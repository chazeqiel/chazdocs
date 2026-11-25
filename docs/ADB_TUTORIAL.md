# ChazDocs ADB Installation, Permissions & File Transfer Tutorial

This guide walks through installing **ChazDocs** (`com.chazdocs.wear`) on a Wear OS smartwatch (such as the **OnePlus Watch 4**), granting full file-system access via ADB, and pushing documents to the watch's `Documents` folder.

---

## 1. Prerequisites

1. **Install ADB (Android Debug Bridge)** on your computer:
   - Download **Android SDK Platform-Tools** from Google (`adb`) and ensure `adb` is in your `PATH` (or run `./adb` from the `platform-tools` folder).
2. **Enable Developer Options on your Watch**:
   - Open **Settings → System → About → Versions** (or **About watch**).
   - Tap **Build number** 7 times until you see *"You are now a developer!"*.
3. **Connect Watch & PC to the same Wi-Fi network**.

---

## 2. Pair & Connect to Your Watch Over Wireless ADB

Wear OS wireless debugging uses **two different ports** on the same IP address:
- A **Pairing port** (used once per computer with a 6-digit code)
- A **Connection port** (shown on the main Wireless debugging screen)

### Step A: Enable Wireless Debugging
1. On the watch, go to **Settings → Developer options → Wireless debugging** and turn it **On**.
2. Note the **IP address & Port** displayed on that main screen (for example, `192.168.0.9:36413`). This is your **connection port**.

### Step B: Pair Your PC (First Time Only)
1. Tap **Pair new device** on the watch.
2. Keep the watch screen awake while it displays a **pairing IP:port** (for example, `192.168.0.9:37977`) and a **6-digit pairing code** (for example, `206532`).
3. On your computer, run:
   ```powershell
   adb pair 192.168.0.9:37977 206532
   ```
   You should see:
   ```text
   Successfully paired to 192.168.0.9:37977 [guid=...]
   ```

### Step C: Connect ADB to the Watch
Use the **connection port** from Step A (not the pairing port):
```powershell
adb connect 192.168.0.9:36413
adb devices -l
```
You should see `192.168.0.9:36413    device`.

---

## 3. Install ChazDocs

Install the release APK (`app/build/outputs/apk/release/app-release.apk`):

```powershell
adb -s 192.168.0.9:36413 install -r -g "app\build\outputs\apk\release\app-release.apk"
```

- `-r` reinstalls/updates while keeping your saved reading positions and settings.
- `-g` automatically grants standard runtime permissions declared in the manifest.

You should see `Performing Streamed Install` followed by `Success`.

---

## 4. Grant File-System Access (`MANAGE_EXTERNAL_STORAGE`) via ADB

On Android 11+ / Wear OS, `MediaStore` alone does not always index arbitrary `.epub`, `.docx`, `.pptx`, `.md`, or newly `adb push`-ed `.pdf` files immediately. Granting `MANAGE_EXTERNAL_STORAGE` lets ChazDocs scan `/sdcard/Documents` directly and instantly.

Many Wear OS watches hide the *"All files access"* settings toggle in their UI, so granting it via `appops` over ADB is the fastest and most reliable method:

```powershell
adb -s 192.168.0.9:36413 shell appops set com.chazdocs.wear MANAGE_EXTERNAL_STORAGE allow
```

Verify that the permission took effect:
```powershell
adb -s 192.168.0.9:36413 shell appops get com.chazdocs.wear MANAGE_EXTERNAL_STORAGE
```
Expected output:
```text
MANAGE_EXTERNAL_STORAGE: allow
```

*(On Android 10 or lower, if `READ_EXTERNAL_STORAGE` wasn't already granted by `-g`, run: `adb -s <IP>:<PORT> shell pm grant com.chazdocs.wear android.permission.READ_EXTERNAL_STORAGE`.)*

Launch ChazDocs from ADB (or from the watch app launcher):
```powershell
adb -s 192.168.0.9:36413 shell am start -n com.chazdocs.wear/.MainActivity
```

---

## 5. Push Documents to Your Watch

ChazDocs scans `/sdcard/Documents` (recursively up to 6 subfolder levels deep) for `.pdf`, `.epub`, `.docx`, `.pptx`, `.md`, `.markdown`, `.txt`, and `.log` files.

### Push a single file
```powershell
adb -s 192.168.0.9:36413 push "D:\path\to\lecture_notes.pdf" /sdcard/Documents/
```

### Push into a subfolder (shows as `Subfolder • Size` in ChazDocs)
```powershell
adb -s 192.168.0.9:36413 shell mkdir -p /sdcard/Documents/Exams
adb -s 192.168.0.9:36413 push "D:\path\to\OIT questions FULL.pdf" /sdcard/Documents/Exams/
```

### Check what is currently on the watch
```powershell
adb -s 192.168.0.9:36413 shell ls -la /sdcard/Documents
```

After pushing files, tap the **Refresh** button at the top of the ChazDocs Home screen (or reopen the app) to see them immediately.

---

## 6. Troubleshooting

- **`error: protocol fault` or connection refused during `adb pair`**:
  The watch's screen went to sleep or you left the *Pair new device* popup, which immediately closes that pairing port and rotates the 6-digit code. Tap **Pair new device** again, keep the watch screen awake, and run `adb pair` with the new port and code.
- **`adb.exe: device offline` or `10060` timeout on `adb connect` / `adb push`**:
  Wear OS puts Wi-Fi and Wireless debugging to sleep when the watch dims, and may assign a new connection port upon waking. Wake the watch, open **Settings → Developer options → Wireless debugging**, check the current **IP address & Port**, then run:
  ```powershell
  adb disconnect
  adb connect <WATCH_IP>:<CURRENT_CONNECT_PORT>
  ```
- **Documents pushed via ADB don't show up**:
  1. Confirm the file is inside `/sdcard/Documents/` (not `/sdcard/Download/`).
  2. Confirm `MANAGE_EXTERNAL_STORAGE` is `allow`:
     `adb shell appops get com.chazdocs.wear MANAGE_EXTERNAL_STORAGE`
  3. Tap **Refresh** at the top of the ChazDocs Home screen.
