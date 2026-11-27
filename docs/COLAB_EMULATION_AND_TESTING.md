# Fast Emulation, Screenshots & Testing (for AI agents)

This guide shows how to **build, run and screenshot InstaDownload-Plus inside a headless Colab-style
container** with no hardware GPU, no KVM and no Android emulator — using [PenguDroid](https://github.com/pengudroid)
(an Android-in-a-container runtime, version `0.3.0`). It also lists the exact tests that pass so another
agent can reproduce the same evidence.

Everything here was executed successfully on Ubuntu, software-rendered (Mesa `llvmpipe`), as a
non-root user. Total wall time for a clean run is roughly **5–8 minutes** (most of it Gradle).

---

## 1. Why this runtime

| Option | Works headless? | Needs KVM? | Notes |
| --- | --- | --- | --- |
| Android Studio emulator | ✗ | ✔ (x86 images) | needs `/dev/kvm`, huge download |
| `avdmanager` + `-gpu swiftshader` | partial | ✔ | very slow without KVM |
| **PenguDroid `launch`** | **✔** | **✗** | transliterates ARM64 guest → host; software GL |
| `pengudroid run` (compositor) | ✗ for capture | ✗ | real Weston window but strips `LD_PRELOAD`, so no frame dump |

> **Rule of thumb:** use **`launch`** (headless apphost/ART harness) for **both rendering and input**.
> Do **not** use `run` — it loses the GL shim and the input pump.

---

## 2. One-shot setup (Colab / container)

```bash
set -e
# 2.1 A non-root user is REQUIRED. PenguDroid refuses to install its guest identity as root.
id pengu >/dev/null 2>&1 || useradd -m -s /bin/bash pengu

# 2.2 Android SDK + JDK (adjust paths to your image)
export ANDROID_HOME=/opt/android-sdk
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64

# 2.3 Build the debug APK
cd InstaDownload                # nested Gradle project
./gradlew :app:assembleDebug --no-daemon
APK=$PWD/app/build/outputs/apk/debug/instadownload-debug.apk

# 2.4 The APK must be READ-ONLY, otherwise ART throws
#     "SecurityException: Writable dex file ... is not allowed".
install -D -m 0444 "$APK" /home/pengu/apps/app.apk
chown -R pengu:pengu /home/pengu/apps
```

### 2.5 Install PenguDroid

Download the AppImage once, extract it, and read the CLI path:

```bash
mkdir -p /opt/pengudroid && cd /opt/pengudroid
curl -L -o pengudroid.AppImage \
  https://github.com/pengudroid/pengudroid/releases/download/v0.3.0/PenguDroid-0.3.0-x86_64.AppImage
chmod +x pengudroid.AppImage
./pengudroid.AppImage --appimage-extract >/dev/null
PD=/opt/pengudroid/squashfs-root/usr/bin/pengudroid     # CLI entry point
"$PD" --version        # -> pengudroid 0.3.0
"$PD" doctor /home/pengu/apps/app.apk   # WebView is the only thing it may report missing (we don't use it)
```

---

## 3. Canonical environment file

Save this as `/tmp/pengurun/env.sh` and `source` it before every run. It sets up a private X server
and a Weston compositor (needed even though capture uses the frame dump).

```bash
# /tmp/pengurun/env.sh
export PD=/opt/pengudroid/squashfs-root/usr/bin/pengudroid
export GLESHIM=/opt/pengudroid/squashfs-root/usr/bin/libpengudroid_gleshim.so

export XDG_RUNTIME_DIR=/tmp/pengurun/wl
export WAYLAND_DISPLAY=penguwl
export DISPLAY=:99
export PENGUDROID_WEBVIEW=0
export PENGUDROID_AUTO_RUNTIME=1
```

Bring up Xvfb **as `pengu`** and the runtime dir with the right permissions:

```bash
mkdir -p /tmp/pengurun/wl
chown pengu:pengu /tmp/pengurun/wl
chmod 0700 /tmp/pengurun/wl
rm -f /tmp/.X99-lock /tmp/.X11-unix/X99
setsid su pengu -c 'Xvfb :99 -screen 0 1280x720x24' >/tmp/xvfb.log 2>&1 < /dev/null &
```

> Skip Weston entirely if you only use `launch` + the frame dump. Xvfb is enough for headless GL.

---

## 4. The capture recipe (render + screenshot)

Frames are captured with the **GL shim's dump-frame**, not with `import`/X capture.

```bash
# /tmp/pengurun/run_shot.sh   — $1 = synthetic click "x,y" (optional), $2 = probe delay ms
#!/bin/bash
source /tmp/pengurun/env.sh
unset PENGUDROID_GL_FORWARDERS PENGUDROID_PORTABLE_LIBS

export PENGUDROID_GLESHIM="$GLESHIM"
export LD_PRELOAD="$GLESHIM"

export PENGUDROID_DUMP_FRAME=1
export PENGUDROID_DUMP_FRAME_AT=1
export PENGUDROID_DUMP_FRAME_EVERY=1

# Landscape so all six library tabs (All|Reels|Posts|Stories|Failed|Links) fit in one row.
export PENGUDROID_SURFACE_W=1280
export PENGUDROID_SURFACE_H=720

export PENGUDROID_TRACE_INPUT=1
export PENGUDROID_SYNTH_CLICK="${1:-}"
export PENGUDROID_PROBE_DELAY="${2:-10000}"

mkdir -p /tmp/pengurun/frames/shot && cd /tmp/pengurun/frames/shot
exec "$PD" launch /home/pengu/apps/app.apk
```

```bash
chmod +x /tmp/pengurun/run_shot.sh && chown pengu:pengu /tmp/pengurun/run_shot.sh
mkdir -p /tmp/pengurun/frames/shot && chown pengu:pengu /tmp/pengurun/frames/shot

# Run detached (a command whose argv contains "pengudroid"/"pengu" can be SIGKILLed by pgrep self-match).
setsid su pengu -c '/tmp/pengurun/run_shot.sh "" 10000' > /tmp/pengurun/shot.log 2>&1 < /dev/null &
sleep 25

# Convert the dumped PPM frames to PNG  (use `convert`; `magick` is often absent)
cd /tmp/pengurun/frames/shot
for f in *.ppm; do convert "$f" "${f%.ppm}.png"; done
ls *.png | tail
```

The dump writes files named `*.s<surface>frame<n>.ppm` into the **current working directory** of the
launch process (the `cd` above).

---

## 5. Driving the UI

`PENGUDROID_SYNTH_CLICK` injects **one** pointer click per launch (down + up) into the app thread. Its
format is `"x,y"` in **raw device pixels**. There is no drag/swipe primitive and no multi-point form
(extra values are ignored). Coordinates used here (landscape `1280×720`):

| Target | `x,y` |
| --- | --- |
| Downloads chip (main screen) | `1119,38` |
| Download button (preview) | `640,442` |
| Library tab row (`y≈116`) | All `93`, Reels `184`, Posts `272`, Stories `357`, Failed `454`, Links `547` |

**Reaching a non-default library tab in one click:** the library remembers the last tab you opened
(`AppSettings.lastLibraryTab`). Pre-seed it, then a single click on the Downloads chip opens straight
to that tab:

```bash
PREFS=/home/pengu/.local/share/pengudroid/data/com.vakarux.instadownload.debug/shared_prefs
cat > "$PREFS/app_settings.xml" <<'XML'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="last_library_tab">FAILED</string>
</map>
XML
```

Then run `run_shot.sh "1119,38" 10000` and the library opens on **Failed**.

### Prefilling a URL via an intent

`PENGUDROID_INTENT_DATA` performs `ACTION_VIEW` with a data URI only (no boolean/int extras, so
`EXTRA_OPEN_LIBRARY` is unreachable — click through the UI instead):

```bash
PENGUDROID_INTENT_DATA="https://www.instagram.com/reel/DdN9BJIIFOt/" \
  su pengu -c '/tmp/pengurun/run_shot.sh "1119,38" 12000'
```

---

## 6. Tests that pass

These are the checks that were run and passed. Reproduce them as stated.

### 6.1 Build

```bash
cd InstaDownload && ./gradlew :app:assembleDebug --no-daemon
# expect: BUILD SUCCESSFUL  (only the pre-existing ScrollableTabRow deprecation warning)
sha256sum app/build/outputs/apk/debug/instadownload-debug.apk
# 4e8be09bf7a2ed81018999f8e168bf1baff1fedb42b6861c7372b6422fd3ce16  (fork.3 build)
```

### 6.2 Symbol presence in the APK

```bash
cd /tmp && rm -rf apkcheck && mkdir apkcheck && cd apkcheck
unzip -q <path>/instadownload-debug.apk "classes*.dex" "resources.arsc"
for s in LinkHistory FailedDownloads lastLibraryTab copy_links_label export_links_label \
         failed_downloads_empty_hint; do
  printf '%-28s %s\n' "$s" "$(cat classes*.dex resources.arsc | strings | grep -c "$s")"
done
# every count must be non-zero (LinkHistory/lastLibraryTab may appear in classes2.dex etc.)
```

### 6.3 App starts and renders (runtime)

Launch once with no click, wait ~15 s, and confirm the Activity rendered without a fatal:

```bash
su pengu -c '/tmp/pengurun/run_shot.sh "" 8000' > /tmp/pengurun/smoke.log 2>&1
grep -c "FATAL EXCEPTION\|AndroidRuntime" /tmp/pengurun/smoke.log   # -> 0
ls /tmp/pengurun/frames/shot/*.ppm | wc -l                          # -> > 0 (frames rendered)
```

### 6.4 Real end-to-end fetch against Instagram

```bash
PENGUDROID_INTENT_DATA="https://www.instagram.com/reel/DdN9BJIIFOt/" \
  setsid su pengu -c '/tmp/pengurun/run_shot.sh "1119,38" 15000' > /tmp/pengurun/e2e.log 2>&1 &
sleep 30
grep -E "okhttp|instagram.com|video_versions|thumbnail" /tmp/pengurun/e2e.log | head
```

Result observed: a real HTTP/2 connection to `www.instagram.com`, the reel resolved, and the real
thumbnail rendered in the preview.

### 6.5 Persistent history is written (the new feature)

After a download attempt, inspect the on-device state:

```bash
D=/home/pengu/.local/share/pengudroid/data/com.vakarux.instadownload.debug
cat "$D/shared_prefs/link_history.xml"
# -> records: [{ "url":"https://www.instagram.com/reel/DdN9BJIIFOt/",
#               "firstSeen":..., "lastSeen":..., "count":2 }]
cat "$D/shared_prefs/failed_downloads.xml"
# -> records: [{ "url":"https://www.instagram.com/reel/DdN9BJIIFOt/",
#               "error":"Could not create file in Downloads", "attempts":1 }]
```

The same reel URL is present in **both** the Links history and the Failed list and is **not**
auto-removed — this is the behaviour the fork adds.

> **Known harness limitation — media save.** In this container `ContentResolver.insert()` into
> `MediaStore.VOLUME_EXTERNAL_PRIMARY` returns `null`, so the app reports
> `"Could not create file in Downloads"`. Fetch and history recording work; the final byte-write to
> the shared gallery cannot complete **in this harness only**. On a real device the same build saves
> normally. Do not treat this as an app bug.

> **Known harness limitation — Compose dialog surface.** The library UI is a Compose `Dialog`, which
> renders into its **own window** (`root 1`), not the activity window (`root 0`). The dump-frame shim
> only captures **`1.s0`** (root 0's surface), so a synthetic click *does* open the dialog — the log
> proves it (`views: 2 window root(s)`, `apphost: root 1 ... mAdded=true`) — but the dialog's pixels
> are **not** written to a `.ppm`. Verify dialog-side behaviour from the logs and the on-device
> `shared_prefs/*.xml` state instead of from a screenshot. A single-activity (non-Dialog) build of the
> library would render into `root 0` and be capturable.

---

## 7. Taking the screenshots into the repo

Two kinds of frame are reliably capturable in this harness: the **main activity** and the **preview
after a real fetch** (both render into `root 0` → `1.s0`). The library dialog does **not** (see the
Compose-dialog limitation in §6.5).

```bash
cd /content/InstaDownload
mkdir -p img
cp /tmp/pengurun/frames/shot/<idle-frame>.png   img/emulated-running-v3.1.2.png   # main screen
cp /tmp/pengurun/frames/shot/<preview-frame>.png img/emulated-real-fetch.png      # live reel preview
git add img docs README.md
git commit -m "Add emulation & testing guide with PenguDroid screenshots"
git push
```

The shipped examples (`img/emulated-running-v3.1.2.png`, `img/emulated-real-fetch.png`) were captured
this way: the second shows a real `https://www.instagram.com/reel/DdN9BJIIFOt` resolved live and its
thumbnail rendered inside the emulated app.

---

## 8. Common pitfalls (agent checklist)

- **Root fails.** PenguDroid aborts with `refusing guest identity installation`. Always run as `pengu`.
- **`XDG_RUNTIME_DIR`** must be `0700` and owned by `pengu`, or the runtime refuses.
- **Stale X lock.** Remove `/tmp/.X99-lock` and `/tmp/.X11-unix/X99` before starting Xvfb.
- **Writable APK.** ART throws `SecurityException: Writable dex file ... is not allowed`. `chmod 0444`.
- **`run` vs `launch`.** `run` gives a real window but **no** frame dump and **no** input pump;
  `pengudroid tap` then reports "input injected but no surface is known". Use `launch`.
- **pgrep self-kill.** A cleanup command containing `pengudroid`/`pengu`/`weston`/`Xvfb` in its argv
  can `pkill -f` itself. Build the pattern from split literals and skip your own PID, or use `setsid`.
- **One click per launch.** There is a single-shot `PENGUDROID_SYNTH_CLICK`. To reach a library tab,
  pre-seed `last_library_tab` (see §5) instead of sequencing clicks.
- **No WebView.** `doctor` may report WebView missing; the app does not need it (`PENGUDROID_WEBVIEW=0`).
- **Software GL only.** No `/dev/dri` in Colab — rendering is Mesa `llvmpipe`. Ignore any performance
  numbers measured this way.
