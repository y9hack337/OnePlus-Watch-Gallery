package com.opw3.photos

import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService

/**
 * Play services будят этот сервис, когда телефон меняет «Избранное» — даже если приложение
 * «Фото» на часах закрыто. Так фото оказываются на часах заранее, как на Apple Watch.
 */
class WatchSyncService : WearableListenerService() {

    private sealed interface Work
    private class Photo(val dm: DataMap) : Work
    private class Removed(val id: Long) : Work
    private class Manifest(val ids: LongArray) : Work

    override fun onDataChanged(events: DataEventBuffer) {
        PhotoRepo.init(this)
        // DataEventBuffer живёт только до выхода из метода — копируем данные
        val work = ArrayList<Work>()
        for (e in events) {
            val path = e.dataItem.uri.path ?: continue
            when {
                path.startsWith(DL.PHOTO_PREFIX) -> {
                    val id = path.removePrefix(DL.PHOTO_PREFIX).toLongOrNull() ?: continue
                    if (e.type == DataEvent.TYPE_DELETED) work += Removed(id)
                    else work += Photo(DataMapItem.fromDataItem(e.dataItem).dataMap)
                }
                path == DL.MANIFEST && e.type == DataEvent.TYPE_CHANGED ->
                    DataMapItem.fromDataItem(e.dataItem).dataMap.getLongArray(DL.K_IDS)?.let { work += Manifest(it) }
            }
        }
        if (work.isEmpty()) return
        Log.i("OPW3Watch", "onDataChanged: ${work.size} событий")
        // Ждём завершения, чтобы сервис оставался живым на время загрузки
        PhotoRepo.submit {
            for (w in work) {
                runCatching {
                    when (w) {
                        is Photo -> PhotoRepo.applyPhoto(w.dm)
                        is Removed -> PhotoRepo.removePhoto(w.id)
                        is Manifest -> PhotoRepo.applyManifest(w.ids)
                    }
                }.onFailure { Log.w("OPW3Watch", "ошибка обработки: ${it.message}") }
            }
            PhotoRepo.fetchMissingOriginals()
        }.get()
    }
}
