# SmartTG Downloader — Android (Jetpack Compose)

Native Android client, rebuilt from scratch in Kotlin + Jetpack Compose
(replacing the previous Flutter implementation).

Builds verified locally: `assembleDebug` and `assembleRelease` (R8-minified)
both produce working APKs containing TDLib natives for
arm64-v8a, armeabi-v7a and x86_64.

## Telegram API credentials

The app needs your personal `api_id` / `api_hash` from <https://my.telegram.org>.

Provide them via `android/gradle.properties` (local, gitignored) or
`~/.gradle/gradle.properties`:

```properties
TG_API_ID=123456
TG_API_HASH=0123456789abcdef0123456789abcdef
```

or as environment variables when building on CI:

```bash
export ORG_GRADLE_PROJECT_TG_API_ID=123456
export ORG_GRADLE_PROJECT_TG_API_HASH=0123456789abcdef0123456789abcdef
```

Placeholder values compile fine (for CI validation); real credentials are
injected for release builds via GitHub secrets (see `.github/workflows/`).

## Release signing

Release APKs are signed with a keystore supplied at build time — it is never
committed. Locally, create `android/keystore.properties` (gitignored):

```properties
storeFile=keystore.jks
storePassword=***
keyAlias=upload
keyPassword=***
```

…with the keystore at `android/keystore.jks`. Or export the four values as
Gradle properties / `ORG_GRADLE_PROJECT_*` environment variables.

Without credentials the build still succeeds but emits an **unsigned** APK
(`app-*-unsigned.apk`) that cannot be installed on devices.

CI (`.github/workflows/android-release.yml`) reads these repo secrets:

| Secret | Meaning |
|---|---|
| `ANDROID_KEYSTORE_B64` | `base64` of your `.jks` (`base64 -w0 keystore.jks`) |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Key alias |
| `ANDROID_KEY_PASSWORD` | Key password |

Generate a keystore once with:

```bash
keytool -genkeypair -v -keystore keystore.jks \
  -alias upload -keyalg RSA -keysize 4096 -validity 10000
```

Keep the keystore + passwords safe: losing them means you can never update
the app under the same package id.

## TDLib native library

The app uses the prebuilt TDLib Android distribution
(`core-release.aar` + `ktx-release.aar`, v0.1.0) from the
[tdlib-android](https://github.com/AkashPriyadarshii/tdlib-android) project.

The AARs are **vendored** in `app/libs/` and verified against pinned SHA-256
digests at configuration time (`gradle/build-tdlib.gradle.kts`) — a build fails
immediately if they are missing or modified. No NDK/CMake setup required.

Requirements: JDK 17+, Android SDK with platform `android-37.1`
(Compose BOM 2026.09.00 requires compileSdk 37.1; `minSdk` is 26, the TDLib AAR
minimum). `local.properties` points at the SDK location.

## Build

```bash
cd android
./gradlew :app:assembleDebug            # debug APK
./gradlew :app:assembleRelease          # signed release APK (R8); unsigned if no keystore is configured
./gradlew :app:assembleRelease -PabiFilters=arm64-v8a,armeabi-v7a   # ARM-only
```

CI builds ARM-only release APKs automatically on pushes to the `android`
branch (see `.github/workflows/android-release.yml`).

## Sideloading troubleshooting

The app's package id is the same as the old Flutter build
(`com.swarupbaral.smarttgdownloader`), which matters for the issues below.

### Tapping the icon does nothing

A home-screen **ghost shortcut** from the old/uninstalled build: the icon
remains but no app backs it, so tapping does nothing — no splash, no error.
Fix: long-press the icon → *App info* (or *Settings → Apps*) and remove the
dead entry, then install the APK fresh.

### "App not installed" or silent install failure

1. **Install-unknown-apps permission** (Android 8+): *Settings → Apps →
   Special access → Install unknown apps* → allow the app you're installing
   from (Files, Chrome, …). Without it the install can fail silently.
2. **Play Protect** may scan sideloaded APKs and warn; choose
   *Install anyway* (or *More details → Install*).
3. **Signature conflict**: a previously installed build signed with a
   different key (e.g. an older debug build from another machine) blocks the
   update. Uninstall the old app first, then install:
   ```bash
   adb uninstall com.swarupbaral.smarttgdownloader
   adb install -r android/app/build/outputs/apk/debug/app-debug.apk
   ```

### MIUI / Xiaomi / Redmi / Poco

MIUI blocks USB installs by default even with debugging on:

- *Developer options → Install via USB* → enable (may require signing in to
  a Mi account and waiting a while after first sign-in).
- *Developer options → USB debugging (Security settings)* → enable for
  granting permissions over adb.

### App installed but crashes at launch

Capture the actual reason with logcat while reproducing:

```bash
adb logcat -c && adb logcat --pid=$(adb shell pidof com.swarupbaral.smarttgdownloader) \
  *:E AndroidRuntime:E
```

Or if the process dies instantly, grab the crash from the buffer:

```bash
adb logcat -d -b crash | grep -A 30 "com.swarupbaral.smarttgdownloader"
```

### Reliable install path (recommended)

USB installs bypass unknown-source blocks and Play Protect entirely:
enable *Developer options → USB debugging*, connect the phone, accept the
RSA prompt, then:

```bash
adb devices    # phone must be listed, not "unauthorized"
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.swarupbaral.smarttgdownloader/.MainActivity
```

## Architecture

- `data/TelegramRepository.kt` — TDLib wrapper: auth state machine
  (phone → OTP → 2FA), t.me/tg:// link parsing, chat history scanning,
  file downloads. All exposed as Kotlin Flows.
- `data/ScanRegistry.kt` — per-scan-token bookkeeping; only the latest scan is
  retained, making the stale-scan-cache bug (fixed on Windows) structurally
  impossible on Android.
- `ui/screens/` — Compose screens (Setup, Login, Home + results sheet,
  Settings) driven by ViewModels + StateFlow.
