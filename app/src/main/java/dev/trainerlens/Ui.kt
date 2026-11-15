package dev.trainerlens

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.TextView

/** Colours, sizes and small view helpers shared by the overlay. Everything is in dp / sp, never raw pixels. */
object Ui {
    val TEXT = 0xFFF5F6F8.toInt()
    val TEXT2 = 0xB3FFFFFF.toInt()      // 70 % white
    val TEXT3 = 0x80FFFFFF.toInt()      // 50 % white
    val GLASS = 0xD1171A21.toInt()      // translucent near-black, the window
    const val GLASS_STROKE = 0x33FFFFFF // hairline light edge
    const val WELL = 0x14FFFFFF         // content area inside the window
    const val CHIP = 0x1FFFFFFF         // buttons, pills, rows
    const val CHIP_ACTIVE = 0x38FFFFFF  // selected tab
    const val TRACK = 0x24FFFFFF        // empty part of a bar / ring
    val GREEN = 0xFF4ADE80.toInt()
    val AMBER = 0xFFFBBF24.toInt()
    val RED = 0xFFF87171.toInt()
    val GREY = 0xFF9CA3AF.toInt()

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()
    fun dpf(ctx: Context, v: Float): Float = v * ctx.resources.displayMetrics.density

    fun rounded(ctx: Context, fill: Int, radiusDp: Float, stroke: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setShape(GradientDrawable.RECTANGLE)
            cornerRadius = dpf(ctx, radiusDp)
            setColor(fill)
            if (stroke != 0) setStroke(maxOf(1, dp(ctx, 1)), stroke)
        }

    /** A single-line, ellipsized label. */
    fun label(
        ctx: Context, sp: Float, color: Int, bold: Boolean = false,
        gravity: Int = Gravity.START or Gravity.CENTER_VERTICAL,
    ): TextView = TextView(ctx).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.DEFAULT
        includeFontPadding = false
        this.gravity = gravity
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    /** A pill-shaped tap target: no Android Button chrome, so it looks the same on every phone. */
    fun button(ctx: Context, text: String, padH: Int = 10, onClick: () -> Unit): TextView =
        label(ctx, 12f, TEXT, gravity = Gravity.CENTER).apply {
            this.text = text
            background = rounded(ctx, CHIP, 10f)
            setPadding(dp(ctx, padH), 0, dp(ctx, padH), 0)
            isClickable = true
            setOnClickListener { onClick() }
        }
}

/** A thin rounded progress bar: [fill] is solid, [ext] (>= fill) is the lighter "could be up to" part. */
class BarView(ctx: Context) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var fill = 0f
    private var ext = 0f
    private var color = Ui.GREY

    fun set(fill: Float, ext: Float, color: Int) {
        val f = fill.coerceIn(0f, 1f); val e = ext.coerceIn(f, 1f)
        if (f == this.fill && e == this.ext && color == this.color) return
        this.fill = f; this.ext = e; this.color = color; invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val r = h / 2f
        paint.color = Ui.TRACK
        rect.set(0f, 0f, w, h); canvas.drawRoundRect(rect, r, r, paint)
        if (ext > fill) {
            paint.color = (color and 0x00FFFFFF) or 0x66000000
            rect.set(0f, 0f, w * ext, h); canvas.drawRoundRect(rect, r, r, paint)
        }
        if (fill > 0f) {
            paint.color = color
            rect.set(0f, 0f, maxOf(w * fill, h), h); canvas.drawRoundRect(rect, r, r, paint)
        }
    }
}

/** The big IV percentage ring. */
class RingView(ctx: Context) : View(ctx) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Ui.TRACK }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; color = Ui.TEXT; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = Ui.TEXT2 }
    private val rect = RectF()
    private var fraction = 0f
    private var bigText = ""
    private var smallText = ""

    fun set(fraction: Float, color: Int, bigText: String, smallText: String) {
        this.fraction = fraction; this.bigText = bigText; this.smallText = smallText
        arc.color = color; invalidate()
    }

    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val sw = 5f * d
        track.strokeWidth = sw; arc.strokeWidth = sw
        val pad = sw / 2f + d
        rect.set(pad, pad, width - pad, height - pad)
        canvas.drawArc(rect, 0f, 360f, false, track)
        canvas.drawArc(rect, -90f, 360f * fraction.coerceIn(0f, 1f), false, arc)
        big.textSize = sp(if (bigText.length > 4) 12f else 17f)
        small.textSize = sp(10f)
        val cx = width / 2f; val cy = height / 2f
        canvas.drawText(bigText, cx, cy + 1f * d, big)
        canvas.drawText(smallText, cx, cy + 13f * d, small)
    }
}
