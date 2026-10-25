package dev.tteles.dock.system

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.tteles.dock.BuildConfig
import dev.tteles.dock.DockApp
import dev.tteles.dock.DockSession
import dev.tteles.dock.R
import dev.tteles.dock.SettingsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Self-updates from GitHub Releases. CI publishes every main build as a release with an
 * `android-dock-<versionCode>.apk` asset; anything with a higher versionCode gets installed.
 */
object Updater {
    private const val TAG = "Updater"
    const val NOTIF_UPDATE = 3
    const val CHANNEL_UPDATES = "updates"

    data class Release(val versionCode: Int, val name: String, val apkUrl: String)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val apkName = Regex("""android-dock-(\d+)\.apk""")

    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(req).execute().use { res ->
            if (res.code == 404) return@withContext null // no releases yet
            if (!res.isSuccessful) error("GitHub returned HTTP ${res.code}")
            val json = JSONObject(res.body!!.string())
            val assets = json.getJSONArray("assets")
            (0 until assets.length()).map { assets.getJSONObject(it) }.firstNotNullOfOrNull { a ->
                apkName.matchEntire(a.getString("name"))?.let { m ->
                    Release(m.groupValues[1].toInt(), json.optString("name"), a.getString("browser_download_url"))
                }
            }
        }
    }

    /** Returns a short status for the settings screen. Installing restarts the app on success. */
    suspend fun checkAndInstall(context: Context): String {
        val release = latest() ?: return "No releases published yet"
        if (release.versionCode <= BuildConfig.VERSION_CODE) return "Up to date (${BuildConfig.VERSION_NAME})"
        Log.i(TAG, "Updating ${BuildConfig.VERSION_CODE} -> ${release.versionCode}")
        val apk = download(context, release)
        install(context, apk)
        return "Installing ${release.name.ifBlank { "build ${release.versionCode}" }}…"
    }

    private suspend fun download(context: Context, release: Release): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
        val file = File(dir, "update.apk")
        client.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { res ->
            if (!res.isSuccessful) error("Download failed: HTTP ${res.code}")
            file.outputStream().use { out -> res.body!!.byteStream().copyTo(out) }
        }
        file
    }

    private fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            // Silent once this app is its own installer of record; Android asks the first time.
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val callback = PendingIntent.getBroadcast(
                context, id, Intent(context, InstallResultReceiver::class.java),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            session.commit(callback.intentSender)
        }
    }
}

/** Checks GitHub every few hours. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val cfg = DockApp.instance.prefs.config.value
        if (!cfg.autoUpdate || !applicationContext.packageManager.canRequestPackageInstalls()) return Result.success()
        // Installing restarts the app, so never pull the dock out from under someone.
        if (DockSession.showing > 0) return Result.retry()
        return try {
            Log.i("Updater", Updater.checkAndInstall(applicationContext))
            Result.success()
        } catch (e: Exception) {
            Log.w("Updater", "Update check failed", e)
            Result.retry()
        }
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("update", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

/** Hears back from the package installer; asks the user via a notification when Android insists. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) {
            if (status != PackageInstaller.STATUS_SUCCESS) {
                Log.w("Updater", "Install failed ($status): ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
            }
            return
        }
        @Suppress("DEPRECATION")
        val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (SettingsActivity.resumed) return context.startActivity(confirm)
        val pi = PendingIntent.getActivity(
            context, 0, confirm, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(Updater.CHANNEL_UPDATES, "Updates", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val n = Notification.Builder(context, Updater.CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_stat_dock)
            .setContentTitle("Dock update ready")
            .setContentText("Tap to install")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(Updater.NOTIF_UPDATE, n)
    }
}
