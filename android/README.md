# SmartTG Downloader — Android (Jetpack Compose)

Native Android client, rebuilt from scratch in Kotlin + Jetpack Compose
(replacing the previous Flutter implementation).

Builds verified locally: `assembleDebug` and `assembleRelease` (R8-minified)
both produce working APKs containing TDLib natives for **arm64-v8a and
armeabi-v7a** (ARM phones only; emulators and Intel devices are not
supported, which is why the x86/x86_64 natives are excluded).

## APK size

TDLib ships one ~16–23 MB `libtdjni.so` per ABI, so the natives dominate the
APK. Two things keep the download small **without splitting the APK**:

- `packaging.jniLibs.useLegacyPackaging = true` stores the natives
deflate-compressed rather than as raw uncompressed pages (the platform
extracts them once at install).
- Only the two ARM ABIs are packaged.

Measured on a clean build of this tree:

| Artifact | Size |
|---|---|
| Debug APK, all four ABIs, uncompressed natives (before) | 81.2 MB |
| `:app:assembleDebug` (default) | **37.8 MB** |
| `:app:assembleDebug -PminifyDebug=true` | **22.9 MB** |

A debug APK is normally unminified so stack traces stay readable. When size
matters more than that, `-PminifyDebug=true` opts the debug build into the
same R8 shrinking the release build uses; it collapses the DEX from ~64 MB
across 9 files to ~10 MB in one.

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
./gradlew :app:assembleDebug                          # debug APK (37.8 MB)
./gradlew :app:assembleDebug -PminifyDebug=true        # R8-shrunk debug APK (22.9 MB)
./gradlew :app:assembleRelease                         # signed release APK (R8); unsigned if no keystore is configured
./gradlew :app:assembleDebug -PabiFilters=arm64-v8a    # single-ABI, for a smaller build
```

`-PabiFilters=<list>` overrides the packaged ABIs for any build type.

## Tests

```bash
cd android
./gradlew :app:testDebugUnitTest
```

Plain JVM unit tests (JUnit 4, no Robolectric), covering the preference rules
that decide what a download actually does:

| File | Covers |
|---|---|
| `data/ChannelSettingsResolutionTest.kt` | per-channel values overriding the app-wide defaults, the on-screen filter always winning, the `rangeTo = 0` sentinel, idempotent resolution |
| `data/RangeSeedingTest.kt` | range seeding and clamping onto the current list, including the swept invariant that a seeded range is always ordered and inside the list |
| `data/ScanLimitEscalationTest.kt` | the 100 → 500 → 1,000 → 5,000 → unlimited ladder, off-ladder values, unlimited being terminal, and the debug label formatting |
| `data/HistoryWalkTest.kt` | the history paging rules: empty and short pages not ending the walk, the boundary echo being dropped, bounded retries, and a proven truncation being flagged |

These rules had to be *extracted* to be testable at all: the resolution rule now
lives in `resolveChannelSettings`, and the clamp in `SavedRange.seededFor`.
Both were previously inline in a ViewModel and a Composable, where they needed a
`Context`, a live `AppContainer` or a composition to run — and where the only
way to check them was to run scans against real channels.

Not covered: `ChannelPreferenceStore`'s JSON round-trip and the download engine
itself, which would need Robolectric or an instrumented run.

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

## Where downloads are saved

Files land in the user's public Download folder as
`Download/SmartTG Downloader/<channel>/<file>`, and **no storage permission is
requested**. On Android 10+ this goes through `MediaStore.Downloads`, which
lets an app create its own files under `Download/` without any grant; the old
MANAGE_EXTERNAL_STORAGE / all-files-access flow (and its blocking permission
screen) is gone.

Android 8 and 9 predate scoped storage and have no permission-free way to
write there, so on those two releases the classic runtime grant is requested
once, lazily, when a download starts — and if the user declines, the file
falls back to the app's own external directory rather than being lost.

Two things about that path are deliberate:

- **The root folder is the app's full name**, not an abbreviation, because it is
  what the user has to recognise while hunting for their files in the Files app.
- **One folder per channel**, created by MediaStore when the first file lands in
  it. The file *type* is not a folder: the channel is what a person looks for,
  and the extension already says what the file is. A channel title a filesystem
  would reject is sanitised — separators and reserved characters replaced, a
  name of nothing but dots becomes `Telegram` so it cannot resolve *outside* the
  app's own folder, and an over-long name is shortened to fit one path
  component, byte-counted and cut on character boundaries. (Builds before this
  wrote to `Download/SmartTG/<channel>/<category>/`; files already there are
  left exactly where they are.)

### Moving files out of the old tree

An earlier 2.2.0 development build wrote to `Download/SmartTG/<channel>/<type>/`.
On the first launch after this change those files are moved into the per-channel
layout automatically. There is no prompt, because a prompt nobody answers leaves
files in a folder the app no longer writes to — a worse default than tidying them
up.

Two properties matter more here than speed:

- **A file is never lost, and never duplicated.** Each file is copied into its
  channel folder, the copy is written fully (marked pending until it is), and only
  then is the original removed. If the copy cannot be written, the original is
  untouched; if the original cannot be removed, the copy is rolled back. The only
  possible outcome is "still where it was", and that is reported.
- **It finishes, or it retries.** The done-flag is set only by a pass that
  completed, so a partial move — a failure, or Android 8–9 without the legacy
  storage grant — is picked up on the next launch instead of being forgotten.

The move is copy-then-delete rather than a metadata-only `RELATIVE_PATH` update,
because that behaves identically on every supported release and each step can be
checked. History rows are repointed at the file's new location in the same pass,
so Open keeps working on a file that has moved.

The old tree is removed once it is empty, so the user is not left staring at the
folder they were just told the app no longer uses.

### Opening the Download folder

Because the files go straight into Downloads, the app can take the user there
instead of describing the path. **Open Downloads folder** appears on the
completion card on Home, in the results panel that started the batch, in
History, and as an action on the completion notification — the channel's own
folder when the batch saved something into it, otherwise the Download root.

It is deliberately never opened automatically: being thrown out of the app the
moment a download completes is disorienting, and for a screen reader user it
would move the focus somewhere they never asked to go.

There is no API for showing a shared folder, so the app composes the document
URI the platform's storage provider itself uses — the folder as a *directory*
first, then the Download root as a browsable *group* — and lets whichever file
manager is installed open it. Each candidate is tried by actually starting it,
rather than gated on `resolveActivity`, which since Android 11 returns null for
packages the app cannot see and would report "no file manager" on a device that
has one. If nothing accepts a folder, the button says so and names the path
instead of doing nothing.

The notification's action routes through `MainActivity` rather than starting a
file manager directly: a notification action may not start another app's
activity from the background on Android 10+, while this app's own activity is
the case the platform does allow. The folder is then opened from `onPostResume`,
from the foreground.

### Channels at the top of History

Above the list, one card per channel with at least one downloaded file: its name,
how many files it holds, the space they take, and **Open folder**. The cards scroll
with the list rather than sitting in a fixed block, so a long history still gets
the screen to itself.

They are ordered by when the channel was last downloaded from, not by size or by
count, because "what did I just work on?" is the question this screen gets opened
with. The size reads as a floor when Telegram withheld sizes for some files —
*"12 files · 2.4 GB or more"* — rather than presenting a guess as a total. A
channel whose downloads all failed gets no card: there is nothing in its folder to
open.

The same count also appears on each entry that links to that channel's folder, and
both come from one computation, so a row and the card above it can never disagree
about how many files a channel holds. Two rules keep those numbers honest:

- **Counted from the whole history**, not the filtered list, so filtering by type
  cannot make a channel look emptier than it is.
- **Keyed on the folder, not the title**, so two titles that sanitise to the same
  folder are one card rather than two each claiming half the files. Failed attempts
  are excluded — they left no file behind, so counting them would report a batch as
  complete when nothing arrived. A renamed channel is deliberately *two* cards,
  which is the truthful answer: a rename really is a second folder on disk, and
  merging them would point one card's button at a folder holding only part of what
  the card counts.

ZIP archives are deliberately left out of these cards. An archive holds the very
files a card counts, so adding its size to the total would count the same bytes
twice, and adding its file count would count the files twice as well. A channel
known only through an archive therefore has no card — its archive is still listed
below, with its own Open action and the folder it lives in.

### Choosing the connection

Settings → *Download connection* controls which networks downloads may use:
**Wi-Fi and mobile data** (the default) or **Wi-Fi only**. The check runs
before a download starts and explains itself when it blocks.

"Wi-Fi only" is enforced against the platform's *not metered* capability
rather than a literal Wi-Fi transport check, so ethernet and unmetered
hotspots count as allowed while a metered hotspot does not — the question that
actually matters is whether the transfer costs the user data.

It is a pre-flight check, not a mid-transfer watchdog: a download already
running when Wi-Fi drops finishes on whatever connection is live.

## Downloading a selection as one ZIP

**Download ZIP** sits beside **Start download** in the results panel. It fetches
*the same selection* and hands it over as a single `.zip` archive in the channel's
folder — `Download/SmartTG Downloader/MC/MC - Audio (20 files).zip` — instead of
as separate files. There is nothing to set up first: it is the same choice of
files, taken a different way, which is why it — and the one switch it has — sit
next to the button rather than in Settings.

By default the archive is the deliverable, so the individual files are **not**
also saved: saving both doubles the space a selection costs and leaves the user
with two copies of everything to tidy up. **Also keep the separate files**, the
switch beside the button, keeps both. It is off by default, remembered between
batches, and recorded on the request itself — so an archive queued before the
switch was flipped still does what it was asked to do. Keeping the files is what
makes a failed archive harmless: the sentence then says the files are still there
rather than reading like a lost download.

**A finished archive is its own entry in History**, with a ZIP icon and the word
*ZIP* where a file row states its type. It carries **Open** and **Delete** and no
Re-download: the archive was built on this device out of files that were fetched
once, so there is no message to fetch again — and a Re-download that quietly
fetched the packed files as separate ones would be doing something other than
what the button says. The engine refuses one for an archive as well as the screen
hiding it, since a promise the rest of the app does not keep is worse than no
button at all.

The entry is written only once the archive is really on disk, so an Open action
always opens something. Its size is the archive's own size rather than the sum of
what is inside it — that is what the entry costs the user's storage. It is filed
under the type it packed, so a zip of audio is still found by filtering for
audio; an archive of a mixed "All" selection belongs to no single type and is
filed under All rather than being mislabelled as whichever type happened to come
first.

**Why it is written in two phases.** Every file is fetched into TDLib's cache
first, and the archive is written in a single pass afterwards. Writing entries as
they arrive would be faster, but a zip cannot be appended to from the middle — so
a single failed file would leave a half-built archive that can never be finished,
making one bad file cost the whole selection. Instead, a file that cannot be
fetched is skipped and reported: *"Saved MC - Audio (20 files).zip (18 file(s))…
2 of 20 file(s) could not be downloaded, so they are not in the archive."*

Whether a skipped file joins the Retry list depends on the mode, because Retry
means something different in each. In the default archive-only mode it does
**not**: a file inside an archive cannot be retried on its own, and a Retry button
that quietly downloaded it as a separate file would be doing something other than
what the user asked for. With **Also keep the separate files** on, it does — the
file was meant to be on disk anyway, so retrying it there is exactly the promise.

**Interruptions.** An archive is built from scratch, so a run killed part-way
throws its partial archive away and rebuilds on the next launch. That is cheap
rather than wasteful: the files are already in TDLib's cache, so the second pass
reads them from disk instead of fetching them again. The request itself is
persisted like the download queue, so it survives a process death instead of
disappearing with the screen that made it.

Two things worth knowing about the archive's contents:

- **Entry names are safe to extract.** A path separator in an entry name is a
  zip-slip hazard for any extractor that joins names onto a destination folder,
  and Telegram file names can contain one, so separators become underscores.
- **Duplicate names are numbered** (`track.mp3`, `track (2).mp3`), compared
  case-insensitively because the filesystem the archive is extracted onto may not
  distinguish `Track.mp3` from `track.mp3`.

The bytes are streamed straight into their final home — a MediaStore row that
stays invisible until the archive is complete, or a `.partial` file renamed on
Android 8–9 — never assembled in a temporary file and copied in, which would need
space for the archive twice over.

**Not listed in History, on purpose.** History's rows are one per Telegram
message, and an archive has no message behind it; inventing a row would also put
a Re-download action on something that cannot be re-downloaded. The completion
card on Home and the notification report the archive instead, each with the
button that opens its folder.

## Background downloads

Downloads run in `DownloadService`, a foreground service, not in a ViewModel —
leaving the app used to destroy the ViewModel and the transfer with it. While a
batch runs, a low-importance notification shows overall progress, the current
file name, and a **Cancel** action.

**Resuming works at two levels, and both are needed:**

- *Per file* — TDLib keeps partial downloads in its own cache and continues from
  the bytes it already has, so an interrupted file is not re-fetched from
  scratch. This is why the app never deletes TDLib's cache.
- *Per batch* — the remaining queue is persisted to disk and an item is removed
  only once it has been fully handled. A killed process therefore resumes the
  rest of the batch instead of starting over; the queue is resumed when the app
  is next opened and Telegram reports an authorized state.

A partial `MediaStore` write cannot be left behind, because the file is only
published to the Download folder after TDLib has the complete copy.

The queue is stored as JSON in SharedPreferences rather than in Room. It is
transient work-in-progress, not user data, and a Room entity would mean a schema
version bump plus a hand-written migration — one that could wipe the download
history if it were subtly wrong. A queue that fails to parse simply means
"nothing pending".

### Failures and retry

A failed file is retried twice inside the engine (1s, then 3s backoff) before
it is reported. Failures here are mostly transient, and TDLib resumes from the
bytes it already holds, so a retry is nearly free — while a failure the user has
to clear by hand is not.

Retrying never happens on its own after that. A file that just failed three
times will not succeed while the user is not looking, so the choice is theirs:

- the **Home screen** shows a failure card with *Retry failed downloads* and
  *Dismiss*;
- each failed **row** in the results panel carries its reason and its own
  *Retry*, so one broken file does not mean re-fetching the batch;
- the completion **notification** offers *Retry failed* when a run ended with
  errors;
- **History** rows that failed offer *Retry* where successful ones offer
  *Re-download*.

Failed items live in their own persisted list, separate from the pending queue.
They are deliberately *not* pending work — nothing resumes a failed file
automatically — but persisting them keeps Retry available after a process
death, and a file that later succeeds (say, re-downloaded by hand from History)
drops off the list. Retrying goes through the normal engine, so it respects the
Wi-Fi-only setting, survives leaving the app, and is itself resumable.

Failures that retrying cannot fix (the message was deleted, or the row was
never downloadable media) are reported immediately instead of spending two
backoff waits on them.

Other details worth knowing:

- A `PARTIAL_WAKE_LOCK` is held only while downloading; without it the CPU can
  suspend with the screen off and the transfer stalls mid-file.
- `START_NOT_STICKY` plus resume-on-open, rather than a sticky service: an
  interrupted batch continues when the user next opens the app instead of
  springing back to life invisibly in the background.
- The service is started only from the UI, never from the background, so it
  cannot trip the Android 12+ background foreground-service start restrictions.
- On Android 13+ the **progress notification** needs `POST_NOTIFICATIONS`. It is
  requested with one tap at the moment a download starts, and declining only
  hides the notification — the download runs either way.
- A plain foreground service was chosen over WorkManager deliberately: this is
  already-started, latency-sensitive transfer driven by a live TDLib client, and
  WorkManager's constraint/retry scheduling models something else entirely (and
  would need a foreground service anyway).

## Login and accessibility

The sign-in flow is split into a phone step and a code step, so only the
relevant controls are on screen at a time.

- **Resend** is offered on an escalating countdown: 30s, then 50s, then a
  minute. Telegram's own cooldown (`AuthenticationCodeInfo.timeout`, surfaced
  through `AuthState.AwaitingCode.resendInSeconds`) always wins when it is
  longer. The remaining wait is part of the button's label and its
  `stateDescription`, so TalkBack explains why the control is unavailable
  instead of presenting a silently greyed-out button.
- **Announcements.** A successful code request is announced as *"OTP sent
  successfully."* — the only announcement the login screen makes. It is a
  Compose **live region**, not a forced announcement: Android 16 removed
  `TYPE_ANNOUNCEMENT` events and `announceForAccessibility`, so the old
  approach is silently dead on a current device. The live region is re-keyed on
  every send, so a repeat resend is spoken again rather than being swallowed as
  an unchanged node.

## The Home flow, in order

1. **Paste the link.** The analyze step appears below the field once there is a
   link, and is a live region, so it is announced when it arrives instead of
   silently appearing off-screen.
2. **Press Analyze, and answer the dialog** — *"What do you want to download?"* —
   with `All`, `Audio`, `Video`, `Images` or `Docs`. The scan then looks for that
   one type, so a big channel is not trawled for media the user has already ruled
   out; `All` means every file in the link, whatever its type.

The dialog appears *on the press* rather than as chips waiting above the button,
because the choice belongs to the scan about to run: the type is settled at the
moment the user asks for one. It confirms rather than acting on the first tap,
and the dialog's own list is the same `All / Audio / Video / Images / Docs` set
Settings offers — no screen invents a type the others do not know about. The
choice is remembered, so reopening the dialog shows what is set now.

**Don't ask again** is the dialog's one other control. Ticked, the answer becomes
the remembered one: pressing Analyze scans for that type straight away, and the
step says which type it is using — *"Analyze looks for Audio files without asking,
because you turned the question off"* — with **Change type** beside the button
reopening the dialog. Unticking the box, or the **Ask which type before
analyzing** switch in Settings, is how the question comes back; neither is hidden,
which matters because a preference that can only be set and not unset is a trap.
The switch lives under *Default filter* because that value is what a skipped
question uses: one remembered type, shown in exactly one place, rather than a
private copy that could drift from what Settings says.

A remembered type this build does not recognise (a value written by a newer
version, or a corrupted preference) falls back to `All` rather than being handed
to the scan. A stale type would match nothing and report the channel as empty —
blaming the channel for a bad setting, and the most misleading answer the app can
give.
3. **The count is announced**: *"200 Audio files found. Do you want to download
   all 200 files, or would you like to select a range?"*
4. **Download all, or select a range** (numbers or the Newest 10/20/50/100
   presets).
5. **The size is stated before anything is fetched**: *"You have selected 50
   file(s). The total download size is 2.5 GB."*
6. **Start download.**

Because a scan answers for exactly one type, choosing a different one drops
results that were collected for the previous type — old rows for "Audio" say
nothing about "Video", and leaving them on screen would invite downloading files
that were never looked for.

### Changing the type from the results panel

The results panel carries its own **File type** control, the same
`All / Audio / Video / Images / Docs` set as everywhere else. Choosing a type
scans again immediately: the user is looking at a scan's output and asking for it
to be different, which is one intention, not two. Tapping the type that is already
selected does nothing rather than starting a redundant scan. Whatever is chosen
is remembered as the default filter, so the next link starts there — but this does
**not** touch the *Ask which type before analyzing* preference, because a change
made here is not an answer to that question.

The panel stays open across that rescan, which is why it is gated on an explicit
open flag rather than on there being rows to show: a rescan clears the rows it is
replacing, so deriving the panel's existence from them would close it at the exact
moment the user asked it to do something. While the rescan runs the panel says
which type it is scanning for, offers **Stop scanning**, and keeps the type
control in place — as it does for a scan that comes back with nothing, since
*"No Video files found"* is a statement about one type and the way to act on it is
one tap up. Home's own scanning block is suppressed while the panel is open, so
exactly one surface reports a scan rather than leaving a Stop button covered by
the sheet.

The type chips are held closed while a download is running, and the panel says so:
a rescan clears the rows, and those rows are where the per-file progress lives.

An empty result distinguishes the two cases that need different fixes:

- the channel has **no files of the chosen type** — reported with the number of
  messages actually scanned (so a real result is distinguishable from a failed
  scan), plus how much *other* media the walk passed on the way, which is what
  points a user at the right file type without re-scanning blindly; or
- the channel **has** files of that type, but none match the active filter.

The size line stays honest when Telegram has not reported a size for some
files: it reads *"at least 2.5 GB (size not reported for 3 file(s))"* rather
than presenting a lower bound as a fact.

Sizes shown are Telegram's declared file sizes from the scan. The download
action only appears once a selection exists, so it is never reachable without a
stated total.

The filter option list lives in one place, `AppPreferences.DOWNLOAD_CATEGORIES`
(`All`, `Audio`, `Video`, `Images`, `Docs`). Settings renders it, and the Home
and History filter rows render exactly the same list — no screen invents extra
values (History previously also offered a chip per channel, which has been
removed).

### Walking a large channel's history

The history walk's paging rules live in `data/HistoryWalk.kt`, a pure class with
its own tests, because they are where a large channel used to go wrong:

- **A short or empty page is not the end of the chat.** `getChatHistory` answers
  out of the history TDLib has already loaded and back-fills the rest from the
  server in the background, so a first pass over a big channel legitimately
  returns nothing — or a handful of messages. The walk re-asks for the same
  range (three attempts spanning ~1.2s) instead of concluding the channel is
  empty, and the UI says *"Waiting for Telegram to load older messages…"* while
  it does.
- **A continuation page re-delivers one message.** `getChatHistory` with
  `offset = 0` includes `from_message_id` itself, so the previous page's oldest
  message arrives again. Only ids below everything already handled are accepted,
  which keeps the results free of duplicate rows (and duplicate Compose keys).
- **Giving up is reported.** `TdApi.Messages.totalCount` is Telegram's own
  approximate message count. When the walk stops short of it, the result is
  marked `historyIncomplete` and the UI says the list may be missing files and
  offers **Scan again**, instead of claiming the channel has none.

The walk only ever asks from a message id it actually received. TDLib wants an
id that exists in the chat, so a guessed `oldest - 1` is not safe.

### Large channels: scan limit and range selection

History is walked newest-first, so results are ordered most-recent-first and
"file 1" is always the newest match.

- **Scan limit** (Settings) caps how many matching files a single analysis
  collects: 100, 500, 1,000, 5,000, or everything. It bounds both the wait and
  the memory on a channel holding tens of thousands of files. When the cap is
  reached the UI says so explicitly — both on the results panel and in the
  empty state — so a truncated scan is never mistaken for a complete one.
- **Range selection** in the download panel takes a first and last file number,
  so 20 files out of 50,000 are picked by typing two numbers instead of
  scrolling and ticking. **Newest 10 / 20 / 50 / 100** presets cover the usual
  case in one tap (a preset larger than the list is disabled rather than
  silently clamped, so the button never claims more than it does).
- The range that was last applied is **remembered** and re-seeded on the next
  analysis, clamped to the new list so a range carried over from a larger
  channel can never come back invalid. Only a range that validated is stored,
  so a rejected entry never overwrites one that worked.

### Per-channel preferences

Filter, range and scan limit are remembered **per channel**, keyed by the
resolved Telegram chat id — the only identifier that is stable across
`t.me/name`, `t.me/c/...` and invite links, which all resolve to the same chat.
Keying on the link text would remember one channel several times over.

The app-wide values in Settings are *defaults* for channels with nothing saved,
not global overrides. A channel gains its own entry the first time it is
analyzed, and that entry follows the channel:

- changing the Home filter, or applying a range, while a channel is loaded
  writes to that channel rather than to every other one;
- a scan cut short by its limit offers **Search deeper** in the results panel,
  which raises *that channel's* limit one step and analyzes again — so
  deepening one channel's search does not quietly change the others;
- Settings lists every remembered channel with what it holds, and can forget one
  or all of them.

Because the channel is only known once its link resolves, analysis is two steps
internally: resolve the link, load that channel's preferences, then walk its
history. The saved limit has to be in hand *before* the scan starts, since it is
what stops the walk.
- Analysis reports live progress ("N messages checked, M files found") and can
  be stopped mid-run.

Memory: a scan retains only lightweight `ScannedFile` rows — each carrying the
file's TDLib id, which is what routes download progress to the right row.
Individual messages are fetched on demand when a download starts, so analyzing
an enormous channel does not pin thousands of `TdApi.Message` objects.

## Architecture

- `data/TelegramRepository.kt` — TDLib wrapper: authorization state machine
  (phone → OTP → 2FA), t.me/tg:// link parsing, complete chat history scanning,
  and fetching files into TDLib's cache. All exposed as Kotlin Flows.
- `data/DownloadsStore.kt` — publishes finished files into the public Download
  folder via MediaStore (or the legacy path/fallback on Android 8–9).
- `data/ScanRegistry.kt` — per-scan-token bookkeeping; only the latest scan is
  retained, making the stale-scan-cache bug (fixed on Windows) structurally
  impossible on Android.
- `data/AppPreferences.kt` — one typed SharedPreferences facade; notably the
  single source of truth for the filter options.
- `ui/screens/` — Compose screens (Setup, Login, Home + results sheet,
  History, Settings) driven by ViewModels + StateFlow.
