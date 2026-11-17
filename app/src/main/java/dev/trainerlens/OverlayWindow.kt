package dev.trainerlens

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The overlay's views: a 🔍 bubble and a frosted-glass panel (top bar with IV | Bag switch, the content, and a grip
 * along the bottom edge that resizes the window). It needs only a [Context], so tests can build it; the service adds
 * [root] to the WindowManager and wires up dragging and resizing.
 *
 * The panel always has its full design size ([panelWidthPx] x [panelHeightPx]) and is drawn scaled; the window around
 * it gets exactly the scaled footprint ([footprint]), so touches, the on-screen clamp and the black-out mask all match
 * what is visible.
 */
class OverlayWindow(
    private val ctx: Context,
    val panelWidthPx: Int,
    val bubbleSizePx: Int,
    onIv: () -> Unit, onBag: () -> Unit, onMinimise: () -> Unit, onStop: () -> Unit,
) {
    private fun dp(v: Int) = Ui.dp(ctx, v)
    val panelHeightPx = dp(Metrics.PANEL_H)

    val bubble = TextView(ctx).apply {
        text = "🔍"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); gravity = Gravity.CENTER
        background = Ui.rounded(ctx, Ui.GLASS, 26f, Ui.GLASS_STROKE)
    }

    private fun tabLabel(t: String, onClick: () -> Unit) =
        Ui.label(ctx, 13f, Ui.TEXT2, bold = true, gravity = Gravity.CENTER).apply { text = t; isClickable = true; setOnClickListener { onClick() } }
    private val tabIv = tabLabel("IV", onIv)
    private val tabBag = tabLabel("Bag", onBag)

    /** Drag this to move the window. */
    val topBar: View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        val seg = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ui.rounded(ctx, Ui.CHIP, 11f)
            setPadding(dp(2), dp(2), dp(2), dp(2))
            addView(tabIv, LinearLayout.LayoutParams(dp(46), dp(Metrics.TOP_BAR - 4)))
            addView(tabBag, LinearLayout.LayoutParams(dp(46), dp(Metrics.TOP_BAR - 4)))
        }
        addView(seg)
        addView(View(ctx), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)) // the drag area
        addView(Ui.button(ctx, "–", 4) { onMinimise() }, LinearLayout.LayoutParams(dp(34), dp(Metrics.TOP_BAR)).apply { marginEnd = dp(5) })
        addView(Ui.button(ctx, "Stop", 4) { onStop() }, LinearLayout.LayoutParams(dp(46), dp(Metrics.TOP_BAR)))
    }

    val contentFrame = FrameLayout(ctx).apply {
        background = Ui.rounded(ctx, Ui.WELL, 16f)
        setPadding(dp(Metrics.CONTENT_PAD), dp(Metrics.CONTENT_PAD), dp(Metrics.CONTENT_PAD), dp(Metrics.CONTENT_PAD))
    }

    /** The bottom strip of the window: drag it up or down to resize the whole window. */
    val grip: View = FrameLayout(ctx).apply {
        contentDescription = "Drag to resize"
        addView(View(ctx).apply { background = Ui.rounded(ctx, Ui.CHIP_ACTIVE, 2f) },
            FrameLayout.LayoutParams(dp(36), dp(4), Gravity.CENTER))
    }

    val panel: LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = Ui.rounded(ctx, Ui.GLASS, 22f, Ui.GLASS_STROKE)
        setPadding(dp(Metrics.WINDOW_PAD), dp(Metrics.WINDOW_PAD), dp(Metrics.WINDOW_PAD), 0)
        addView(topBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(Metrics.TOP_BAR)))
        addView(contentFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(Metrics.CONTENT_H)).apply { topMargin = dp(Metrics.GAP) })
        addView(grip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)) // takes what is left of the fixed height
        visibility = View.GONE
    }

    val root: FrameLayout = FrameLayout(ctx).apply {
        addView(bubble, FrameLayout.LayoutParams(bubbleSizePx, bubbleSizePx))
        addView(panel, FrameLayout.LayoutParams(panelWidthPx, panelHeightPx)) // exact: never measured shorter than designed
    }

    /** Shows [content] in the panel and highlights its tab. */
    fun showTab(content: View, bagActive: Boolean) {
        contentFrame.removeAllViews()
        contentFrame.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        style(tabIv, !bagActive); style(tabBag, bagActive)
    }

    private fun style(v: TextView, active: Boolean) {
        v.setTextColor(if (active) Ui.TEXT else Ui.TEXT2)
        v.background = if (active) Ui.rounded(ctx, Ui.CHIP_ACTIVE, 9f) else null
    }

    fun setOpen(open: Boolean) {
        bubble.visibility = if (open) View.GONE else View.VISIBLE
        panel.visibility = if (open) View.VISIBLE else View.GONE
    }

    fun applyScale(s: Float) {
        panel.pivotX = 0f; panel.pivotY = 0f; panel.scaleX = s; panel.scaleY = s
    }

    /** Window size in pixels: the bubble, or the panel at scale [s]. */
    fun footprint(open: Boolean, s: Float): Pair<Int, Int> =
        if (open) (panelWidthPx * s).toInt() to (panelHeightPx * s).toInt() else bubbleSizePx to bubbleSizePx
}
