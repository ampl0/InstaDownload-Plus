# Idea and Process

How this fork came to be, why each change was made, and how the work was carried out.

## The idea

The upstream InstaDownload app already reverse-engineers Instagram's public endpoints well: paste a
link, get the media. But the gaps were all in the *experience around* the download:

1. **Sharing to the app cost an extra tap.** You share a reel, the app opens, you tap Download. The
   natural mobile flow is: share → done.
2. **Downloads died with the screen.** There was no visible progress and no guarantee the download
   would continue once you left the app.
3. **A failed download lost the link.** The error popped up, and the URL was gone. For a private or
   temporarily broken post, the user had to go back to Instagram to find it again.
4. **Silent completions.** Nothing told you a download finished or failed unless you were staring at
   the screen.
5. **History links carried tracking parameters.** Shared links include `?igsh=...`, which identifies
   the sharer; these leaked into saved history and re-shared links.
6. **Previews doubled the bandwidth.** Previewing a post fetched bytes that were then fetched again on
   save.
7. **Downloads were not reliably in the gallery** on modern Android without a media scan.

Each of these is small on its own, but together they turn a capable downloader into one you actually
want to use. The guiding principle was: **never lose the user's link, and never make them babysit a
download.**

## Design decisions

| Problem | Decision | Why |
| --- | --- | --- |
| Easy save | Auto-start the background service on share, on by default, with a toggle | Matches the native share→done flow; power users can disable it |
| Progress/background | Foreground service with a progress notification | Survives leaving the app; standard Android pattern for user-initiated long work |
| Failed links | Persist cleaned URLs in SharedPreferences JSON, keyed by URL | Tiny, no schema, dedupes automatically, survives process death |
| Retry UX | A "Failed (N)" tab in the existing library dialog | Reuses the library the user already knows; no new screen |
| Sounds | `ToneGenerator`, no bundled assets | Zero APK weight, honors notification volume, easy to gate with one setting |
| Clean links | Strip `?`/`#` at the point links are produced | Fixes every consumer (history, share, copy, failed list) with one helper |
| Preview bandwidth | In-memory byte cache keyed by URL | Removes the second fetch without touching the network layer |
| Gallery | Explicit `MediaScannerConnection` after write | Reliable visibility across Android versions |

## The process

1. **Reconnaissance.** Read the upstream codebase: `InstagramDownloader` (the reverse-engineered
   API layer), `MainActivity` (paste + save flow), `DownloadsDialog` (library), `AppSettings`
   (preferences), and the upstream `git log` to understand conventions.
2. **Gap analysis.** Map every requested behavior to the exact code path that would need to change,
   and choose an implementation consistent with the surrounding code.
3. **Implementation, in dependency order:**
   - Low-level stores first: `FailedDownloads`, `AppSettings.soundsEnabled`, `DownloadSounds`.
   - Wire them into the flows: `MainActivity` save + fetch, `QuickDownloadService` single + batch.
   - Surface them in the UI: the Failed tab in `DownloadsDialog`, the sounds toggle in Settings.
   - Add strings to `strings.xml` for everything user-facing.
4. **Build loop.** Compile with the Gradle wrapper. Fix type/scope errors as they appear — for
   example, Compose list composables that use `Modifier.weight` must be `ColumnScope`/`RowScope`
   extensions, and `stringResource` cannot be called inside a non-composable click lambda.
5. **Static verification.** Confirm the new classes, methods and strings are present in the built APK
   (`classes*.dex`, `resources.arsc`).
6. **Runtime verification.** Launch on an Android-in-a-container runtime, confirm the activity starts
   and renders with no fatals, and capture a frame.
7. **Commit and publish.** Commit locally, then push to this fork and attach the built APK to a
   release.

## What "done" looked like

- `./gradlew :app:assembleDebug` succeeds with only a pre-existing deprecation warning.
- New symbols (`FailedDownloads`, `DownloadSounds`, `playSuccess`, `playFailure`, `startDownload`,
  `soundsEnabled`) and new string resources exist in the APK.
- The app launches on Android and renders `MainActivity` without crashing.
- The feature set above is reachable from the UI (Settings toggle, Failed tab, share flow).

## Constraints hit along the way

- **Headless input injection** on the container runtime acknowledged taps but delivered no frames, and
  it could not forward intent extras, so the share/easy-save path could not be fully exercised
  end-to-end without a device. Static verification of the code and strings was used instead, plus a
  launch/render check.
- **No hardware acceleration** in the container (no KVM/DRI), so performance numbers from it are not
  representative of a real device.

See [`AGENT_REPRODUCTION_GUIDE.md`](AGENT_REPRODUCTION_GUIDE.md) to reproduce the work, and
[`ARCHITECTURE.md`](ARCHITECTURE.md) for where everything lives.
