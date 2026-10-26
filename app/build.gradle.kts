plugins {
    alias(libs.plugins.android.application)
    // Compose compiler plugin; required for any @Composable code since Kotlin 2.0.
    alias(libs.plugins.kotlin.compose)
}

// Release signing and version come from the environment (set by .github/workflows/release.yml);
// a local `assembleRelease` without them produces an unsigned, uninstallable APK on purpose.
val releaseStoreFile = System.getenv("WS_RELEASE_STORE_FILE")
val releaseStorePassword = System.getenv("WS_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("WS_RELEASE_KEY_ALIAS")
val releaseKeyPassword = System.getenv("WS_RELEASE_KEY_PASSWORD")
val hasReleaseSigning = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
    .all { !it.isNullOrBlank() }

// Tag `v1.2.3` → versionName "1.2.3", versionCode 10203 (so each release outranks the last).
val taggedVersion = System.getenv("WS_VERSION_NAME")?.removePrefix("v")?.takeIf { it.isNotBlank() }
val taggedVersionCode = taggedVersion
    ?.split(".")
    ?.mapNotNull { it.takeWhile(Char::isDigit).toIntOrNull() }
    ?.takeIf { it.size == 3 }
    ?.let { (major, minor, patch) -> major * 10_000 + minor * 100 + patch }

android {
    namespace = "ru.vehq.hcsync"
    compileSdk = 37

    defaultConfig {
        applicationId = "ru.vehq.hcsync"
        // Android 14: first release with Health Connect (and exercise routes) in the platform.
        minSdk = 34
        targetSdk = 36
        versionCode = taggedVersionCode ?: 1
        versionName = taggedVersion ?: "0.1.0-dev"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(checkNotNull(releaseStoreFile))
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            // Separate package so a dev build and the signed release can be installed side by side.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // No code shrinking: the APK is small enough and R8 config is one more thing to maintain.
            isMinifyEnabled = false
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            // Lets JVM unit tests touch android.* classes (e.g. android.util.Log) without crashing.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.health.connect.client)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.okhttp)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.org.json)
}
