package com.opw3.photos

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.OverScroller
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Просмотр одного фото: плавный зум колёсиком/щипком/двойным тапом, инерционная прокрутка
 * и подгрузка тайлов из оригинала (BitmapRegionDecoder) — видно каждый пиксель исходника,
 * но в памяти только видимая часть.
 */
class ZoomImageView(context: Context) : View(context) {

    interface Callbacks {
        fun onSingleTap()
        fun onZoomChanged(view: ZoomImageView, zoomed: Boolean)
        fun onCrownExit()
    }

    var callbacks: Callbacks? = null
    var meta: PhotoMeta? = null; private set

    private var preview: Bitmap? = null
    private var rawW = 1
    private var rawH = 1
    private val rotM = Matrix()
    private val m = Matrix()
    private val inv = Matrix()
    private var dispW = 1f
    private var dispH = 1f

    private var scale = 1f
    private var tx = 0f
    private var ty = 0f
    private var minScale = 1f
    private var baseScale = 1f
    private var maxScale = 4f
    private var wasZoomed = false

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val dst = RectF()
    private val tmp = RectF()

    // ---------------- тайлы ----------------
    private var decoder: BitmapRegionDecoder? = null
    private var decoderState = 0 // 0 нет, 1 открывается, 2 готов, 3 ошибка
    private val tiles = HashMap<Long, Bitmap>()
    private val pendingTiles = HashSet<Long>()
    @Volatile private var wanted: Set<Long> = emptySet()
    private var generation = 0
    private var level = 0

    // ---------------- жесты ----------------
    private val scroller = OverScroller(context)
    private var anim: ValueAnimator? = null
    private var scaling = false

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            stopCrown()
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scaling || !isZoomed()) return false
            panBy(-dx, -dy)
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (scaling || !isZoomed()) return false
            val w = dispW * scale
            val h = dispH * scale
            val minX = if (w > width) (width - w).roundToInt() else tx.roundToInt()
            val maxX = if (w > width) 0 else tx.roundToInt()
            val minY = if (h > height) (height - h).roundToInt() else ty.roundToInt()
            val maxY = if (h > height) 0 else ty.roundToInt()
            scroller.fling(tx.roundToInt(), ty.roundToInt(), vx.roundToInt(), vy.roundToInt(), minX, maxX, minY, maxY)
            postInvalidateOnAnimation()
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            callbacks?.onSingleTap(); return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val target = if (scale > baseScale * 1.15f) baseScale else min(maxScale, baseScale * 2.6f)
            animateZoom(target, e.x, e.y)
            return true
        }
    })

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
            scaling = true
            stopCrown(); anim?.cancel()
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }

        override fun onScale(d: ScaleGestureDetector): Boolean {
            val target = (scale * d.scaleFactor).coerceIn(minScale * 0.7f, maxScale * 1.3f)
            zoomTo(target, d.focusX, d.focusY)
            return true
        }

        override fun onScaleEnd(d: ScaleGestureDetector) {
            scaling = false
            settle(d.focusX, d.focusY)
        }
    }).apply { isQuickScaleEnabled = false }

    init {
        isFocusable = false
    }

    // ---------------- данные ----------------

    fun bind(newMeta: PhotoMeta) {
        val old = meta
        if (old != null && old.key == newMeta.key) {
            val origArrived = !old.hasOrig && newMeta.hasOrig
            meta = newMeta
            if (origArrived) invalidate()
            return
        }
        unbind()
        meta = newMeta
        rawW = max(1, newMeta.w)
        rawH = max(1, newMeta.h)
        rotM.reset()
        when (newMeta.rot) {
            90 -> { rotM.postRotate(90f); rotM.postTranslate(rawH.toFloat(), 0f) }
            180 -> { rotM.postRotate(180f); rotM.postTranslate(rawW.toFloat(), rawH.toFloat()) }
            270 -> { rotM.postRotate(270f); rotM.postTranslate(0f, rawW.toFloat()) }
        }
        if (newMeta.rot == 90 || newMeta.rot == 270) { dispW = rawH.toFloat(); dispH = rawW.toFloat() }
        else { dispW = rawW.toFloat(); dispH = rawH.toFloat() }
        computeScales(reset = true)
        val cached = Images.cachedPreview(newMeta)
        if (cached != null) preview = cached
        else Images.preview(newMeta) { b -> if (meta?.key == newMeta.key) { preview = b; invalidate() } }
        invalidate()
    }

    fun unbind() {
        anim?.cancel(); scroller.forceFinished(true); stopCrown()
        generation++
        wanted = emptySet()
        tiles.clear(); pendingTiles.clear()
        val d = decoder
        decoder = null; decoderState = 0
        if (d != null) tileExec.execute { d.recycle() }
        preview = null
        meta = null
    }

    fun resetZoom() {
        anim?.cancel(); stopCrown(); scroller.forceFinished(true)
        computeScales(reset = true)
        invalidate()
    }

    fun isZoomed() = scale > baseScale * 1.04f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        computeScales(reset = true)
    }

    private fun computeScales(reset: Boolean) {
        if (width == 0 || height == 0) return
        val vw = width.toFloat()
        val vh = height.toFloat()
        baseScale = min(vw / dispW, vh / dispH)                    // вписать в квадрат экрана
        minScale = if (resources.configuration.isScreenRound)
            min(baseScale, min(vw, vh) / hypot(dispW, dispH))       // вписать целиком в круг
        else baseScale
        maxScale = max(baseScale * 3f, 1.6f)                       // до 1.6 экранного пикселя на пиксель оригинала
        if (reset) {
            scale = baseScale
            tx = (vw - dispW * scale) / 2f
            ty = (vh - dispH * scale) / 2f
        }
        clampAndUpdate()
    }

    // ---------------- трансформация ----------------

    private fun clampAndUpdate() {
        val w = dispW * scale
        val h = dispH * scale
        tx = if (w <= width) (width - w) / 2f else tx.coerceIn(width - w, 0f)
        ty = if (h <= height) (height - h) / 2f else ty.coerceIn(height - h, 0f)
        m.set(rotM)
        m.postScale(scale, scale)
        m.postTranslate(tx, ty)
        m.invert(inv)
        val z = isZoomed()
        if (z != wasZoomed) {
            wasZoomed = z
            callbacks?.onZoomChanged(this, z)
        }
    }

    private fun zoomTo(newScale: Float, fx: Float, fy: Float) {
        val f = newScale / scale
        tx = fx - (fx - tx) * f
        ty = fy - (fy - ty) * f
        scale = newScale
        clampAndUpdate()
        invalidate()
    }

    private fun panBy(dx: Float, dy: Float) {
        tx += dx; ty += dy
        clampAndUpdate()
        invalidate()
    }

    private fun settle(fx: Float, fy: Float) {
        when {
            scale < minScale -> animateZoom(minScale, width / 2f, height / 2f)
            scale > maxScale -> animateZoom(maxScale, fx, fy)
        }
    }

    private fun animateZoom(target: Float, fx: Float, fy: Float) {
        anim?.cancel(); stopCrown()
        val from = scale
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener {
                val t = it.animatedValue as Float
                // интерполяция в логарифмической шкале — зум выглядит равномерным
                zoomTo(exp(ln(from) + (ln(target) - ln(from)) * t), fx, fy)
            }
            start()
        }
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            tx = scroller.currX.toFloat()
            ty = scroller.currY.toFloat()
            clampAndUpdate()
            postInvalidateOnAnimation()
        }
    }

    // ---------------- колёсико ----------------

    private var crownTarget = Float.NaN
    private var crownRunning = false
    private var lastFrame = 0L
    private var exitPull = 0f
    private var lastCrownEvent = 0L

    /** px > 0 — вращение «вниз» (по часовой) = приблизить. */
    fun onRotary(px: Float) {
        anim?.cancel(); scroller.forceFinished(true)
        lastCrownEvent = SystemClock.uptimeMillis()
        if (crownTarget.isNaN()) crownTarget = scale

        // Отдаление за минимум: «резинка», а затем выход в сетку — как на Apple Watch
        if (px < 0 && crownTarget <= minScale * 1.001f && scale <= minScale * 1.02f) {
            exitPull += -px
            if (exitPull > EXIT_PULL_PX) {
                exitPull = 0f
                callbacks?.onCrownExit()
                return
            }
            invalidate()
            postDelayed(pullRelease, 220)
            return
        }
        if (px > 0) exitPull = 0f

        crownTarget = (crownTarget * exp(px / CROWN_PX_PER_E)).coerceIn(minScale, maxScale)
        if (!crownRunning) {
            crownRunning = true
            lastFrame = 0L
            postOnAnimation(crownFrame)
        }
    }

    private val pullRelease = Runnable {
        if (SystemClock.uptimeMillis() - lastCrownEvent >= 200 && exitPull > 0f) {
            ValueAnimator.ofFloat(exitPull, 0f).apply {
                duration = 220
                interpolator = DecelerateInterpolator()
                addUpdateListener { exitPull = it.animatedValue as Float; invalidate() }
                start()
            }
        }
    }

    private val crownFrame = object : Runnable {
        override fun run() {
            if (!crownRunning || crownTarget.isNaN()) { crownRunning = false; return }
            val now = SystemClock.uptimeMillis()
            val dt = if (lastFrame == 0L) 16f else (now - lastFrame).coerceIn(1, 64).toFloat()
            lastFrame = now
            val a = 1f - exp(-dt / CROWN_SMOOTH_MS)
            val ns = exp(ln(scale) + (ln(crownTarget) - ln(scale)) * a)
            if (abs(crownTarget - ns) / ns < 0.0015f) {
                zoomTo(crownTarget, width / 2f, height / 2f)
                crownRunning = false
                crownTarget = Float.NaN
            } else {
                zoomTo(ns, width / 2f, height / 2f)
                postOnAnimation(this)
            }
        }
    }

    private fun stopCrown() {
        crownRunning = false
        crownTarget = Float.NaN
        removeCallbacks(crownFrame)
    }

    // ---------------- касания ----------------

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> parent?.requestDisallowInterceptTouchEvent(isZoomed())
            MotionEvent.ACTION_POINTER_DOWN -> parent?.requestDisallowInterceptTouchEvent(true)
        }
        scaleDetector.onTouchEvent(e)
        gestures.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (!scaling && (scale < minScale || scale > maxScale)) settle(e.x, e.y)
        }
        return true
    }

    override fun canScrollHorizontally(direction: Int): Boolean = isZoomed()

    // ---------------- отрисовка ----------------

    override fun onDraw(canvas: Canvas) {
        val bmp = preview ?: return
        val pull = (exitPull / EXIT_PULL_PX).coerceIn(0f, 1f)
        val save = canvas.save()
        if (pull > 0f) {
            val s = 1f - 0.22f * pull
            canvas.scale(s, s, width / 2f, height / 2f)
        }
        canvas.concat(m)
        dst.set(0f, 0f, rawW.toFloat(), rawH.toFloat())
        canvas.drawBitmap(bmp, null, dst, paint)
        if (tiles.isNotEmpty()) drawTiles(canvas)
        canvas.restoreToCount(save)
        updateTiles(bmp)
    }

    private fun drawTiles(canvas: Canvas) {
        visibleRaw(tmp)
        // сначала грубые уровни, потом точные
        val sorted = tiles.entries.sortedByDescending { levelOf(it.key) }
        for ((k, b) in sorted) {
            val s = levelOf(k)
            if (s < level) continue
            val r = tileRect(s, colOf(k), rowOf(k))
            if (!RectF.intersects(tmp, RectF(r))) continue
            dst.set(r)
            canvas.drawBitmap(b, null, dst, paint)
        }
    }

    private fun visibleRaw(out: RectF) {
        out.set(0f, 0f, width.toFloat(), height.toFloat())
        inv.mapRect(out)
        if (!out.intersect(0f, 0f, rawW.toFloat(), rawH.toFloat())) out.setEmpty()
    }

    private fun updateTiles(bmp: Bitmap) {
        val meta = meta ?: return
        val rawPerScreen = 1f / scale
        val previewRawPerPx = rawW.toFloat() / bmp.width
        if (!meta.hasOrig || previewRawPerPx <= rawPerScreen * 1.15f || decoderState == 3) {
            if (level != 0) { level = 0; wanted = emptySet(); trimTiles(emptySet()) }
            return
        }
        var s = 1
        while (s * 2 <= rawPerScreen) s *= 2
        level = s
        if (decoderState == 0) openDecoder(meta)
        if (decoderState != 2) return

        visibleRaw(tmp)
        if (tmp.isEmpty) return
        val t = TILE * s
        val need = HashSet<Long>()
        for (r in (tmp.top / t).toInt()..((tmp.bottom - 1) / t).toInt())
            for (c in (tmp.left / t).toInt()..((tmp.right - 1) / t).toInt())
                need += key(s, c, r)
        wanted = need
        for (k in need) if (k !in tiles && k !in pendingTiles) requestTile(k)
        trimTiles(need)
    }

    private fun trimTiles(need: Set<Long>) {
        if (tiles.size <= MAX_TILES && need.isNotEmpty()) return
        val it = tiles.entries.iterator()
        while (it.hasNext()) {
            val (k, b) = it.next()
            val s = levelOf(k)
            if (k !in need && (need.isEmpty() || s < level || s > level * 2 || tiles.size > MAX_TILES)) {
                it.remove() // без recycle(): битмап может ещё быть в display list
            }
        }
    }

    private fun openDecoder(meta: PhotoMeta) {
        decoderState = 1
        val gen = generation
        val path = PhotoRepo.origFile(meta.id).path
        tileExec.execute {
            val d = runCatching {
                @Suppress("DEPRECATION")
                if (Build.VERSION.SDK_INT >= 31) BitmapRegionDecoder.newInstance(path)
                else BitmapRegionDecoder.newInstance(path, false)
            }.onFailure { Log.w("OPW3Watch", "region decoder: ${it.message}") }.getOrNull()
            post {
                if (gen != generation) { d?.let { x -> tileExec.execute { x.recycle() } }; return@post }
                decoder = d
                decoderState = if (d != null) 2 else 3
                invalidate()
            }
        }
    }

    private fun requestTile(k: Long) {
        val d = decoder ?: return
        val gen = generation
        pendingTiles += k
        val s = levelOf(k)
        val rect = tileRect(s, colOf(k), rowOf(k))
        tileExec.execute {
            var b: Bitmap? = null
            if (gen == generation && k in wanted && !d.isRecycled) {
                b = runCatching {
                    d.decodeRegion(rect, BitmapFactory.Options().apply { inSampleSize = s })
                }.getOrNull()?.also { it.prepareToDraw() }
            }
            post {
                pendingTiles -= k
                if (b != null && gen == generation) { tiles[k] = b; invalidate() } else b?.recycle()
            }
        }
    }

    private fun tileRect(s: Int, c: Int, r: Int): Rect {
        val t = TILE * s
        return Rect(c * t, r * t, min((c + 1) * t, rawW), min((r + 1) * t, rawH))
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopCrown()
    }

    companion object {
        private const val TILE = 512
        private const val MAX_TILES = 30
        private const val CROWN_PX_PER_E = 520f   // столько пикселей прокрутки колёсика = зум в e раз
        private const val CROWN_SMOOTH_MS = 65f
        private const val EXIT_PULL_PX = 260f

        private val tileExec = Executors.newSingleThreadExecutor { r -> Thread(r, "tiles") }

        private fun key(s: Int, c: Int, r: Int) = (s.toLong() shl 48) or (c.toLong() shl 24) or r.toLong()
        private fun levelOf(k: Long) = (k ushr 48).toInt()
        private fun colOf(k: Long) = ((k ushr 24) and 0xFFFFFF).toInt()
        private fun rowOf(k: Long) = (k and 0xFFFFFF).toInt()
    }
}
