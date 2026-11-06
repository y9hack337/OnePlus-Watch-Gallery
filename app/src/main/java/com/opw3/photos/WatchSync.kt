package com.opw3.photos

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/**
 * Синхронизация «Избранного» в Wear Data Layer.
 * Каждое фото — DataItem /fav/photo/<id> с тремя Asset (оригинал, превью, миниатюра).
 * Доставку на часы (Bluetooth/Wi-Fi, повторы, фон) выполняют Google Play services —
 * тот же канал, через который работает OHealth. Своих серверов нет.
 */
object WatchSync {
    private const val TAG = "OPW3Sync"
    private val mutex = Mutex()

    data class State(
        val running: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
        val error: String? = null,
        val lastRun: Long = 0,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED

    suspend fun sync(ctx: Context, reason: String) {
        val app = ctx.applicationContext
        if (!hasPermission(app)) {
            Log.w(TAG, "нет разрешения READ_MEDIA_IMAGES")
            return
        }
        mutex.withLock {
            // Медиатека меняется часто (камера, скриншоты, загрузки). Если «Избранное» то же самое —
            // выходим сразу, не трогая Play services и Bluetooth: почти нулевой расход батареи.
            if (reason == "media" && signature(Favorites.query(app)) == prefs(app).getString(SIG, null)) {
                Log.d(TAG, "media: избранное не изменилось")
                return
            }
            // Повторяем, пока список избранного не перестанет меняться во время отправки
            var guard = 0
            do {
                val before = runOnce(app, reason)
                val after = Favorites.query(app).map { it.id to it.modified }
            } while (before != after && ++guard < 5)
        }
    }

    private const val SIG = "synced_sig"
    private fun prefs(c: Context) = c.getSharedPreferences("sync", Context.MODE_PRIVATE)
    private fun signature(favs: List<FavPhoto>) =
        "${DL.FORMAT}|" + favs.joinToString(",") { "${it.id}:${it.modified}:${it.size}" }

    private data class Sent(val mod: Long, val size: Long, val fmt: Int)

    private suspend fun runOnce(app: Context, reason: String): List<Pair<Long, Long>> {
        val favs = Favorites.query(app)
        _state.value = State(running = true, total = favs.size)
        Log.i(TAG, "sync ($reason): избранных ${favs.size}")
        try {
            val dc = Wearable.getDataClient(app)
            val local = Wearable.getNodeClient(app).localNode.await().id
            val dir = Uri.Builder().scheme("wear").authority(local).path(DL.PHOTO_DIR).build()

            val existing = HashMap<Long, Sent>()
            val buf = dc.getDataItems(dir, DataClient.FILTER_PREFIX).await()
            try {
                for (item in buf) {
                    if (!item.uri.path.orEmpty().startsWith(DL.PHOTO_PREFIX)) continue
                    val m = DataMapItem.fromDataItem(item).dataMap
                    existing[m.getLong(DL.K_ID)] =
                        Sent(m.getLong(DL.K_MOD), m.getLong(DL.K_SIZE), m.getInt(DL.K_FORMAT))
                }
            } finally {
                buf.release()
            }

            var done = 0
            var sent = 0
            for (p in favs) {
                val e = existing[p.id]
                if (e == null || e.mod != p.modified || e.size != p.size || e.fmt != DL.FORMAT) {
                    runCatching { put(app, dc, p); sent++ }
                        .onFailure { Log.e(TAG, "не отправлено ${p.name}", it) }
                }
                _state.value = _state.value.copy(done = ++done)
            }

            val keep = favs.mapTo(HashSet()) { it.id }
            for (id in existing.keys - keep) {
                val u = Uri.Builder().scheme("wear").authority(local).path(DL.PHOTO_PREFIX + id).build()
                dc.deleteDataItems(u).await()
                Log.i(TAG, "убрано с часов: $id")
            }

            val manifest = PutDataMapRequest.create(DL.MANIFEST).apply {
                dataMap.putLongArray(DL.K_IDS, favs.map { it.id }.toLongArray())
            }.asPutDataRequest().setUrgent()
            dc.putDataItem(manifest).await()
            Log.i(TAG, "sync готов: отправлено $sent, удалено ${(existing.keys - keep).size}")
            if (sent == favs.count { existing[it.id]?.let { e -> e.mod != it.modified || e.size != it.size || e.fmt != DL.FORMAT } ?: true })
                prefs(app).edit().putString(SIG, signature(favs)).apply()
            _state.value = State(done = favs.size, total = favs.size, lastRun = System.currentTimeMillis())
        } catch (t: Throwable) {
            Log.e(TAG, "sync failed", t)
            _state.value = State(error = t.message ?: t.javaClass.simpleName, lastRun = System.currentTimeMillis())
        }
        return favs.map { it.id to it.modified }
    }

    private suspend fun put(app: Context, dc: DataClient, p: FavPhoto) {
        val r = Favorites.renditions(app, p)
        run {

            val req = PutDataMapRequest.create(DL.PHOTO_PREFIX + p.id).apply {
                dataMap.putLong(DL.K_ID, p.id)
                dataMap.putString(DL.K_NAME, p.name)
                dataMap.putString(DL.K_MIME, p.mime)
                dataMap.putInt(DL.K_W, r.w)
                dataMap.putInt(DL.K_H, r.h)
                dataMap.putInt(DL.K_ROT, r.rot)
                dataMap.putLong(DL.K_TAKEN, p.taken)
                dataMap.putLong(DL.K_MOD, p.modified)
                dataMap.putLong(DL.K_SIZE, p.size)
                dataMap.putInt(DL.K_FORMAT, DL.FORMAT)
                dataMap.putAsset(DL.K_THUMB, Asset.createFromBytes(r.thumb))
                dataMap.putAsset(DL.K_PREVIEW, Asset.createFromBytes(r.preview))
            }.asPutDataRequest().setUrgent()
            dc.putDataItem(req).await()
        }
        Log.i(TAG, "отправлено ${p.name} ${r.w}x${r.h} rot=${r.rot} ${p.size / 1024} КБ")
    }
}
