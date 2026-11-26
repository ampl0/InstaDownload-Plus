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
| `FailedDownloads.kt` | Persists failed URLs with error, timestamp and attempt count. |
| `DownloadSounds.kt` | Plays distinct success/failure tones via `ToneGenerator`. |
| `AppSettings.kt` | All preferences: theme, quality, haptics, sounds, easy save, folders, open-after-download. |
| `ProfileBrowser.kt` | UI for browsing a profile and bulk-downloading its posts. |
| `DownloadsDialog.kt` | In-app library dialog: history, downloads and the new Failed tab. |

## Data flow for a download

1. A URL arrives (pasted in `MainActivity`, or shared into `QuickDownloadActivity`).
2. `InstagramDownloader` resolves it to media URLs (and metadata like author/caption).
3. `DownloadStorage` streams the bytes to the destination folder and triggers a media scan.
4. The result is recorded:
   - success → `DownloadHistory.record(...)`, `FailedDownloads.remove(...)`, success tone.
   - failure → `FailedDownloads.add(...)`, failure tone, error surfaced in the UI.

## The failed-link store

`FailedDownloads` is a tiny key/value store:

- **Backing store:** `SharedPreferences("failed_downloads")`, key `"records"`, value = JSON array.
- **Record:** `{ url, error, failedAt, attempts }`.
- **Key:** the cleaned URL (`cleanInstagramUrl`), so the same post never appears twice.
- **Semantics:** `add` bumps `attempts`/refreshes `error`; success calls `remove`; capped at 200.

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
