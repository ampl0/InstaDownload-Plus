# Architecture

How the app is put together and where each feature lives.

## High level

InstaDownload is a single-module Android app written in Kotlin with a Jetpack Compose UI. It talks
directly to Instagram's public web endpoints (reverse-engineered), downloads media over HTTP, and
writes it to the user's storage. There is no backend server of its own and no account system.

```
Share / paste URL
        │
        ▼
QuickDownloadActivity ──(easy save on)──► QuickDownloadService (foreground service)
        │                                          │
        │ (easy save off)                          │ progress notification
        ▼                                          ▼
MainActivity ──► InstagramDownloader ──► media bytes ──► DownloadStorage ──► Downloads folder
                     (API layer)                              │
                                                              ▼
                                                        MediaScanner (gallery)
        │
        ├──► DownloadHistory      (history of saved posts)
        ├──► LinkHistory          (every pasted/shared link, incl. failures)
        ├──► FailedDownloads      (links that failed, for retry)
        └──► DownloadSounds       (success/failure tones)
```

## Modules / key files

| File | Responsibility |
| --- | --- |
| `MainActivity.kt` | Main Compose UI, paste field, save flow, settings dialog. Records failures and plays sounds. |
| `QuickDownloadActivity.kt` | Entry point for shared links. Routes to the background service (easy save) or to `MainActivity`. |
| `QuickDownloadService.kt` | Foreground service doing single and batch downloads, progress notifications, sounds, failed-link recording. Exposes `startDownload(context, url)`. |
| `InstagramDownloader.kt` | The reverse-engineered Instagram layer: resolves a URL to `PostMeta`/`MediaResult`, fetches profiles, and provides `cleanInstagramUrl`. |
| `DownloadStorage.kt` | Writes bytes to the chosen folder (SAF tree or Downloads) and announces them to the MediaScanner. |
| `DownloadHistory.kt` | Persists the history of downloaded posts (SharedPreferences JSON). |
| `LinkHistory.kt` | Persists every pasted/shared link (incl. failures), keyed by cleaned URL; never auto-removed. |
| `FailedDownloads.kt` | Persists failed URLs with error, timestamp and attempt count; never auto-removed. |
| `DownloadSounds.kt` | Plays distinct success/failure tones via `ToneGenerator`. |
| `AppSettings.kt` | All preferences: theme, quality, haptics, sounds, easy save, folders, open-after-download. |
| `ProfileBrowser.kt` | UI for browsing a profile and bulk-downloading its posts. |
| `DownloadsDialog.kt` | In-app library dialog: per-kind tabs, the Links history tab and the Failed tab, plus multi-select copy/share/export. |

## Data flow for a download

1. A URL arrives (pasted in `MainActivity`, or shared into `QuickDownloadActivity`).
2. `InstagramDownloader` resolves it to media URLs (and metadata like author/caption).
3. `DownloadStorage` streams the bytes to the destination folder and triggers a media scan.
4. The result is recorded:
   - success → `DownloadHistory.record(...)`, success tone. Failures are **not** cleared.
   - failure → `FailedDownloads.add(...)`, failure tone, error surfaced in the UI.

Every incoming link is also recorded in `LinkHistory` when it is pasted (`MainActivity`) or shared
(`QuickDownloadService`), so a link is never lost even if the download fails.

## The history stores

All three stores share the same shape — `SharedPreferences` holding a JSON array, keyed by the
**cleaned URL** so the same post never appears twice.

`FailedDownloads` (`SharedPreferences("failed_downloads")`, key `"records"`):

- **Record:** `{ url, error, failedAt, attempts }`.
- **Semantics:** `add` bumps `attempts`/refreshes `error`; capped at 200. **Never auto-removed** — a
  successful download or retry does not delete the entry; only **Remove** / **Clear failed** do.

`LinkHistory` (`SharedPreferences("link_history")`, key `"records"`):

- **Record:** `{ url, firstSeen, lastSeen, count }`.
- **Semantics:** `add` dedupes by cleaned URL, refreshes `lastSeen`/`count` and moves the entry to
  the top; capped at 500. **Never auto-removed** — only **Remove** / **Clear history** delete entries.

The library UI's batch actions (copy/share/export) are pure UI helpers in `DownloadsDialog.kt`:
copy uses `ClipData.newPlainText`, share uses `ACTION_SEND` with newline-joined text, and export
writes `links.txt` under `cacheDir/exports/` and shares it read-only via the app's `FileProvider`.

Because retry reuses `QuickDownloadService.startDownload`, the retry path and the first-attempt path
share all of the download, notification, sound and history logic.

## The sound helper

`DownloadSounds` creates a `ToneGenerator` on a background coroutine, plays a tone, waits briefly,
and releases it. It never blocks the caller and bundles no audio assets. It is gated by
`AppSettings.soundsEnabled` at every call site.

## Settings keys

| Setting | Pref key | Default |
| --- | --- | --- |
| Haptics | `haptics` | `true` |
| Sounds | `sounds` | `true` |
| Easy save | `easy_save` | `true` |
| Open library after download | `open_library_after_download` | `true` |
| Theme | `theme` | `SYSTEM` |
| Quality | `quality` | `AUTO` |
| Download folder | `download_folder_name` | `Downloads/InstaDownload (default)` |
