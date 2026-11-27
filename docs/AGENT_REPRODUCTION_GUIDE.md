# Agent Reproduction Guide

A precise, step-by-step guide for an AI coding agent (or a human) to reproduce the changes in this
fork from the upstream base commit. Follow it top to bottom.

## 0. Target

- **Upstream base:** `Orang-Studio/InstaDownload` at commit `e21a36a` ("Bump to 3.1.2").
- **Result:** this fork, which adds easy save, background progress, a failed-link retry list,
  success/fail sounds, clean links, preview caching and gallery fixes.
- **Project root inside the repo:** `InstaDownload/` (the Gradle project is nested one level deep).

## 1. Environment

| Tool | Version / note |
| --- | --- |
| JDK | 17+ (17 in CI; 21 verified locally) |
| Android SDK | `platforms;android-37.0` (not `android-37`), plus `build-tools` |
| Gradle | use the bundled wrapper `./gradlew` (Gradle 9.8.2) |
| AGP / Kotlin | AGP 9.4.1 / Kotlin 2.4.21 (see `gradle/libs.versions.toml`) |
| SDK levels | `compileSdk=37`, `targetSdk=36`, `minSdk=24` |

Set `ANDROID_HOME` (or `sdk.dir` in `local.properties`) and `JAVA_HOME`. Example:

```bash
export ANDROID_HOME=/opt/android-sdk
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
cd InstaDownload        # the nested Gradle project
./gradlew :app:assembleDebug --no-daemon
```

The debug APK is at `app/build/outputs/apk/debug/instadownload-debug.apk`.

## 2. Files to create

### `…/instadownload/FailedDownloads.kt`

A store for links that failed.

- `data class FailedDownload(url, error, failedAt, attempts)`.
- `object FailedDownloads` with:
  - `load(context): List<FailedDownload>` — parse JSON from `SharedPreferences("failed_downloads")`,
    key `"records"`, defensive (`runCatching`, filter blank URLs).
  - `add(context, url, error)` — `@Synchronized`; clean the URL with
    `InstagramDownloader.cleanInstagramUrl`, dedupe by URL (bump `attempts`, refresh `error`/`failedAt`),
    cap at `MAX_ENTRIES = 200`, newest first.
  - `remove(context, url)` and `clear(context)`.
  - private `save(context, records)` writing a `JSONArray`.

### `…/instadownload/DownloadSounds.kt`

- `object DownloadSounds` with `playSuccess(context, enabled)` using `ToneGenerator.TONE_PROP_ACK`
  and `playFailure(context, enabled)` using `ToneGenerator.TONE_SUP_ERROR`.
- Play on `Dispatchers.Default` with a `ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)`,
  `startTone(tone, 200)`, sleep ~260 ms, then `release()` in a `finally`.

## 3. Files to modify

### `AppSettings.kt`

Add a `soundsEnabled` preference (key `"sounds"`, default `true`), mirroring `hapticsEnabled`.

### `MainActivity.kt`

- On save success: play the success tone. Do **not** remove the link from `FailedDownloads`.
- On any fetch/save failure: play the failure tone and `FailedDownloads.add(context, url, error)`.
- Record every pasted/fetched link with `LinkHistory.add(context, url)`.
- Add a **"Download sounds"** toggle in `SettingsDialog` (mirror the haptics toggle), using
  `LocalContext.current`, and play a preview tone when it is switched on.

### `QuickDownloadService.kt`

- Single-download path: on success play success (no `FailedDownloads.remove`); on failure play failure
  + `FailedDownloads.add`. Record the shared link with `LinkHistory.add(...)`.
- Batch path: record the failing post URL and play one sound for the overall result.
- Add `companion object { fun startDownload(context, url) }` that starts the service for a URL, so
  the UI can retry.

### `DownloadsDialog.kt`

- Add `failed` / `links` state and **"Failed (N)"** and **"Links"** tabs to the tab row (Failed is
  error-colored when selected).
- Add `ColumnScope.FailedDownloadsList(...)`: one card per failed link showing the URL, when it
  failed, attempts and the error; actions **Retry**, **Retry all**, **Remove**, **Clear failed**.
  Retry must **not** call `FailedDownloads.remove`.
- Add `ColumnScope.LinkHistoryList(...)` backed by `LinkHistory`: per-row **Download** / **Remove**
  and a header **Clear history** guarded by an `AlertDialog` confirmation.
- Add a shared multi-select mode (`selecting` + `selected: Set<String>`): long-press a row to start,
  tap to toggle, with a `SelectionBar` offering **Select all / Clear selection / Copy / Share /
  Export**.
- Helper functions: `copyLinks` (`ClipData.newPlainText`, newline-joined), `shareLinks`
  (`ACTION_SEND` text), `exportLinks` (writes `cacheDir/exports/links.txt` and shares it through the
  `FileProvider`).
- **Compose gotchas:**
  - Any composable that uses `Modifier.weight` must be a `ColumnScope`/`RowScope` extension
    (import `androidx.compose.foundation.layout.ColumnScope`).
  - `stringResource(...)` cannot be called inside a non-composable lambda such as `onLongClick`.
    Resolve the string above the lambda and capture it.

### `InstagramDownloader.kt`

- Add `cleanInstagramUrl(url)` — trim, strip everything from `?` and `#`, trim trailing `/`, re-add a
  single `/`.

### `DownloadStorage.kt`

- Ensure written files are announced via `MediaScannerConnection` so they appear in the gallery.

### `LinkHistory.kt`

- Add a new object mirroring `FailedDownloads` but for every pasted/shared link, keyed by cleaned
  URL: record `{ url, firstSeen, lastSeen, count }`, `add`/`remove`/`clear`, capped at 500, never
  auto-removed.

### `strings.xml`

Add: `sound_feedback_label`, `sound_feedback_description`, `downloads_failed`,
`failed_downloads_empty`, `failed_downloads_empty_hint`, `retry_download_label`, `retry_all_label`,
`clear_failed_label`, `remove_label`, `failed_attempts_format`, `failed_when_format`,
`retry_started`, `failed_removed`, `failed_cleared`, `failed_link_clipboard_label`,
`failed_link_copied`, plus the history and multi-select strings: `history_title`,
`history_clear_label`, `history_removed`, `history_cleared`, `history_empty`,
`history_empty_hint`, `history_seen_format`, `history_clear_confirm_title`,
`history_clear_confirm_text`, `copy_links_label`, `share_links_label`, `export_links_label`,
`links_copied_format`, `links_clipboard_label`, `selected_count_format`, `select_done_label`,
`select_all_links_label`, `clear_selection_label`, `no_links_selected`, `export_filename`,
`export_failed`.

## 4. Verify

```bash
cd InstaDownload
./gradlew :app:assembleDebug --no-daemon 2>&1 | tail -20
```

Expect **BUILD SUCCESSFUL** with only the pre-existing `ScrollableTabRow` deprecation warning.

Confirm the new symbols and strings made it into the APK:

```bash
cd /tmp && rm -rf apkcheck && mkdir apkcheck && cd apkcheck
unzip -q <path>/instadownload-debug.apk "classes*.dex" "resources.arsc"
for s in FailedDownloads DownloadSounds playSuccess playFailure startDownload soundsEnabled \
         failed_downloads_empty_hint retry_all_label; do
  printf "%s: " "$s"; cat classes*.dex resources.arsc | strings | grep -c "$s"
done
```

Every count should be non-zero.

## 5. Runtime check (optional, needs an Android runtime)

Launch the debug build on an emulator or an Android-in-a-container runtime and confirm the activity
starts and renders without fatals. With `adb`:

```bash
adb install -r app/build/outputs/apk/debug/instadownload-debug.apk
adb shell am start -n com.vakarux.instadownload.debug/com.vakarux.instadownload.MainActivity
adb logcat -d | grep -E "FATAL|AndroidRuntime|ClassNotFound"
```

The component is `com.vakarux.instadownload.debug/com.vakarux.instadownload.MainActivity` — the
package is doubled in some container runtimes that prepend the package themselves, so if you see
`com.vakarux.instadownload.debug/com.vakarux.instadownload.debug/...` in the error, pass only the
app's default launcher (drop `--component`).

## 6. Common pitfalls

- **`Unresolved reference 'weight'`** — the containing composable is not a `ColumnScope`/`RowScope`
  extension. Add the receiver.
- **"@Composable invocations can only happen from the context of a @Composable function"** — a
  `stringResource`/composable call is inside a non-composable lambda (e.g. `onClick`/`onLongClick`).
  Hoist it.
- **`classes.dex` alone is not the app** — modern builds split into `classes2.dex`…`classesN.dex`.
  Grep across all of them.
- **SDK package name** — use `platforms;android-37.0`, not `android-37`.
- **No KVM/DRI containers** — the app runs but software-rendered; do not trust performance numbers
  from such an environment.

## 7. Publish

```bash
git add -A
git commit -m "Keep failed download links for retry and add success/fail sounds"
git push
```

Then attach the built APK to a GitHub release (see the repo's `docs/` for the exact feature text and
release notes template in `IDEA_AND_PROCESS.md`).
