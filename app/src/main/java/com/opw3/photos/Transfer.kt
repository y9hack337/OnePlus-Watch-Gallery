package com.opw3.photos

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.FileInputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Передача оригиналов на часы.
 *
 * Часы открывают канал Wear (ChannelClient, по Bluetooth) и присылают список нужных id.
 * Если оба устройства в одной Wi-Fi сети, телефон поднимает одноразовый TCP-сервер на своём
 * локальном адресе со случайным токеном, и часы забирают файлы напрямую по Wi-Fi (~10 МБ/с).
 * Иначе файлы идут прямо по каналу через Bluetooth. Сервер живёт, пока открыт канал.
 *
 * Формат потока в обоих случаях: [id:long][len:long][байты]... [-1:long]
 */
object Transfer {
    private const val TAG = "OPW3Xfer"

    fun handleChannel(ctx: Context, channel: ChannelClient.Channel) {
        val cc = Wearable.getChannelClient(ctx)
        val inp = Tasks.await(cc.getInputStream(channel), 20, TimeUnit.SECONDS)
        val out = DataOutputStream(BufferedOutputStream(Tasks.await(cc.getOutputStream(channel), 20, TimeUnit.SECONDS), 64 * 1024))
        var server: ServerSocket? = null
        try {
            val req = JSONObject(readLine(inp) ?: return)
            val wanted = req.getJSONArray("ids").let { a -> List(a.length()) { a.getLong(it) } }
            val favs = Favorites.query(ctx).associateBy { it.id }
            val files = wanted.mapNotNull { favs[it] }
            val ip = if (req.optBoolean("lan")) wifiIpv4(ctx) else null
            Log.i(TAG, "запрос ${files.size} оригиналов, lan=${ip?.hostAddress}")

            if (ip != null) {
                val token = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
                val srv = ServerSocket(0, 2, ip).apply { soTimeout = 60_000 }
                server = srv
                thread(name = "lan-serve") { serve(ctx, srv, token, files) }
                out.writeBytes("LAN ${ip.hostAddress} ${srv.localPort} $token\n")
                out.flush()
                // Ждём от часов «DONE» или «SEND id,id» (если Wi-Fi не сработал)
                val next = readLine(inp)
                srv.close()
                if (next != null && next.startsWith("SEND ")) {
                    val rest = next.removePrefix("SEND ").split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()
                    Log.i(TAG, "часы не достучались по Wi-Fi, отправляю ${rest.size} по Bluetooth")
                    streamFrames(ctx, out, files.filter { it.id in rest })
                }
            } else {
                out.writeBytes("BT\n")
                streamFrames(ctx, out, files)
            }
            out.flush()
        } catch (t: Throwable) {
            Log.w(TAG, "канал: ${t.message}")
        } finally {
            runCatching { server?.close() }
            runCatching { out.close() }
            runCatching { inp.close() }
            runCatching { cc.close(channel) }
        }
    }

    private fun serve(ctx: Context, srv: ServerSocket, token: String, files: List<FavPhoto>) {
        try {
            while (!srv.isClosed) {
                val s = srv.accept()
                s.use { sock ->
                    sock.soTimeout = 15_000
                    sock.tcpNoDelay = true
                    if (readLine(sock.getInputStream()) != token) {
                        Log.w(TAG, "неверный токен от ${sock.inetAddress}")
                        return@use
                    }
                    val t0 = System.currentTimeMillis()
                    val o = DataOutputStream(BufferedOutputStream(sock.getOutputStream(), 256 * 1024))
                    val bytes = streamFrames(ctx, o, files)
                    o.flush()
                    Log.i(TAG, "Wi-Fi: ${bytes / 1024} КБ за ${System.currentTimeMillis() - t0} мс")
                }
            }
        } catch (_: Throwable) {
            // сервер закрыт или таймаут
        }
    }

    private fun streamFrames(ctx: Context, out: DataOutputStream, files: List<FavPhoto>): Long {
        var total = 0L
        val buf = ByteArray(256 * 1024)
        for (p in files) {
            val pfd = runCatching { ctx.contentResolver.openFileDescriptor(Favorites.originalUri(ctx, p), "r") }.getOrNull() ?: continue
            pfd.use {
                val len = pfd.statSize
                out.writeLong(p.id)
                out.writeLong(len)
                FileInputStream(pfd.fileDescriptor).use { fin ->
                    var left = len
                    while (left > 0) {
                        val n = fin.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n < 0) error("файл короче ожидаемого: ${p.name}")
                        out.write(buf, 0, n)
                        left -= n
                    }
                }
                total += len
            }
        }
        out.writeLong(-1)
        return total
    }

    fun wifiIpv4(ctx: Context): InetAddress? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            val lp = cm.getLinkProperties(n) ?: continue
            lp.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.let { return it }
        }
        return null
    }

    fun readLine(inp: InputStream): String? {
        val sb = StringBuilder()
        while (sb.length < 65536) {
            val c = inp.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString()
            sb.append(c.toChar())
        }
        return sb.toString()
    }
}
