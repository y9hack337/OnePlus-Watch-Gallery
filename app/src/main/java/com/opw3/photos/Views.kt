package com.opw3.photos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import kotlin.math.min

/** Точки-индикатор страниц (окно до 7 точек, крайние уменьшаются). */
class PageDots @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = resources.displayMetrics.density
    var count = 0; set(v) { field = v; invalidate() }
    var current = 0f; set(v) { field = v; invalidate() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((7 * 11 * d).toInt(), (10 * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        if (count <= 1) return
        val gap = 11 * d
        val cx = width / 2f
        val cy = height / 2f
        val first = (current - 3).toInt().coerceIn(0, maxOf(0, count - 7))
        val last = min(count - 1, first + 6)
        val shown = last - first + 1
        for (i in first..last) {
            val x = cx + (i - first - (shown - 1) / 2f) * gap
            val dist = abs(i - current)
            val sel = (1f - dist).coerceIn(0f, 1f)
            val edge = if ((i == first && first > 0) || (i == last && last < count - 1)) 0.6f else 1f
            p.color = blend(0x66FFFFFF, 0xFFFFFFFF.toInt(), sel)
            c.drawCircle(x, cy, (2.6f + 1.2f * sel) * d * edge, p)
        }
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = (((a shr s) and 0xFF) + (((b shr s) and 0xFF) - ((a shr s) and 0xFF)) * t).toInt() shl s
        return ch(24) or ch(16) or ch(8) or ch(0)
    }
}

/** Квадратная ячейка с закруглёнными углами. */
class SquareImageView(ctx: Context) : ImageView(ctx) {
    init {
        scaleType = ScaleType.CENTER_CROP
        setBackgroundColor(0xFF1E1E1E.toInt())
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) =
                outline.setRoundRect(0, 0, view.width, view.height, view.width * 0.16f)
        }
        clipToOutline = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }
}

class GridAdapter(private val onLongHeader: () -> Unit, private val onClick: (Int, View) -> Unit) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    var items: List<PhotoMeta> = emptyList()
    var title = "Избранное"
    var subtitle = ""

    init { setHasStableIds(true) }

    override fun getItemCount() = items.size + 1
    override fun getItemViewType(position: Int) = if (position == 0) 0 else 1
    override fun getItemId(position: Int) = if (position == 0) Long.MIN_VALUE else items[position - 1].id

    class Header(v: LinearLayout, val t: TextView, val s: TextView) : RecyclerView.ViewHolder(v)
    class Cell(val iv: SquareImageView) : RecyclerView.ViewHolder(iv)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val ctx = parent.context
        val dp = ctx.resources.displayMetrics.density
        if (viewType == 0) {
            val t = TextView(ctx).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(0xFFFFFFFF.toInt())
                gravity = Gravity.CENTER
            }
            val s = TextView(ctx).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(0xFFAAAAAA.toInt())
                gravity = Gravity.CENTER
            }
            val l = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, (8 * dp).toInt())
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(t); addView(s)
                setOnLongClickListener { onLongHeader(); true }
            }
            return Header(l, t, s)
        }
        val iv = SquareImageView(ctx).apply {
            val m = (3 * dp).toInt()
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(m, m, m, m) }
        }
        val h = Cell(iv)
        iv.setOnClickListener { val p = h.bindingAdapterPosition; if (p > 0) onClick(p - 1, iv) }
        return h
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is Header -> {
                holder.t.text = title
                holder.s.text = subtitle
                holder.s.visibility = if (subtitle.isEmpty()) View.GONE else View.VISIBLE
            }
            is Cell -> {
                val m = items[position - 1]
                holder.iv.tag = m.key
                val cached = Images.cachedThumb(m)
                if (cached != null) holder.iv.setImageBitmap(cached)
                else {
                    holder.iv.setImageDrawable(null)
                    Images.thumb(m) { b: Bitmap? -> if (holder.iv.tag == m.key) holder.iv.setImageBitmap(b) }
                }
            }
        }
    }
}

class PagerAdapter(private val callbacks: ZoomImageView.Callbacks) : RecyclerView.Adapter<PagerAdapter.Page>() {
    var items: List<PhotoMeta> = emptyList()

    init { setHasStableIds(true) }

    class Page(val v: ZoomImageView) : RecyclerView.ViewHolder(v)

    override fun getItemCount() = items.size
    override fun getItemId(position: Int) = items[position].id

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Page(
        ZoomImageView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            this.callbacks = this@PagerAdapter.callbacks
        }
    )

    override fun onBindViewHolder(holder: Page, position: Int) = holder.v.bind(items[position])
    override fun onViewRecycled(holder: Page) = holder.v.unbind()
}
