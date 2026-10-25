import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.tteles.dock"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.tteles.dock"
        minSdk = 29
        targetSdk = 35
        // CI passes its run number so every published build is newer than the last.
        versionCode = System.getenv("VERSION_CODE")?.toInt() ?: 1
        versionName = "0.$versionCode"
        buildConfigField("String", "UPDATE_REPO", "\"ttmx/android-dock\"")
    }

    // Release key: from env vars in CI, or a keystore.properties outside the repo locally
    // (path in DOCK_KEYSTORE_PROPERTIES, default ~/.android-dock-signing/keystore.properties).
    val keystoreProps = Properties().apply {
        val file = file(
            System.getenv("DOCK_KEYSTORE_PROPERTIES")
                ?: "${System.getProperty("user.home")}/.android-dock-signing/keystore.properties"
        )
        if (file.exists()) file.inputStream().use { load(it) }
    }
    fun secret(env: String, prop: String): String? = System.getenv(env) ?: keystoreProps.getProperty(prop)

    signingConfigs {
        val store = secret("DOCK_KEYSTORE_FILE", "storeFile")
        if (store != null) create("release") {
            storeFile = file(store)
            storePassword = secret("DOCK_KEYSTORE_PASSWORD", "storePassword")
            keyAlias = secret("DOCK_KEY_ALIAS", "keyAlias")
            keyPassword = secret("DOCK_KEY_PASSWORD", "keyPassword")
        }
    }

    buildTypes {
        // Same key for local debug builds, so a phone running one can take CI updates.
        debug {
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
