package com.opw3.photos

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.util.concurrent.Executors

/** Кэш миниатюр и превью в памяти + фоновое декодирование. */
object Images {
    private val main = Handler(Looper.getMainLooper())
    private val decoder = Executors.newFixedThreadPool(2) { r -> Thread(r, "img-decode").apply { priority = Thread.NORM_PRIORITY } }

    private val thumbs = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val previews = object : LruCache<String, Bitmap>(40 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val inflight = HashMap<String, MutableList<(Bitmap?) -> Unit>>()

    fun cachedThumb(m: PhotoMeta): Bitmap? = thumbs.get(m.key)
    fun cachedPreview(m: PhotoMeta): Bitmap? = previews.get(m.key)

    fun thumb(m: PhotoMeta, cb: (Bitmap?) -> Unit) =
        load("t" + m.key, thumbs, m.key, cb) { BitmapFactory.decodeFile(PhotoRepo.thumbFile(m.id).path) }

    fun preview(m: PhotoMeta, cb: (Bitmap?) -> Unit) =
        load("p" + m.key, previews, m.key, cb) { BitmapFactory.decodeFile(PhotoRepo.previewFile(m.id).path) }

    private fun load(
        flightKey: String,
        cache: LruCache<String, Bitmap>,
        key: String,
        cb: (Bitmap?) -> Unit,
        decode: () -> Bitmap?,
    ) {
        cache.get(key)?.let { cb(it); return }
        inflight[flightKey]?.let { it += cb; return }
        inflight[flightKey] = mutableListOf(cb)
        decoder.execute {
            val b = runCatching(decode).getOrNull()?.also { it.prepareToDraw() }
            main.post {
                if (b != null) cache.put(key, b)
                inflight.remove(flightKey)?.forEach { it(b) }
            }
        }
    }
}
