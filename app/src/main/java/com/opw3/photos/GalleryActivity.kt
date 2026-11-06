package com.opw3.photos

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import androidx.wear.widget.SwipeDismissFrameLayout
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

class GalleryActivity : ComponentActivity(), ZoomImageView.Callbacks {

    private lateinit var root: FrameLayout
    private lateinit var grid: RecyclerView
    private lateinit var glm: GridLayoutManager
    private lateinit var gridAdapter: GridAdapter
    private lateinit var empty: TextView
    private lateinit var outerSwipe: SwipeDismissFrameLayout
    private lateinit var innerSwipe: SwipeDismissFrameLayout
    private lateinit var pager: ViewPager2
    private lateinit var pagerAdapter: PagerAdapter
    private lateinit var dots: PageDots
    private lateinit var info: TextView

    private var viewerOpen = false
    private var animating = false
    private val ease = PathInterpolator(0.2f, 0f, 0f, 1f)
    private val dateFmt = SimpleDateFormat("d MMMM yyyy", Locale("ru"))
    private val prefs by lazy { getSharedPreferences("ui", MODE_PRIVATE) }

    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = closeViewer()
    }
    private val repoListener: () -> Unit = { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PhotoRepo.init(this)
        setContentView(R.layout.activity_gallery)
        root = findViewById(R.id.root)
        grid = findViewById(R.id.grid)
        empty = findViewById(R.id.empty)
        outerSwipe = findViewById(R.id.outer_swipe)
        innerSwipe = findViewById(R.id.inner_swipe)
        pager = findViewById(R.id.pager)
        dots = findViewById(R.id.dots)
        info = findViewById(R.id.info)
        onBackPressedDispatcher.addCallback(this, back)

        setupGrid()
        setupViewer()

        outerSwipe.addCallback(object : SwipeDismissFrameLayout.Callback() {
            override fun onDismissed(layout: SwipeDismissFrameLayout) {
                layout.visibility = View.GONE
                finish()
                overridePendingTransition(0, 0)
            }
        })
        innerSwipe.addCallback(object : SwipeDismissFrameLayout.Callback() {
            override fun onDismissed(layout: SwipeDismissFrameLayout) {
                hideViewerNow()
            }
        })
        refresh()
    }

    override fun onStart() {
        super.onStart()
        PhotoRepo.addListener(repoListener)
        PhotoRepo.requestSync()
        refresh()
    }

    override fun onStop() {
        PhotoRepo.removeListener(repoListener)
        super.onStop()
    }

    // ---------------- сетка ----------------

    private fun setupGrid() {
        val span = prefs.getInt("span", 3)
        glm = GridLayoutManager(this, span)
        glm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) = if (position == 0) glm.spanCount else 1
        }
        gridAdapter = GridAdapter(onLongHeader = { openPhoneSettings() }) { index, cell -> openViewer(index, cell) }
        grid.layoutManager = glm
        grid.adapter = gridAdapter
        grid.itemAnimator?.changeDuration = 180
        grid.setHasFixedSize(true)

        grid.addOnLayoutChangeListener { v, l, t, r, b, ol, ot, or_, ob ->
            if (r - l != or_ - ol || b - t != ob - ot) {
                val w = r - l
                val h = b - t
                val round = resources.configuration.isScreenRound
                grid.setPadding(
                    (w * if (round) 0.12f else 0.04f).toInt(), (h * 0.165f).toInt(),
                    (w * if (round) 0.12f else 0.04f).toInt(), (h * 0.24f).toInt()
                )
            }
            grid.post { applyLens() }
        }
        grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) = applyLens()
        })

        // Щипок по сетке — меняет число колонок (2…4)
        var acc = 1f
        val sgd = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean { acc = 1f; return true }
            override fun onScale(d: ScaleGestureDetector): Boolean {
                acc *= d.scaleFactor
                val target = when {
                    acc > 1.25f -> glm.spanCount - 1
                    acc < 0.8f -> glm.spanCount + 1
                    else -> return true
                }.coerceIn(2, 4)
                if (target != glm.spanCount) {
                    acc = 1f
                    setSpan(target)
                }
                return true
            }
        })
        grid.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                sgd.onTouchEvent(e)
                return sgd.isInProgress || e.pointerCount > 1
            }
            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) { sgd.onTouchEvent(e) }
        })
    }

    private fun setSpan(n: Int) {
        val first = glm.findFirstVisibleItemPosition().coerceAtLeast(0)
        glm.spanCount = n
        prefs.edit().putInt("span", n).apply()
        gridAdapter.notifyItemRangeChanged(0, gridAdapter.itemCount)
        glm.scrollToPosition(first)
    }

    /** «Линза» под круглый экран: ячейки у верхнего и нижнего края уменьшаются и тускнеют. */
    private fun applyLens() {
        val h = grid.height
        if (h == 0) return
        val cy = h / 2f
        val cx = grid.width / 2f
        val r = h / 2f
        for (i in 0 until grid.childCount) {
            val c = grid.getChildAt(i)
            val ccy = (c.top + c.bottom) / 2f
            val d = ((ccy - cy) / r).coerceIn(-1.4f, 1.4f)
            val dd = d * d
            val s = (1f - 0.32f * dd * dd - 0.06f * dd).coerceIn(0.4f, 1f)
            c.scaleX = s
            c.scaleY = s
            c.translationX = (cx - (c.left + c.right) / 2f) * (1f - s) * 0.55f
            c.alpha = (1.2f - 0.75f * dd).coerceIn(0.15f, 1f)
        }
    }

    /** Долгое нажатие на «Избранное» — открыть настройки на телефоне (там же можно скрыть/показать значок). */
    private fun openPhoneSettings() {
        val i = android.content.Intent(android.content.Intent.ACTION_VIEW)
            .addCategory(android.content.Intent.CATEGORY_BROWSABLE)
            .setData(android.net.Uri.parse("opw3photos://settings"))
        runCatching { androidx.wear.remote.interactions.RemoteActivityHelper(this).startRemoteActivity(i) }
        android.widget.Toast.makeText(this, "Открыто на телефоне", android.widget.Toast.LENGTH_SHORT).show()
    }

    // ---------------- данные ----------------
    private fun refresh() {
        val list = PhotoRepo.photos
        val pending = PhotoRepo.pending
        gridAdapter.subtitle = when {
            pending > 0 -> "Синхронизация…"
            PhotoRepo.phoneConnected == false && list.isEmpty() -> "Телефон не подключён"
            list.isEmpty() -> ""
            else -> plural(list.size)
        }
        val changed = gridAdapter.items.map { it.key to it.hasOrig } != list.map { it.key to it.hasOrig }
        gridAdapter.items = list
        if (changed) gridAdapter.notifyDataSetChanged() else gridAdapter.notifyItemChanged(0)

        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        empty.text = if (PhotoRepo.phoneConnected == false)
            "Нет связи с телефоном.\nФото появятся, когда часы подключатся."
        else "Поставьте ♥ фото в Галерее на телефоне — оно появится здесь"

        if (viewerOpen) {
            val curId = pagerAdapter.items.getOrNull(pager.currentItem)?.id
            if (changed) {
                pagerAdapter.items = list
                pagerAdapter.notifyDataSetChanged()
                val idx = list.indexOfFirst { it.id == curId }
                if (idx < 0) { closeViewer(); return }
                if (idx != pager.currentItem) pager.setCurrentItem(idx, false)
                dots.count = list.size
            }
            updateInfo()
        }
        grid.post { applyLens() }
    }

    private fun plural(n: Int) = "$n фото"


    // ---------------- просмотрщик ----------------

    private fun setupViewer() {
        pagerAdapter = PagerAdapter(this)
        pager.adapter = pagerAdapter
        pager.offscreenPageLimit = 1
        (pager.getChildAt(0) as? RecyclerView)?.apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            setItemViewCacheSize(2)
        }
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrolled(position: Int, positionOffset: Float, px: Int) {
                dots.current = position + positionOffset
            }

            override fun onPageSelected(position: Int) {
                // соседние страницы возвращаем к исходному масштабу
                val rv = pager.getChildAt(0) as RecyclerView
                for (i in 0 until rv.childCount) {
                    val v = rv.getChildAt(i) as? ZoomImageView ?: continue
                    if (rv.getChildAdapterPosition(v) != position) v.resetZoom()
                }
                updateInfo()
                showChrome()
            }
        })
        info.alpha = 0f
        dots.alpha = 0f
    }

    private fun currentPage(): ZoomImageView? {
        val rv = pager.getChildAt(0) as? RecyclerView ?: return null
        return (rv.findViewHolderForAdapterPosition(pager.currentItem) as? PagerAdapter.Page)?.v
    }

    private fun openViewer(index: Int, cell: View) {
        if (viewerOpen || animating) return
        val items = gridAdapter.items
        val m = items.getOrNull(index) ?: return
        // Ждём превью (обычно уже в кэше), чтобы анимация шла с картинкой
        val start = SystemClock.uptimeMillis()
        Images.preview(m) {
            if (SystemClock.uptimeMillis() - start < 400) doOpen(index, cell) else doOpen(index, null)
        }
    }

    private fun doOpen(index: Int, cell: View?) {
        if (viewerOpen) return
        viewerOpen = true
        back.isEnabled = true
        outerSwipe.isSwipeable = false
        pagerAdapter.items = gridAdapter.items
        pagerAdapter.notifyDataSetChanged()
        pager.setCurrentItem(index, false)
        dots.count = pagerAdapter.items.size
        dots.current = index.toFloat()
        updateInfo()

        innerSwipe.visibility = View.VISIBLE
        innerSwipe.translationX = 0f
        innerSwipe.alpha = 1f
        animating = true
        if (cell != null && cell.isAttachedToWindow) {
            val (sx, sy, tx, ty) = cellTransform(cell)
            innerSwipe.pivotX = 0f; innerSwipe.pivotY = 0f
            innerSwipe.scaleX = sx; innerSwipe.scaleY = sy
            innerSwipe.translationX = tx; innerSwipe.translationY = ty
            innerSwipe.alpha = 0.4f
        } else {
            innerSwipe.pivotX = root.width / 2f; innerSwipe.pivotY = root.height / 2f
            innerSwipe.scaleX = 0.85f; innerSwipe.scaleY = 0.85f; innerSwipe.alpha = 0f
        }
        innerSwipe.animate().scaleX(1f).scaleY(1f).translationX(0f).translationY(0f).alpha(1f)
            .setDuration(300).setInterpolator(ease)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    animating = false
                    showChrome()
                }
            }).start()
        grid.animate().scaleX(1.08f).scaleY(1.08f).alpha(0.3f).setDuration(300).setInterpolator(ease).start()
    }

    private data class T4(val a: Float, val b: Float, val c: Float, val d: Float)

    private fun cellTransform(cell: View): T4 {
        val loc = IntArray(2)
        val rl = IntArray(2)
        cell.getLocationInWindow(loc)
        root.getLocationInWindow(rl)
        val w = cell.width * cell.scaleX
        val h = cell.height * cell.scaleY
        val left = loc[0] - rl[0].toFloat()
        val top = loc[1] - rl[1].toFloat()
        return T4(w / root.width, h / root.height, left, top)
    }

    private fun closeViewer() {
        if (!viewerOpen || animating) return
        val index = pager.currentItem
        glm.scrollToPositionWithOffset(index + 1, grid.height / 3)
        grid.animate().cancel()
        grid.scaleX = 1f; grid.scaleY = 1f
        grid.post {
            applyLens()
            val cell = glm.findViewByPosition(index + 1)
            animating = true
            val a = innerSwipe.animate().setDuration(260).setInterpolator(ease)
            if (cell != null) {
                val (sx, sy, tx, ty) = cellTransform(cell)
                innerSwipe.pivotX = 0f; innerSwipe.pivotY = 0f
                a.scaleX(sx).scaleY(sy).translationX(tx).translationY(ty).alpha(0f)
            } else {
                innerSwipe.pivotX = root.width / 2f; innerSwipe.pivotY = root.height / 2f
                a.scaleX(0.8f).scaleY(0.8f).alpha(0f)
            }
            a.setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    animating = false
                    hideViewerNow()
                }
            }).start()
            grid.alpha = 0.3f
            grid.animate().alpha(1f).setDuration(260).setListener(null).start()
        }
    }

    private fun hideViewerNow() {
        viewerOpen = false
        back.isEnabled = false
        outerSwipe.isSwipeable = true
        innerSwipe.animate().setListener(null).cancel()
        innerSwipe.visibility = View.GONE
        innerSwipe.scaleX = 1f; innerSwipe.scaleY = 1f
        innerSwipe.translationX = 0f; innerSwipe.translationY = 0f
        innerSwipe.alpha = 1f
        grid.animate().cancel()
        grid.scaleX = 1f; grid.scaleY = 1f; grid.alpha = 1f
        pagerAdapter.items = emptyList()
        pagerAdapter.notifyDataSetChanged()
        info.alpha = 0f; dots.alpha = 0f
        grid.post { applyLens() }
    }

    private fun updateInfo() {
        val m = pagerAdapter.items.getOrNull(pager.currentItem) ?: return
        info.text = if (m.hasOrig) dateFmt.format(m.taken) else dateFmt.format(m.taken) + " · загрузка оригинала…"
    }

    private val hideChrome = Runnable {
        info.animate().alpha(0f).setDuration(400).start()
        dots.animate().alpha(0f).setDuration(400).start()
    }

    private fun showChrome() {
        root.removeCallbacks(hideChrome)
        if (currentPage()?.isZoomed() == true) { hideChrome.run(); return }
        info.animate().alpha(1f).setDuration(180).start()
        dots.animate().alpha(1f).setDuration(180).start()
        root.postDelayed(hideChrome, 2500)
    }

    // ZoomImageView.Callbacks
    override fun onSingleTap() {
        if (info.alpha > 0.5f) hideChrome.run() else showChrome()
    }

    override fun onZoomChanged(view: ZoomImageView, zoomed: Boolean) {
        if (view !== currentPage()) return
        pager.isUserInputEnabled = !zoomed
        if (zoomed) { root.removeCallbacks(hideChrome); hideChrome.run() }
    }

    override fun onCrownExit() {
        closeViewer()
    }

    // ---------------- колёсико ----------------

    private var gridScrollLeft = 0f
    private var gridScrolling = false
    private var gridLast = 0L

    private val gridScrollFrame = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            val dt = if (gridLast == 0L) 16f else (now - gridLast).coerceIn(1, 64).toFloat()
            gridLast = now
            val step = gridScrollLeft * (1f - exp(-dt / 55f))
            val px = if (abs(gridScrollLeft) < 1.5f) gridScrollLeft.roundToInt() else step.roundToInt()
            if (px != 0) {
                grid.scrollBy(0, px)
                gridScrollLeft -= px
            }
            if (abs(gridScrollLeft) >= 1f && px != 0) grid.postOnAnimation(this)
            else { gridScrollLeft = 0f; gridScrolling = false }
        }
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_SCROLL && ev.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
            val px = -ev.getAxisValue(MotionEvent.AXIS_SCROLL) *
                ViewConfiguration.get(this).scaledVerticalScrollFactor
            if (viewerOpen) {
                if (!animating) currentPage()?.onRotary(px)
            } else {
                gridScrollLeft += px
                if (!gridScrolling) {
                    gridScrolling = true
                    gridLast = 0L
                    grid.postOnAnimation(gridScrollFrame)
                }
            }
            return true
        }
        return super.dispatchGenericMotionEvent(ev)
    }

}
