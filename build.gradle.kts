// Root build file: only declares plugin versions (from gradle/libs.versions.toml);
// the single module in app/ applies them.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
