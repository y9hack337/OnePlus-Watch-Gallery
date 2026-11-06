<div align="center">

# ⌚ OnePlus Watch Gallery

**Your favorite photos, on your wrist, in original quality.**
An Apple Watch–style Photos app for **OnePlus Watch 3** that syncs the ♥ Favorites album from the OnePlus Gallery.

![Wear OS](https://img.shields.io/badge/Wear%20OS-5-4285F4?logo=wearos&logoColor=white)
![Android](https://img.shields.io/badge/Android-11%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.1-7F52FF?logo=kotlin&logoColor=white)
![No root](https://img.shields.io/badge/root-not%20required-success)
![License: MIT](https://img.shields.io/badge/license-MIT-blue)
![No cloud](https://img.shields.io/badge/servers-none-success)

**English** · [Русский](README.ru.md)

</div>

---

### ✨ Features

- **Native sync, no servers.** Uses the same Wear OS Data Layer link that OHealth keeps with the watch. Photos travel only between your phone and your watch.
- **Favorites, automatically.** Tap ♥ on a photo in the OnePlus Gallery and it appears on the watch. Unlike it and it disappears. Sync works in the background, even when both apps are closed.
- **Original quality.** HEIC/JPEG files arrive byte for byte. Deep zoom loads tiles straight from the original, so you can see every pixel.
- **Smooth viewer:**
  - fluid crown zoom;
  - pinch, double-tap and inertial panning;
  - swipe between photos;
  - zoom out past the minimum with the crown to return to the grid, just like on Apple Watch.
- **Grid made for a round screen.** A lens effect shrinks thumbnails near the edges. The crown scrolls, and pinching changes the number of columns (2–4).
- **Fast transfer:**
  - previews arrive first over Bluetooth, so a photo shows up in about a second;
  - originals then load over local Wi-Fi when both devices share a network (~10 MB/s), or over 3 parallel Bluetooth channels otherwise.
- **One APK for both devices.** Install the same file on the phone and the watch, and it picks its role automatically.
- **Battery friendly.** No background services. The phone wakes only on a MediaStore change or a request from the watch, and exits in milliseconds if Favorites didn't change.
- **Hideable phone icon.** Open the phone settings from the watch by long-pressing the «Favorites» header.

### 📦 Install

1. Download `app-release.apk` from [Releases](https://github.com/y9hack337/OnePlus-Watch-Gallery/releases/latest) or build it yourself:
   ```bash
   ./gradlew :app:assembleRelease
   ```
2. Install the **same** APK on both devices:
   ```bash
   adb -s <phone> install -r app/build/outputs/apk/release/app-release.apk
   adb -s <watch> install -r app/build/outputs/apk/release/app-release.apk
   ```
3. On the phone, open **«Фото на часах»** once and set it up:
   - allow access to **all** photos;
   - allow background activity.
4. Open **Photos** on the watch. That's it.

> Both copies must come from the same build, because Wear Data Layer requires the same package name and signing key.

### ⚡ Transfer speed (5 photos, 3 MB of originals)

| Path | Time |
|---|---|
| Same Wi-Fi network | ~2 s |
| Bluetooth only (outdoors) | ~16 s |

Without Wi-Fi, the limit is Bluetooth Classic itself (~200 KB/s). OnePlus Watch 3 has no Wi-Fi Direct.

### 🛠 How it works

```
OnePlus Gallery ♥ ──► MediaStore.IS_FAVORITE
                          │ (JobScheduler content trigger)
                          ▼
   Phone ── DataItem /fav/photo/<id>: metadata + preview + thumb ──► Watch (Bluetooth, GMS)
     ▲                                                                │
     └──────── channel /fav/xfer: “send originals” ◄──────────────────┘
               ├─ same Wi-Fi → one-time TCP server with random token (LAN)
               └─ otherwise  → 3 parallel Wear channels over Bluetooth
```

| File | Role |
|---|---|
| `Favorites.kt` | Reads Favorites from MediaStore and builds the preview and thumbnail |
| `WatchSync.kt`, `SyncJob.kt` | Publish to the Data Layer; MediaStore trigger |
| `Transfer.kt` | Sends originals over LAN or Bluetooth |
| `PhotoRepo.kt`, `WatchSyncService.kt` | Watch-side storage and downloads |
| `GalleryActivity.kt`, `ZoomImageView.kt` | Grid and tiled zoom viewer |

### ⚠️ Notes

- Unofficial project, not affiliated with OnePlus or Google.
- Tested on a OnePlus 13 (OxygenOS 16) with a OnePlus Watch 3 (Wear OS 5) paired via OHealth.
- If the Data Layer has no Bluetooth link but both devices are online, Google Play services may relay data through Google's end-to-end encrypted cloud node.
- The watch launcher icon is the OnePlus Gallery icon, a trademark of OnePlus; it is not covered by the MIT license.

## License

[MIT](LICENSE) © 2026 y9hack337
