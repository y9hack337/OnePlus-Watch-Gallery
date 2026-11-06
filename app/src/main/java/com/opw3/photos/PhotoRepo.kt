package com.opw3.photos

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

data class PhotoMeta(
    val id: Long,
    val name: String,
    val mime: String,
    val w: Int,
    val h: Int,
    val rot: Int,
    val taken: Long,
    val mod: Long,
    val size: Long,
    val hasOrig: Boolean,
) {
    val key get() = "$id:$mod"

    fun toJson() = JSONObject()
        .put("id", id).put("name", name).put("mime", mime).put("w", w).put("h", h).put("rot", rot)
        .put("taken", taken).put("mod", mod).put("size", size).put("orig", hasOrig)

    companion object {
        fun fromJson(o: JSONObject) = PhotoMeta(
            o.getLong("id"), o.getString("name"), o.getString("mime"), o.getInt("w"), o.getInt("h"),
            o.getInt("rot"), o.getLong("taken"), o.getLong("mod"), o.getLong("size"), o.getBoolean("orig"),
        )
    }
}

/**
 * Локальная копия «Избранного» на часах. Все изменения — в одном фоновом потоке.
 * Файлы: <id>.t.jpg (миниатюра), <id>.p.jpg (превью), <id>.o (оригинал байт в байт).
 */
object PhotoRepo {
    private const val TAG = "OPW3Watch"
    private lateinit var app: Context
    private lateinit var dir: File
    private val main = Handler(Looper.getMainLooper())
    val io: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "photo-sync").apply { priority = Thread.NORM_PRIORITY - 1 } }

    private val byId = LinkedHashMap<Long, PhotoMeta>()
    private var order: List<Long> = emptyList()

    @Volatile var photos: List<PhotoMeta> = emptyList(); private set
    @Volatile var pending: Int = 0; private set
    @Volatile var phoneConnected: Boolean? = null; private set

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Synchronized
    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        dir = File(app.filesDir, "photos").apply { mkdirs() }
        load()
    }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun notifyChanged() = main.post { listeners.forEach { it() } }

    fun thumbFile(id: Long) = File(dir, "$id.t.jpg")
    fun previewFile(id: Long) = File(dir, "$id.p.jpg")
    fun origFile(id: Long) = File(dir, "$id.o")

    fun submit(task: () -> Unit): Future<*> = io.submit { runCatching(task).onFailure { Log.e(TAG, "task", it) } }

    // ---------- обработка данных от телефона (вызывать в io) ----------

    fun applyPhoto(m: DataMap) {
        val id = m.getLong(DL.K_ID)
        val mod = m.getLong(DL.K_MOD)
        val size = m.getLong(DL.K_SIZE)
        val cur = byId[id]
        if (cur != null && cur.mod == mod && cur.size == size && cur.hasOrig &&
            origFile(id).exists() && previewFile(id).exists() && thumbFile(id).exists()
        ) return

        val sameVersion = cur != null && cur.mod == mod && cur.size == size
        if (!sameVersion) origFile(id).delete()
        val meta = PhotoMeta(
            id, m.getString(DL.K_NAME, "photo"), m.getString(DL.K_MIME, "image/*"),
            m.getInt(DL.K_W), m.getInt(DL.K_H), m.getInt(DL.K_ROT), m.getLong(DL.K_TAKEN), mod, size,
            hasOrig = sameVersion && cur!!.hasOrig && origFile(id).exists(),
        )
        pending++
        notifyChanged()
        try {
            // Сначала лёгкие миниатюра и превью — фото сразу видно в сетке
            if (!sameVersion || !thumbFile(id).exists()) fetch(m.getAsset(DL.K_THUMB), thumbFile(id))
            if (!sameVersion || !previewFile(id).exists()) fetch(m.getAsset(DL.K_PREVIEW), previewFile(id))
            put(meta)
            lastItemAt = System.currentTimeMillis()
            // Оригиналы забираются пачкой в fetchMissingOriginals()
        } finally {
            pending--
            notifyChanged()
        }
    }

    fun removePhoto(id: Long) {
        if (byId.remove(id) == null) return
        thumbFile(id).delete(); previewFile(id).delete(); origFile(id).delete()
        Log.i(TAG, "удалено $id")
        commit()
        reportState()
    }

    fun applyManifest(ids: LongArray) {
        order = ids.toList()
        val keep = ids.toHashSet()
        for (id in byId.keys.toList()) if (id !in keep) removePhoto(id)
        commit()
    }

    /** Полная сверка с хранилищем Data Layer: подхватывает всё, что пропущено, пока приложение не работало. */
    fun reconcile() {
        val dc = Wearable.getDataClient(app)
        val photosRemote = ArrayList<DataMap>()
        var manifest: LongArray? = null
        val buf = Tasks.await(dc.getDataItems(Uri.parse("wear://*/fav/"), DataClient.FILTER_PREFIX), 20, TimeUnit.SECONDS)
        try {
            for (item in buf) {
                val path = item.uri.path ?: continue
                val dm = DataMapItem.fromDataItem(item).dataMap
                when {
                    path.startsWith(DL.PHOTO_PREFIX) -> photosRemote += dm
                    path == DL.MANIFEST -> manifest = dm.getLongArray(DL.K_IDS)
                }
            }
        } finally {
            buf.release()
        }
        manifest?.let { applyManifest(it) }
        val remoteIds = photosRemote.mapTo(HashSet()) { it.getLong(DL.K_ID) }
        if (manifest != null) for (id in byId.keys.toList()) if (id !in remoteIds) removePhoto(id)
        // новые — по порядку манифеста
        val pos = order.withIndex().associate { it.value to it.index }
        photosRemote.sortBy { pos[it.getLong(DL.K_ID)] ?: Int.MAX_VALUE }
        for (dm in photosRemote) runCatching { applyPhoto(dm) }.onFailure { Log.w(TAG, "не скачано: ${it.message}") }
        fetchMissingOriginals()
    }

    // ---------- оригиналы: Wi-Fi напрямую, иначе Bluetooth ----------

    private var xferFailedAt = 0L
    private const val BT_CHANNELS = 3
    @Volatile private var lastItemAt = 0L
    @Volatile private var retryPosted = false

    /** Забирает все недостающие оригиналы одним запросом к телефону (вызывать в io). */
    fun fetchMissingOriginals() {
        val missing = byId.values.filter { !it.hasOrig }
        if (missing.isEmpty()) return
        if (System.currentTimeMillis() - xferFailedAt < 10_000) return
        // Сначала пусть дойдут все превью (по Bluetooth это важно: фото сразу видны в сетке),
        // потом одним заходом — оригиналы. Если какое-то превью застряло, ждём не дольше 8 с.
        val since = System.currentTimeMillis() - lastItemAt
        if (order.any { it !in byId } && since < 8_000) {
            if (!retryPosted) {
                retryPosted = true
                main.postDelayed({ retryPosted = false; submit { fetchMissingOriginals() } }, 8_000 - since + 200)
            }
            return
        }
        val node = phoneNodeId() ?: return
        val cc = Wearable.getChannelClient(app)
        val t0 = System.currentTimeMillis()
        val got = HashSet<Long>()
        var wifi: Pair<Network, ConnectivityManager.NetworkCallback?>? = null
        var via = "BT"
        var bytes = 0L
        // Wi-Fi включаем специально, только если качать много; если он уже подключён — берём сразу
        val phoneOnWifi = runCatching {
            Tasks.await(Wearable.getMessageClient(app).sendRequest(node, DL.LAN_PROBE, ByteArray(0)), 5, TimeUnit.SECONDS)
                .firstOrNull() == 1.toByte()
        }.getOrDefault(true)
        wifi = if (phoneOnWifi) wifiNetwork(allowRequest = missing.sumOf { it.size } > 2 * 1024 * 1024) else null
        if (wifi == null && missing.size > 1) {
            fetchOverBluetooth(node, missing)
            return
        }
        val ch = try {
            Tasks.await(cc.openChannel(node, DL.XFER), 20, TimeUnit.SECONDS)
        } catch (t: Throwable) {
            Log.w(TAG, "канал не открыт: ${t.message}"); xferFailedAt = System.currentTimeMillis()
            wifi?.second?.let { cb -> runCatching { app.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) } }
            return
        }
        try {
            val out = Tasks.await(cc.getOutputStream(ch), 20, TimeUnit.SECONDS)
            val inp = DataInputStream(BufferedInputStream(Tasks.await(cc.getInputStream(ch), 20, TimeUnit.SECONDS), 64 * 1024))
            val req = JSONObject().put("ids", JSONArray(missing.map { it.id })).put("lan", wifi != null)
            out.write((req.toString() + "\n").toByteArray()); out.flush()
            val resp = readLine(inp) ?: error("телефон закрыл канал")
            if (resp.startsWith("LAN ")) {
                val (ip, port, token) = resp.removePrefix("LAN ").split(' ')
                runCatching {
                    val s = wifi!!.first.socketFactory.createSocket()
                    s.use { sock ->
                        sock.connect(InetSocketAddress(ip, port.toInt()), 3000)
                        sock.soTimeout = 15_000
                        sock.receiveBufferSize = 512 * 1024
                        sock.getOutputStream().apply { write("$token\n".toByteArray()); flush() }
                        bytes += receiveFrames(DataInputStream(BufferedInputStream(sock.getInputStream(), 256 * 1024)), got)
                    }
                    via = "Wi-Fi"
                }.onFailure { Log.w(TAG, "Wi-Fi не удался: ${it.message}") }
                val rest = missing.map { it.id }.filter { it !in got }
                if (rest.isEmpty()) out.write("DONE\n".toByteArray())
                else {
                    out.write(("SEND " + rest.joinToString(",") + "\n").toByteArray())
                    out.flush()
                    bytes += receiveFrames(inp, got)
                    via += "+BT"
                }
                out.flush()
            } else {
                bytes += receiveFrames(inp, got)
            }
            val ms = System.currentTimeMillis() - t0
            Log.i(TAG, "оригиналы: ${got.size}/${missing.size}, ${bytes / 1024} КБ за $ms мс через $via" +
                (if (ms > 0) " (${bytes * 1000 / 1024 / ms} КБ/с)" else ""))
        } catch (t: Throwable) {
            Log.w(TAG, "передача оригиналов: ${t.message}")
            xferFailedAt = System.currentTimeMillis()
        } finally {
            runCatching { cc.close(ch) }
            wifi?.second?.let { cb -> runCatching { app.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) } }
            if (got.isNotEmpty()) reportState()
        }
    }

    /** Без Wi-Fi: несколько параллельных каналов Wear по Bluetooth (обходит ограничение окна одного канала). */
    private fun fetchOverBluetooth(node: String, missing: List<PhotoMeta>) {
        val cc = Wearable.getChannelClient(app)
        val n = minOf(BT_CHANNELS, missing.size)
        // раскладываем по каналам, выравнивая объём
        val groups = List(n) { ArrayList<PhotoMeta>() }
        val load = LongArray(n)
        for (m in missing.sortedByDescending { it.size }) {
            val i = load.indices.minBy { load[it] }
            groups[i] += m; load[i] += m.size
        }
        val got = java.util.Collections.synchronizedSet(HashSet<Long>())
        val bytes = java.util.concurrent.atomic.AtomicLong()
        val t0 = System.currentTimeMillis()
        val threads = groups.map { g ->
            Thread {
                var ch: com.google.android.gms.wearable.ChannelClient.Channel? = null
                try {
                    ch = Tasks.await(cc.openChannel(node, DL.XFER), 20, TimeUnit.SECONDS)
                    val out = Tasks.await(cc.getOutputStream(ch), 20, TimeUnit.SECONDS)
                    val inp = DataInputStream(BufferedInputStream(Tasks.await(cc.getInputStream(ch), 20, TimeUnit.SECONDS), 64 * 1024))
                    val req = JSONObject().put("ids", JSONArray(g.map { it.id })).put("lan", false)
                    out.write((req.toString() + "\n").toByteArray()); out.flush()
                    readLine(inp) // "BT"
                    bytes.addAndGet(receiveFrames(inp, got))
                } catch (t: Throwable) {
                    Log.w(TAG, "BT-канал: ${t.message}")
                } finally {
                    ch?.let { runCatching { cc.close(it) } }
                }
            }.apply { start() }
        }
        threads.forEach { it.join() }
        val ms = System.currentTimeMillis() - t0
        if (got.size < missing.size) xferFailedAt = System.currentTimeMillis()
        Log.i(TAG, "оригиналы: ${got.size}/${missing.size}, ${bytes.get() / 1024} КБ за $ms мс через BT×$n" +
            (if (ms > 0) " (${bytes.get() * 1000 / 1024 / ms} КБ/с)" else ""))
        if (got.isNotEmpty()) reportState()
    }

    private fun receiveFrames(inp: DataInputStream, got: MutableSet<Long>): Long {
        var total = 0L
        val buf = ByteArray(256 * 1024)
        while (true) {
            val id = inp.readLong()
            if (id < 0) return total
            var left = inp.readLong()
            val tmp = File(dir, "$id.o.part")
            tmp.outputStream().use { o ->
                while (left > 0) {
                    val n = inp.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) error("обрыв потока")
                    o.write(buf, 0, n)
                    left -= n; total += n
                }
            }
            synchronized(this) {
                val m = byId[id]
                if (m == null) { tmp.delete(); return@synchronized }
                val dst = origFile(id)
                dst.delete()
                check(tmp.renameTo(dst)) { "rename" }
                got += id
                put(m.copy(hasOrig = true))
            }
        }
    }

    private fun wifiNetwork(allowRequest: Boolean): Pair<Network, ConnectivityManager.NetworkCallback?>? {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
            ?.let { return it to null }
        if (!allowRequest) return null
        val latch = CountDownLatch(1)
        var net: Network? = null
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { net = network; latch.countDown() }
        }
        cm.requestNetwork(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), cb
        )
        latch.await(6, TimeUnit.SECONDS)
        val n = net
        if (n == null) { runCatching { cm.unregisterNetworkCallback(cb) }; return null }
        return n to cb
    }

    private fun phoneNodeId(): String? = runCatching {
        val cap = Tasks.await(
            Wearable.getCapabilityClient(app).getCapability(DL.CAP_PHONE, CapabilityClient.FILTER_REACHABLE), 10, TimeUnit.SECONDS
        )
        val nodes = cap.nodes.ifEmpty { Tasks.await(Wearable.getNodeClient(app).connectedNodes, 10, TimeUnit.SECONDS).toSet() }
        (nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull())?.id
    }.getOrNull().also { if (it == null) Log.w(TAG, "телефон не найден") }

    private fun readLine(inp: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (sb.length < 4096) {
            val c = inp.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString()
            sb.append(c.toChar())
        }
        return sb.toString()
    }

    /** Открыли приложение: просим телефон проверить «Избранное» и сверяемся сами. */
    fun requestSync() = submit {
        try {
            val cap = Tasks.await(
                Wearable.getCapabilityClient(app).getCapability(DL.CAP_PHONE, CapabilityClient.FILTER_REACHABLE),
                10, TimeUnit.SECONDS,
            )
            val nodes = cap.nodes.ifEmpty { Tasks.await(Wearable.getNodeClient(app).connectedNodes, 10, TimeUnit.SECONDS).toSet() }
            phoneConnected = nodes.isNotEmpty()
            notifyChanged()
            for (n in nodes) Wearable.getMessageClient(app).sendMessage(n.id, DL.MSG_SYNC, null)
        } catch (t: Throwable) {
            Log.w(TAG, "requestSync: ${t.message}")
            phoneConnected = false
            notifyChanged()
        }
        reconcile()
    }

    private fun fetch(asset: Asset?, dst: File) {
        requireNotNull(asset) { "нет asset для ${dst.name}" }
        val resp = Tasks.await(Wearable.getDataClient(app).getFdForAsset(asset), 15, TimeUnit.MINUTES)
        val tmp = File(dst.path + ".part")
        resp.inputStream.use { inp -> tmp.outputStream().use { out -> inp.copyTo(out, 256 * 1024) } }
        if (!tmp.renameTo(dst)) { dst.delete(); check(tmp.renameTo(dst)) { "rename ${dst.name}" } }
    }

    private fun reportState() {
        val ids = byId.values.filter { it.hasOrig }.map { it.id }.toLongArray()
        val req = PutDataMapRequest.create(DL.WATCH_STATE).apply {
            dataMap.putLongArray(DL.K_IDS, ids)
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(app).putDataItem(req)
    }

    // ---------- индекс ----------

    private fun put(m: PhotoMeta) {
        byId[m.id] = m
        commit()
    }

    private fun commit() {
        val pos = order.withIndex().associate { it.value to it.index }
        photos = byId.values.sortedWith(
            compareBy<PhotoMeta> { pos[it.id] ?: Int.MAX_VALUE }.thenByDescending { it.taken }
        )
        save()
        notifyChanged()
    }

    private fun indexFile() = File(dir, "index.json")

    private fun save() {
        val o = JSONObject()
            .put("order", JSONArray(order))
            .put("photos", JSONArray().apply { byId.values.forEach { put(it.toJson()) } })
        val tmp = File(dir, "index.json.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(indexFile())
    }

    private fun load() {
        runCatching {
            val f = indexFile()
            if (!f.exists()) return
            val o = JSONObject(f.readText())
            val ord = o.getJSONArray("order")
            order = List(ord.length()) { ord.getLong(it) }
            val arr = o.getJSONArray("photos")
            for (i in 0 until arr.length()) {
                val m = PhotoMeta.fromJson(arr.getJSONObject(i))
                if (thumbFile(m.id).exists() && previewFile(m.id).exists())
                    byId[m.id] = m.copy(hasOrig = m.hasOrig && origFile(m.id).exists())
            }
            val pos = order.withIndex().associate { it.value to it.index }
            photos = byId.values.sortedWith(compareBy<PhotoMeta> { pos[it.id] ?: Int.MAX_VALUE }.thenByDescending { it.taken })
        }.onFailure { Log.e(TAG, "index", it) }
    }
}
