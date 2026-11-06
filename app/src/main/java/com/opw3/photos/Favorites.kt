package com.opw3.photos

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class FavPhoto(
    val id: Long,
    val uri: Uri,
    val name: String,
    val mime: String,
    val taken: Long,
    val modified: Long,
    val size: Long,
    val orientation: Int?,
)

/** Альбом «Избранное» галереи OnePlus = MediaStore.IS_FAVORITE (лайк в галерее ставит этот флаг). */
object Favorites {
    private val COLLECTION: Uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    fun query(ctx: Context): List<FavPhoto> {
        val proj = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.ORIENTATION,
        )
        val out = ArrayList<FavPhoto>()
        ctx.contentResolver.query(
            COLLECTION, proj, "${MediaStore.MediaColumns.IS_FAVORITE}=1", null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val modified = c.getLong(4)
                val taken = if (c.isNull(3) || c.getLong(3) <= 0) modified * 1000 else c.getLong(3)
                out += FavPhoto(
                    id = id,
                    uri = ContentUris.withAppendedId(COLLECTION, id),
                    name = c.getString(1) ?: "photo_$id",
                    mime = c.getString(2) ?: "image/*",
                    taken = taken,
                    modified = modified,
                    size = c.getLong(5),
                    orientation = if (c.isNull(6)) null else c.getInt(6),
                )
            }
        }
        out.sortByDescending { it.taken }
        return out
    }

    /** Uri для чтения файла без вырезания геометок (если есть ACCESS_MEDIA_LOCATION) — настоящий оригинал. */
    fun originalUri(ctx: Context, p: FavPhoto): Uri =
        if (ctx.checkSelfPermission(android.Manifest.permission.ACCESS_MEDIA_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) MediaStore.setRequireOriginal(p.uri) else p.uri

    class Renditions(val w: Int, val h: Int, val rot: Int, val preview: ByteArray, val thumb: ByteArray)

    const val PREVIEW_MAX = 1280
    const val THUMB = 320

    fun renditions(ctx: Context, p: FavPhoto): Renditions {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(p.uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        val w = bounds.outWidth
        val h = bounds.outHeight
        require(w > 0 && h > 0) { "не удалось прочитать ${p.name}" }

        val rot = p.orientation ?: runCatching {
            cr.openInputStream(p.uri)!!.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> 90
                    ExifInterface.ORIENTATION_ROTATE_180, ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180
                    ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> 270
                    else -> 0
                }
            }
        }.getOrDefault(0)

        var sample = 1
        while (max(w, h) / (sample * 2) >= PREVIEW_MAX) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = cr.openInputStream(p.uri)!!.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: error("не удалось декодировать ${p.name}")
        val big = max(bmp.width, bmp.height)
        if (big > PREVIEW_MAX) {
            val k = PREVIEW_MAX.toFloat() / big
            val s = Bitmap.createScaledBitmap(bmp, (bmp.width * k).roundToInt(), (bmp.height * k).roundToInt(), true)
            bmp.recycle(); bmp = s
        }
        val preview = bmp.toJpeg(86)

        // Квадратная миниатюра, сразу повернутая для отображения
        val side = min(bmp.width, bmp.height)
        val m = Matrix().apply {
            val k = THUMB.toFloat() / side
            postScale(k, k)
            postRotate(rot.toFloat())
        }
        val sq = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side, m, true)
        val thumb = sq.toJpeg(88)
        sq.recycle(); bmp.recycle()
        return Renditions(w, h, ((rot % 360) + 360) % 360, preview, thumb)
    }

    private fun Bitmap.toJpeg(q: Int): ByteArray =
        ByteArrayOutputStream(256 * 1024).also { compress(Bitmap.CompressFormat.JPEG, q, it) }.toByteArray()
}
