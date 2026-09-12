import java.io.FileInputStream
import java.util.Properties

// Resolved once at configuration time; null when no signing credentials are
// available (the release build then falls back to an unsigned APK).
val signingProps = loadSigningProperties()

plugins {
    id("com.android.application")
    // AGP 9 provides built-in Kotlin support; the Compose compiler plugin
    // is still required when compose is enabled.
    id("org.jetbrains.kotlin.plugin.compose")
    // KSP runs Room's annotation processor at Kotlin-compiler level.
    id("com.google.devtools.ksp")
}

apply(from = rootProject.file("gradle/build-tdlib.gradle.kts"))

android {
    namespace = "com.swarupbaral.smarttgdownloader"
    // Compose 1.12 (BOM 2026.09.00) requires compiling against SDK 37.1
    compileSdk = 37

    defaultConfig {
        applicationId = "com.swarupbaral.smarttgdownloader"
        // TDLib core AAR requires API 26+
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "2.1.0"

        // CI can restrict native ABIs (e.g. ARM-only releases) via -PabiFilters.
        // AGP 9 defaults to the host ABI (x86_64) when no explicit NDK abiFilters
        // are set, so release builds must pass ARM ABIs explicitly.
        val requestedAbis = (project.findProperty("abiFilters") as String?)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf("arm64-v8a", "armeabi-v7a", "x86_64") // local dev default
        ndk {
            abiFilters.clear()
            abiFilters.addAll(requestedAbis)
        }

        // Telegram API credentials are injected at build time from
        // gradle.properties / environment (TG_API_ID / TG_API_HASH).
        // Placeholders are tolerated so CI can validate compilation; they are
        // replaced by real secrets for release builds (see .github workflow).
        val apiId = (project.findProperty("TG_API_ID") as String?)?.toIntOrNull() ?: 0
        val apiHash = project.findProperty("TG_API_HASH") as String? ?: ""
        buildConfigField("int", "TG_API_ID", "$apiId")
        buildConfigField("String", "TG_API_HASH", "\"$apiHash\"")
    }

    signingConfigs {
        create("release") {
            if (signingProps != null) {
                storeFile = rootProject.file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Attach signing only when credentials exist — assigning an empty
            // config makes AGP's validateSigningRelease fail the build.
            if (signingProps != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // TDLib prebuilt native distribution (core = JNI + native libs for all ABIs,
    // ktx = Kotlin coroutines/Flow wrapper). Downloaded and verified by
    // gradle/build-tdlib.gradle.kts.
    implementation(files("libs/core-release.aar", "libs/ktx-release.aar"))

    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.navigation:navigation-compose:2.9.6")

    val roomVersion = "2.8.5"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    implementation("io.coil-kt.coil3:coil-compose:3.3.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}

/**
 * Locates signing credentials for the release build.
 *
 * Resolution order:
 *  1. android/keystore.properties (gitignored — CI writes it from secrets,
 *     local devs create it once) with keys: storeFile, storePassword,
 *     keyAlias, keyPassword.
 *  2. Gradle properties / environment variables (ORG_GRADLE_PROJECT_*)
 *     of the same names.
 *
 * Returns null when no credentials are present: the release build then
 * produces an unsigned APK rather than failing.
 */
fun loadSigningProperties(): Properties? {
    val fromFile = runCatching {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) FileInputStream(f).use { Properties().apply { load(it) } } else null
    }.getOrNull()
    if (fromFile != null && fromFile.getProperty("storeFile") != null) {
        return fromFile
    }

    val keys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    val values = keys.associateWith { key ->
        (project.findProperty(key) as String?)?.takeIf { it.isNotBlank() }
    }
    return if (values.values.all { it != null }) {
        Properties().apply { values.forEach { (k, v) -> setProperty(k, v!!) } }
    } else {
        null
    }
}
