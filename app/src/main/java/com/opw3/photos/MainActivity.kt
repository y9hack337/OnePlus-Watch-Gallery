package com.opw3.photos

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateFormat
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class MainActivity : Activity(), DataClient.OnDataChangedListener {
    private val scope = MainScope()
    private lateinit var status: TextView
    private lateinit var syncInfo: TextView
    private lateinit var list: LinearLayout
    private lateinit var batteryBtn: Button
    private lateinit var permBtn: Button
    private lateinit var iconBtn: Button
    private var onWatch: Set<Long> = emptySet()
    private var refreshJob: Job? = null

    private val dark get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val fg get() = if (dark) Color.WHITE else Color.BLACK
    private val sub get() = if (dark) 0xFFAAAAAA.toInt() else 0xFF666666.toInt()
    private val card get() = if (dark) 0xFF1C1C1E.toInt() else 0xFFF2F2F7.toInt()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = if (dark) Color.BLACK else Color.WHITE

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }
        root.addView(TextView(this).apply {
            text = "Фото на часах"
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(fg)
        })
        root.addView(TextView(this).apply {
            text = "Фото с ♥ из Галереи OnePlus автоматически появляются в приложении «Фото» на OnePlus Watch 3 в оригинальном качестве."
            textSize = 14f
            setTextColor(sub)
            setPadding(0, dp(6), 0, dp(16))
        })

        val statusCard = cardView()
        status = TextView(this).apply { textSize = 16f; setTextColor(fg) }
        syncInfo = TextView(this).apply { textSize = 13f; setTextColor(sub); setPadding(0, dp(6), 0, 0) }
        statusCard.addView(status)
        statusCard.addView(syncInfo)
        root.addView(statusCard)

        permBtn = button("Разрешить доступ к фото") { requestPerms() }
        root.addView(permBtn)
        root.addView(button("Синхронизировать сейчас") {
            scope.launch(Dispatchers.IO) { WatchSync.sync(applicationContext, "manual") }
        })
        batteryBtn = button("Разрешить работу в фоне") {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        root.addView(batteryBtn)
        iconBtn = button("") { setIconHidden(!isIconHidden()) }
        root.addView(iconBtn)
        root.addView(TextView(this).apply {
            text = "Синхронизация работает и со скрытым значком. Открыть этот экран можно с часов: " +
                "долгое нажатие на «Избранное» в приложении «Фото»."
            textSize = 12f
            setTextColor(sub)
            setPadding(dp(4), dp(6), dp(4), 0)
        })

        root.addView(TextView(this).apply {
            text = "Избранное"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(fg)
            setPadding(0, dp(24), 0, dp(8))
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(if (dark) Color.BLACK else Color.WHITE)
            addView(root)
        })

        scope.launch {
            WatchSync.state.collect { s ->
                syncInfo.text = when {
                    s.running -> "Отправка на часы… ${s.done}/${s.total}"
                    s.error != null -> "Ошибка: ${s.error}"
                    s.lastRun > 0 -> "Синхронизировано в " + DateFormat.getTimeFormat(this@MainActivity).format(s.lastRun)
                    else -> "Синхронизация идёт автоматически"
                }
                if (!s.running && s.lastRun > 0) refresh()
            }
        }
        if (!WatchSync.hasPermission(this)) requestPerms()
    }

    private val launcherAlias get() = android.content.ComponentName(this, "$packageName.Launcher")

    private fun isIconHidden() = packageManager.getComponentEnabledSetting(launcherAlias) ==
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED

    private fun setIconHidden(hidden: Boolean) {
        packageManager.setComponentEnabledSetting(
            launcherAlias,
            if (hidden) PackageManager.COMPONENT_ENABLED_STATE_DISABLED else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            PackageManager.DONT_KILL_APP,
        )
        updateIconBtn()
    }

    private fun updateIconBtn() {
        iconBtn.text = if (isIconHidden()) "Показать значок на рабочем столе" else "Скрыть значок с рабочего стола"
    }

    private fun requestPerms() {
        requestPermissions(arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.ACCESS_MEDIA_LOCATION), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
        if (WatchSync.hasPermission(this)) scope.launch(Dispatchers.IO) { WatchSync.sync(applicationContext, "perm") }
    }

    override fun onResume() {
        super.onResume()
        Wearable.getDataClient(this).addListener(this)
        refresh()
        if (WatchSync.hasPermission(this)) scope.launch(Dispatchers.IO) { WatchSync.sync(applicationContext, "open") }
    }

    override fun onPause() {
        Wearable.getDataClient(this).removeListener(this)
        super.onPause()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onDataChanged(events: DataEventBuffer) {
        if (events.any { it.dataItem.uri.path == DL.WATCH_STATE }) refresh()
    }

    private fun refresh() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            val pm = getSystemService(PowerManager::class.java)
            batteryBtn.visibility = if (pm.isIgnoringBatteryOptimizations(packageName)) View.GONE else View.VISIBLE
            val granted = WatchSync.hasPermission(this@MainActivity)
            permBtn.visibility = if (granted) View.GONE else View.VISIBLE
            updateIconBtn()

            val (statusText, ids) = withContext(Dispatchers.IO) { watchStatus() }
            onWatch = ids
            status.text = statusText

            val favs = if (granted) withContext(Dispatchers.IO) { Favorites.query(this@MainActivity) } else emptyList()
            list.removeAllViews()
            if (favs.isEmpty()) {
                list.addView(TextView(this@MainActivity).apply {
                    text = if (granted) "Пока пусто. Поставьте ♥ фото в Галерее." else "Нет доступа к фото."
                    setTextColor(sub)
                })
            }
            for (p in favs) list.addView(row(p))
        }
    }

    private suspend fun watchStatus(): Pair<String, Set<Long>> = runCatching {
        val nodes = Wearable.getNodeClient(this).connectedNodes.await()
        val cap = Wearable.getCapabilityClient(this)
            .getCapability(DL.CAP_WATCH, CapabilityClient.FILTER_ALL).await()
        val ids = HashSet<Long>()
        val buf = Wearable.getDataClient(this)
            .getDataItems(Uri.parse("wear://*" + DL.WATCH_STATE)).await()
        try {
            for (it in buf) DataMapItem.fromDataItem(it).dataMap.getLongArray(DL.K_IDS)?.forEach { id -> ids += id }
        } finally {
            buf.release()
        }
        val watch = nodes.firstOrNull()
        val text = when {
            watch == null -> "⌚ Часы не подключены\nФото отправятся, как только часы появятся рядом."
            cap.nodes.isEmpty() -> "⌚ ${watch.displayName} — подключены\nУстановите «Фото» на часы."
            else -> "⌚ ${watch.displayName} — подключены ✓"
        }
        text to ids
    }.getOrElse { "Wear OS недоступен: ${it.message}" to emptySet() }

    private fun row(p: FavPhoto): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val iv = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(card)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height, dp(10).toFloat())
            }
            clipToOutline = true
        }
        r.addView(iv)
        scope.launch {
            val b = withContext(Dispatchers.IO) {
                runCatching { contentResolver.loadThumbnail(p.uri, Size(dp(56), dp(56)), null) }.getOrNull()
            }
            iv.setImageBitmap(b)
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(this).apply { text = p.name; setTextColor(fg); textSize = 15f; maxLines = 1 })
        texts.addView(TextView(this).apply {
            text = DateFormat.getMediumDateFormat(this@MainActivity).format(p.taken) + " · " + human(p.size)
            setTextColor(sub); textSize = 13f
        })
        r.addView(texts)
        r.addView(TextView(this).apply {
            val ok = p.id in onWatch
            text = if (ok) "на часах ✓" else "в очереди"
            setTextColor(if (ok) 0xFF30B050.toInt() else sub)
            textSize = 13f
        })
        return r
    }

    private fun human(b: Long) = if (b > 1024 * 1024) "%.1f МБ".format(b / 1048576f) else "${b / 1024} КБ"

    private fun cardView() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(card) }
    }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(10) }
        setOnClickListener { onClick() }
    }
}
