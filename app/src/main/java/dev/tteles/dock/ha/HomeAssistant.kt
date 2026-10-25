package dev.tteles.dock.ha

import android.util.Log
import dev.tteles.dock.DockConfig
import dev.tteles.dock.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class EntityState(val id: String, val state: String, val attributes: JSONObject) {
    val domain get() = id.substringBefore('.')
    val name: String get() = attributes.optString("friendly_name").ifBlank { id }
    val unit: String get() = attributes.optString("unit_of_measurement")
    val available get() = state != "unavailable" && state != "unknown"
    fun attr(key: String): String? = attributes.opt(key)?.takeIf { it != JSONObject.NULL }?.toString()
    fun attrDouble(key: String): Double? = attr(key)?.toDoubleOrNull()

    companion object {
        fun from(o: JSONObject) = EntityState(
            o.getString("entity_id"),
            o.optString("state"),
            o.optJSONObject("attributes") ?: JSONObject(),
        )
    }
}

enum class HaStatus { NotConfigured, Connecting, Connected, AuthFailed, Error }

/**
 * Talks to Home Assistant over its WebSocket API: pulls every state once, then follows
 * state_changed events so the dock updates live. Only connected while the dock is on screen.
 */
class HomeAssistant(private val prefs: Prefs) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    private val _states = MutableStateFlow<Map<String, EntityState>>(emptyMap())
    val states: StateFlow<Map<String, EntityState>> = _states
    private val _status = MutableStateFlow(HaStatus.NotConfigured)
    val status: StateFlow<HaStatus> = _status

    private val ids = AtomicInteger(1)
    private var socket: WebSocket? = null
    private var loop: Job? = null
    private var users = 0

    @Synchronized
    fun acquire() {
        if (users++ == 0) loop = scope.launch { runLoop() }
    }

    @Synchronized
    fun release() {
        if (--users > 0) return
        users = 0
        loop?.cancel()
        socket?.close(1000, null)
        socket = null
    }

    /** Drop the current socket so new settings are picked up. */
    fun reconnect() {
        socket?.cancel()
    }

    private suspend fun runLoop() {
        var backoff = 1_000L
        while (true) {
            val cfg = prefs.config.value
            if (!cfg.haConfigured) {
                _status.value = HaStatus.NotConfigured
                delay(2_000)
                continue
            }
            _status.value = HaStatus.Connecting
            val closed = kotlinx.coroutines.CompletableDeferred<Unit>()
            socket = client.newWebSocket(
                Request.Builder().url(wsUrl(cfg.haUrl)).build(),
                Listener(cfg, closed),
            )
            closed.await()
            if (_status.value == HaStatus.AuthFailed) {
                // No point hammering HA with a bad token; wait for settings to change.
                val token = cfg.haToken
                while (prefs.config.value.haToken == token) delay(1_000)
                continue
            }
            if (_status.value != HaStatus.Connected) _status.value = HaStatus.Error
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(30_000)
        }
    }

    private inner class Listener(
        val cfg: DockConfig,
        val closed: kotlinx.coroutines.CompletableDeferred<Unit>,
    ) : WebSocketListener() {
        private var statesRequest = -1

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = JSONObject(text)
            when (msg.optString("type")) {
                "auth_required" -> webSocket.send(
                    JSONObject().put("type", "auth").put("access_token", cfg.haToken).toString()
                )
                "auth_invalid" -> {
                    _status.value = HaStatus.AuthFailed
                    webSocket.close(1000, null)
                }
                "auth_ok" -> {
                    _status.value = HaStatus.Connected
                    // Subscribe first so nothing slips between the snapshot and the stream.
                    webSocket.send(
                        JSONObject().put("id", ids.getAndIncrement()).put("type", "subscribe_events")
                            .put("event_type", "state_changed").toString()
                    )
                    statesRequest = ids.getAndIncrement()
                    webSocket.send(JSONObject().put("id", statesRequest).put("type", "get_states").toString())
                }
                "result" -> if (msg.optInt("id") == statesRequest && msg.optBoolean("success")) {
                    val arr = msg.getJSONArray("result")
                    _states.value = (0 until arr.length()).map { EntityState.from(arr.getJSONObject(it)) }
                        .associateBy { it.id }
                }
                "event" -> {
                    val data = msg.optJSONObject("event")?.optJSONObject("data") ?: return
                    val id = data.optString("entity_id")
                    val newState = data.optJSONObject("new_state")
                    _states.update { if (newState == null) it - id else it + (id to EntityState.from(newState)) }
                }
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            closed.complete(Unit)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "websocket failure", t)
            if (_status.value != HaStatus.AuthFailed) _status.value = HaStatus.Error
            closed.complete(Unit)
        }
    }

    fun callService(domain: String, service: String, entityId: String, data: JSONObject? = null) {
        val msg = JSONObject()
            .put("id", ids.getAndIncrement())
            .put("type", "call_service")
            .put("domain", domain)
            .put("service", service)
            .put("target", JSONObject().put("entity_id", entityId))
        if (data != null) msg.put("service_data", data)
        socket?.send(msg.toString())
    }

    /** One-off REST fetch of all entities, used by the settings screen (works without the socket). */
    suspend fun fetchStates(url: String, token: String): Result<List<EntityState>> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url(url.trim().trimEnd('/') + "/api/states")
                .header("Authorization", "Bearer ${token.trim()}")
                .build()
            client.newCall(req).execute().use { res ->
                if (res.code == 401) error("Token rejected (401)")
                if (!res.isSuccessful) error("HTTP ${res.code}")
                val arr = JSONArray(res.body!!.string())
                (0 until arr.length()).map { EntityState.from(arr.getJSONObject(it)) }.sortedBy { it.id }
            }
        }
    }

    companion object {
        private const val TAG = "HomeAssistant"

        fun wsUrl(base: String): String {
            val b = base.trim().trimEnd('/')
            return when {
                b.startsWith("https://") -> "wss://" + b.removePrefix("https://")
                b.startsWith("http://") -> "ws://" + b.removePrefix("http://")
                else -> "ws://$b"
            } + "/api/websocket"
        }
    }
}
