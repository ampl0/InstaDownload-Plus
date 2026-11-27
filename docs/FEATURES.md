# Features

This document lists the app's features and highlights what this fork added on top of upstream
InstaDownload `3.1.2` (`e21a36a`).

## Core features (upstream + this fork)

- **Link downloads** — paste an Instagram post/reel/video URL and save the media.
- **Carousel support** — download every photo/video in a carousel post.
- **Profile bulk download** — fetch a profile's posts and download them in bulk.
- **No login / no ads / no tracking** for public content.
- **No third-party download server** — media is fetched directly from Instagram.
- **In-app library** — browse previously downloaded posts.
- **Themes** — system / light / dark.
- **Quality selection** — Auto, Data saver, Best, Custom width.
- **Haptics** — optional feedback.
- **F-Droid / Play Store distribution** (upstream).

## New in this fork

### 1. Easy save mode (default on)

Sharing a link to the app no longer needs a confirmation tap. When easy save is on, the shared URL
is handed straight to the background download service, which downloads it and reports progress in a
notification. A Settings toggle turns it off, in which case the app opens with the URL pre-filled
instead (it is passed to `MainActivity` via `EXTRA_SHARED_URL`).

- Entry point: `QuickDownloadActivity`
- Background work: `QuickDownloadService`
- Setting: `AppSettings.easySaveEnabled` (`prefs` key `easy_save`, default `true`)

### 2. Background downloads with progress

Downloads run in a foreground service, so they survive leaving the app, and a progress notification
shows percentage/state. The screen can be kept on while a download runs.

### 3. Failed download retry list

Any download that fails is saved so the link is not lost:

- Persisted as JSON in SharedPreferences by `FailedDownloads` (keyed by the cleaned URL).
- Each record stores the **tracking-stripped URL**, the error message, a timestamp and an attempt
  count. Re-failing bumps the counter and refreshes the error.
- Entries are **never removed automatically**. A successful download — including a successful
  **retry** — does not clear the failure. The link stays in the list until the user explicitly
  removes it or taps **Clear failed**, so links the user still cares about cannot silently vanish.
- Failures are recorded from every path:
  - `MainActivity` — media fetch and save flow.
  - `QuickDownloadService` — single download, and per-post failures in the batch path.
- The library UI gets a **Failed (N)** tab (`DownloadsDialog.FailedDownloadsList`) with
  **Retry**, **Retry all**, **Remove** and **Clear failed**, plus long-press-to-copy and
  multi-select of the clean links. Retry re-enters the background pipeline via
  `QuickDownloadService.startDownload(context, url)`.

### 4. Persistent link history

Every Instagram link pasted or shared into the app is remembered, including links whose download
later failed — so a link is never lost even if you close the app.

- Persisted as JSON in SharedPreferences by `LinkHistory` (key `link_history`), keyed by the
  **cleaned URL** and capped at 500 entries.
- Each record stores `firstSeen`, `lastSeen` and how many times the link was seen. Re-seeing the same
  cleaned link refreshes `lastSeen`/`count` and moves it to the top instead of duplicating it.
- Like the failed list, history is **never trimmed automatically**; only an explicit **Remove** or
  **Clear history** (with a confirmation dialog) deletes an entry.
- Wired at both entry points: `MainActivity` (paste/fetch) and `QuickDownloadService` (shared link).
- Shown in the library as a **Links** tab (`DownloadsDialog.LinkHistoryList`) with per-row
  **Download** and **Remove** actions and long-press multi-select.

### 5. Multi-select copy / share / export

Any list in the library — the Downloads library, the Failed tab and the Links tab — supports
multi-select:

- **Long-press** a row to enter selection mode, then tap rows to check/uncheck them.
- **Select all / Clear selection** buttons and a live selected-count header.
- **Copy** — puts every selected clean link on the clipboard as newline-separated text.
- **Share** — opens the share sheet with the selected links as text.
- **Export** — writes the selected links to a `links.txt` file (in `cacheDir/exports/`, shared
  read-only through the app's `FileProvider`) and opens the share sheet so the user can save it to
  Downloads, Drive, email or a chat.

### 6. Success and failure sounds (default on)

`DownloadSounds` plays a short **rising tone** (`TONE_PROP_ACK`) on success and a distinct **low
buzz** (`TONE_SUP_ERROR`) on failure. It uses `ToneGenerator`, so no audio assets are bundled and
the user's notification volume is respected. Tones play off the main thread and the generator is
released right after. Gated by `AppSettings.soundsEnabled` (`prefs` key `sounds`, default `true`)
with a Settings toggle that previews the tone when enabled.

### 7. Clean, tracker-stripped links

`InstagramDownloader.cleanInstagramUrl(url)` removes the query string and fragment (for example the
`?igsh=...` sharer identifier) from links used in history, copy/share actions and the failed list.
So saved and shared links stay short and do not leak who shared them.

### 8. Gallery visibility fixes

Downloads are written through `DownloadStorage` and announced with `MediaScannerConnection`, so
they show up in the system gallery as well as the in-app library.

### 9. No doubled bandwidth from previews

Previewing a post no longer re-downloads bytes. Preview bytes are cached in memory keyed by URL
(`copyCachedPreviewTo`), and a single photo reuses the cached preview bytes when saving. Preview
size labels are derived from the fetched bytes instead of a separate `HEAD` request.
