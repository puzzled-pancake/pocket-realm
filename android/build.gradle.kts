// Top-level build file. Plugins are declared here and applied in :app so versions
// are controlled from one place (the version catalog at gradle/libs.versions.toml).
// AGP 9.x applies the Kotlin Android extension itself, so only the Compose
// compiler plugin is declared separately.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
