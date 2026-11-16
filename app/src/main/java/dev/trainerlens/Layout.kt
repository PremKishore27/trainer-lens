package dev.trainerlens

import kotlin.math.ceil
import kotlin.math.floor

/**
 * Fixed sizes of the overlay, in dp. They are plain numbers so unit tests can check that every part fits and that the
 * window stays out of the screen areas the parsers need.
 */
object Metrics {
    const val PANEL_W = 280
    const val WINDOW_PAD = 8
    const val TOP_BAR = 28
    const val GAP = 6
    const val CONTENT_PAD = 10
    const val CONTENT_H = 196
    const val PANEL_H = WINDOW_PAD + TOP_BAR + GAP + CONTENT_H + WINDOW_PAD
    const val INNER_H = CONTENT_H - 2 * CONTENT_PAD

    // IV card, top to bottom
    const val IV_HEADER = 22
    const val IV_GAP = 4
    const val RING = 56
    const val BAR_ROW = 18
    const val CHIP_ROW = 22
    const val LEAGUE_ROW = 18
    const val IV_FOOTER = 22
    const val IV_RESULT = RING + IV_GAP + CHIP_ROW + IV_GAP + 2 * LEAGUE_ROW
    const val IV_NEEDED = IV_HEADER + IV_GAP + IV_RESULT + IV_GAP + IV_FOOTER
    /** The area a message card (with or without a drawing) has inside the IV card. */
    const val IV_BODY = INNER_H - IV_HEADER - 2 * IV_GAP - IV_FOOTER
    const val ART = 40
    const val ART_GAP = 6
    const val MESSAGE_LINE = 16   // one 12 sp line with spacing
    const val MESSAGE_LINES_WITH_ART = 3

    // Bag panel, top to bottom (the list takes what is left)
    const val BAG_HEADER = 22
    const val BAG_BAR = 4
    const val BAG_ADVICE = 16
    const val BAG_BUTTONS = 28
    const val BAG_GAP = 4
    const val BAG_ROW = 28
    const val BAG_FIXED = BAG_HEADER + BAG_GAP + BAG_BAR + BAG_GAP + BAG_ADVICE + BAG_GAP + BAG_GAP + BAG_BUTTONS
    const val BAG_LIST = INNER_H - BAG_FIXED
}

/** The two window sizes the size button switches between (the layout is designed at 1.0). */
object WindowSize {
    const val SMALL = 0.8f
    const val LARGE = 1.1f
}

object OverlayLayout {
    /**
     * The window opens just below the CP line (CP is printed at about 5-9 % of the screen height) and ends above the
     * Pokémon's HP / type lines (about 48 % and below), so it doesn't hide what the IV checker has to read.
     */
    fun panelTop(screenH: Int): Int = (screenH * 0.11).toInt()

    fun panelWidthPx(screenW: Int, density: Float): Int =
        minOf((Metrics.PANEL_W * density + 0.5f).toInt(), screenW - (16 * density + 0.5f).toInt())

    fun panelHeightPx(density: Float): Int = (Metrics.PANEL_H * density + 0.5f).toInt()

    /** The window's footprint on screen at [scale]: what the mask and the on-screen clamp must use. */
    fun panelRect(screenW: Int, screenH: Int, density: Float, scale: Float = 1f): Box {
        val w = (panelWidthPx(screenW, density) * scale).toInt()
        val left = ((screenW - w) / 2).coerceAtLeast(0)
        val top = panelTop(screenH)
        return Box(left, top, left + w, top + (panelHeightPx(density) * scale).toInt())
    }
}

/**
 * The overlay is part of the screen capture, so its area is painted black before text recognition. The capture bitmap
 * is not always the same size as the real display (it is made from the app's display metrics), and the mirror scales
 * the screen to fit it, so the window's rectangle has to go through the same scaling.
 */
object OverlayMask {
    fun map(win: Box, realW: Int, realH: Int, bmpW: Int, bmpH: Int, pad: Int): Box {
        if (realW <= 0 || realH <= 0) return Box(win.left - pad, win.top - pad, win.right + pad, win.bottom + pad).clamp(bmpW, bmpH)
        val s = minOf(bmpW.toDouble() / realW, bmpH.toDouble() / realH)
        val ox = (bmpW - realW * s) / 2.0
        val oy = (bmpH - realH * s) / 2.0
        return Box(
            floor(win.left * s + ox).toInt() - pad, floor(win.top * s + oy).toInt() - pad,
            ceil(win.right * s + ox).toInt() + pad, ceil(win.bottom * s + oy).toInt() + pad,
        ).clamp(bmpW, bmpH)
    }

    private fun Box.clamp(w: Int, h: Int) = Box(left.coerceIn(0, w), top.coerceIn(0, h), right.coerceIn(0, w), bottom.coerceIn(0, h))
}

/** Last overlay / capture geometry, copied into the "Copy log" text so a bad read can be diagnosed. */
object Diag {
    @Volatile var line: String = ""
}
