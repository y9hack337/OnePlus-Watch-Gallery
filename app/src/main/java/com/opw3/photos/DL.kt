package com.opw3.photos

/** Протокол Wear Data Layer (общий для телефона и часов). */
object DL {
    const val PHOTO_DIR = "/fav/photo"
    const val PHOTO_PREFIX = "/fav/photo/"
    const val MANIFEST = "/fav/manifest"
    const val WATCH_STATE = "/fav/watch/state"
    const val MSG_SYNC = "/fav/sync"
    const val LAN_PROBE = "/fav/lan"   // запрос: телефон сейчас в Wi-Fi?
    const val XFER = "/fav/xfer"   // канал для оригиналов (Wi-Fi напрямую или Bluetooth)

    const val CAP_PHONE = "opw3_photos_phone"
    const val CAP_WATCH = "opw3_photos_watch"

    /** Версия формата: при изменении телефон переотправит все фото. */
    const val FORMAT = 2

    const val K_ID = "id"
    const val K_NAME = "name"
    const val K_MIME = "mime"
    const val K_W = "w"           // ширина оригинала в пикселях файла (до поворота)
    const val K_H = "h"
    const val K_ROT = "rot"       // 0/90/180/270 — поворот по часовой для отображения
    const val K_TAKEN = "taken"
    const val K_MOD = "mod"
    const val K_SIZE = "size"
    const val K_FORMAT = "fmt"
    // оригинал передаётся отдельно через канал XFER (см. Transfer)
    const val K_PREVIEW = "preview" // Asset: JPEG ~1280px, ориентация как у оригинала
    const val K_THUMB = "thumb"   // Asset: квадратный JPEG 320px, уже повернут
    const val K_IDS = "ids"
}
