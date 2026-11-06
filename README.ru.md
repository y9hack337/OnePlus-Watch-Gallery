<div align="center">

# ⌚ OnePlus Watch Gallery

**Любимые фото на запястье — в оригинальном качестве.**
Приложение «Фото» в стиле Apple Watch для **OnePlus Watch 3**: синхронизирует альбом ♥ «Избранное» из Галереи OnePlus.

![Wear OS](https://img.shields.io/badge/Wear%20OS-5-4285F4?logo=wearos&logoColor=white)
![Android](https://img.shields.io/badge/Android-11%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.1-7F52FF?logo=kotlin&logoColor=white)
![No root](https://img.shields.io/badge/root-%D0%BD%D0%B5%20%D0%BD%D1%83%D0%B6%D0%B5%D0%BD-success)
![License: MIT](https://img.shields.io/badge/license-MIT-blue)
![No cloud](https://img.shields.io/badge/%D1%81%D0%B5%D1%80%D0%B2%D0%B5%D1%80%D0%BE%D0%B2-%D0%BD%D0%B5%D1%82-success)

[English](README.md) · **Русский**

</div>

---

### ✨ Возможности

- **Нативная синхронизация без серверов.** Работает через тот же канал Wear OS Data Layer, которым OHealth связан с часами. Фото передаются только между телефоном и часами.
- **Избранное — само.** Поставил ♥ на фото в Галерее OnePlus — оно появилось на часах. Снял — исчезло. Синхронизация идёт в фоне, даже когда оба приложения закрыты.
- **Оригинальное качество.** HEIC/JPEG передаются байт в байт. При глубоком зуме тайлы подгружаются прямо из оригинала, так что видно каждый пиксель.
- **Плавный просмотр:**
  - плавный зум колёсиком;
  - щипок, двойной тап и инерционная прокрутка;
  - свайп между фото;
  - если отдалить колёсиком дальше минимума, вернёшься в сетку, как на Apple Watch.
- **Сетка под круглый экран.** Эффект «линзы» уменьшает миниатюры у краёв. Колёсико прокручивает сетку, щипок меняет число колонок (2–4).
- **Быстрая передача:**
  - сначала по Bluetooth приходят превью, так что фото видно примерно через секунду;
  - затем оригиналы грузятся по Wi‑Fi напрямую, если устройства в одной сети (~10 МБ/с), а иначе — по 3 параллельным Bluetooth-каналам.
- **Один APK на оба устройства.** Ставишь один и тот же файл на телефон и на часы, роль определяется автоматически.
- **Бережёт батарею.** Фоновых сервисов нет. Телефон просыпается только при изменении медиатеки или по запросу часов и сразу выходит, если «Избранное» не менялось.
- **Значок на телефоне можно скрыть.** Настройки телефона открываются с часов долгим нажатием на «Избранное».

### 📦 Установка

1. Скачай `app-release.apk` из [Releases](https://github.com/y9hack337/OnePlus-Watch-Gallery/releases/latest) или собери сам:
   ```bash
   ./gradlew :app:assembleRelease
   ```
2. Поставь **один и тот же** APK на оба устройства:
   ```bash
   adb -s <телефон> install -r app/build/outputs/apk/release/app-release.apk
   adb -s <часы>    install -r app/build/outputs/apk/release/app-release.apk
   ```
3. На телефоне один раз открой **«Фото на часах»** и настрой:
   - разреши доступ ко **всем** фото;
   - разреши работу в фоне.
4. Открой **«Фото»** на часах. Готово.

> Обе копии должны быть из одной сборки: Wear Data Layer требует одинакового имени пакета и ключа подписи.

### ⚡ Скорость (5 фото, 3 МБ оригиналов)

| Путь | Время |
|---|---|
| Одна Wi‑Fi сеть | ~2 с |
| Только Bluetooth (на улице) | ~16 с |

Без Wi‑Fi предел задаёт сам Bluetooth Classic (~200 КБ/с). Wi‑Fi Direct у OnePlus Watch 3 нет.

### 🛠 Как устроено

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

| Файл | Назначение |
|---|---|
| `Favorites.kt` | Читает «Избранное» из MediaStore, делает превью и миниатюру |
| `WatchSync.kt`, `SyncJob.kt` | Публикация в Data Layer; триггер на изменения MediaStore |
| `Transfer.kt` | Передача оригиналов по локальной сети или Bluetooth |
| `PhotoRepo.kt`, `WatchSyncService.kt` | Хранилище и загрузки на часах |
| `GalleryActivity.kt`, `ZoomImageView.kt` | Сетка и просмотрщик с тайловым зумом |

### ⚠️ Примечания

- Неофициальный проект, не связан с OnePlus или Google.
- Проверено на OnePlus 13 (OxygenOS 16) и OnePlus Watch 3 (Wear OS 5) в паре через OHealth.
- Если Bluetooth-связи нет, но оба устройства в интернете, Google Play services могут передать данные через облачный узел Google со сквозным шифрованием.
- Иконка на часах — иконка Галереи OnePlus, товарный знак OnePlus; на неё лицензия MIT не распространяется.

## Лицензия

[MIT](LICENSE) © 2026 y9hack337
