package com.opw3.photos

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.runBlocking

/** Один APK на оба устройства: роль определяется по железу. */
object Role {
    fun isWatch(ctx: Context) = ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)
}

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val watch = Role.isWatch(this)
        // Выключаем компоненты чужой роли, чтобы Play services не будили их зря
        setEnabled(WatchSyncService::class.java, watch)
        setEnabled(PhoneListenerService::class.java, !watch)
        setEnabled(BootReceiver::class.java, !watch)
        if (!watch) SyncJob.schedule(this)
    }

    private fun setEnabled(cls: Class<*>, on: Boolean) {
        val cn = ComponentName(this, cls)
        val want = if (on) PackageManager.COMPONENT_ENABLED_STATE_DEFAULT else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        val cur = packageManager.getComponentEnabledSetting(cn)
        val curNorm = if (cur == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) PackageManager.COMPONENT_ENABLED_STATE_DEFAULT else cur
        if (curNorm != want) packageManager.setComponentEnabledSetting(cn, want, PackageManager.DONT_KILL_APP)
    }
}

/** Значок приложения: на часах — галерея, на телефоне — экран настроек. */
class LaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = if (Role.isWatch(this)) GalleryActivity::class.java else MainActivity::class.java
        startActivity(Intent(this, target))
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!Role.isWatch(context)) SyncJob.schedule(context)
    }
}

/** Телефон: часы открыли «Фото» и просят актуальный список — Play services будят нас сами. */
class PhoneListenerService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == DL.MSG_SYNC) {
            runBlocking { WatchSync.sync(applicationContext, "watch") }
        }
    }

    /** Часы забирают оригиналы. Держим вызов, пока идёт передача, — так сервис остаётся живым. */
    /** Быстрый вопрос часов перед передачей: есть ли у телефона Wi-Fi (иначе часы сразу идут по Bluetooth). */
    override fun onRequest(nodeId: String, path: String, request: ByteArray): com.google.android.gms.tasks.Task<ByteArray>? {
        if (path != DL.LAN_PROBE) return null
        val on = Transfer.wifiIpv4(applicationContext) != null
        return com.google.android.gms.tasks.Tasks.forResult(byteArrayOf(if (on) 1 else 0))
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (channel.path != DL.XFER) return
        // Колбэки сервиса идут в одном потоке — каждый канал обслуживаем отдельно, чтобы
        // часы могли качать несколько файлов параллельно. Wakelock держит CPU до конца передачи.
        val wl = getSystemService(android.os.PowerManager::class.java)
            .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "opw3:xfer").apply { acquire(10 * 60_000L) }
        Thread({
            try { Transfer.handleChannel(applicationContext, channel) } finally { if (wl.isHeld) wl.release() }
        }, "xfer").start()
    }
}
