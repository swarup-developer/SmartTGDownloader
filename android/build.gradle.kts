// Top-level build file for SmartTG Downloader (native Android, Jetpack Compose).
// AGP 9 ships built-in Kotlin support; the Compose compiler plugin is still
// required explicitly when compose is enabled.
plugins {
    id("com.android.application") version "9.3.2" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
    id("com.google.devtools.ksp") version "2.3.12" apply false
}
