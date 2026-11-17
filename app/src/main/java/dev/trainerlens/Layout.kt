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
    const val GRIP = 26      // the resize strip along the bottom edge
    const val PANEL_H = WINDOW_PAD + TOP_BAR + GAP + CONTENT_H + GRIP
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

    // IV card in its compact form (small window): ring, three IV rows and one league pill; everything else is hidden
    const val COMPACT_RING = 84
    const val COMPACT_BAR_ROW = 26
    const val COMPACT_PICK = 30
    const val COMPACT_GAP = 8
    const val COMPACT_NEEDED = IV_HEADER + IV_GAP + COMPACT_RING + COMPACT_GAP + COMPACT_PICK

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

/**
 * When the window is small the IV card keeps only what matters at a glance (the % ring, the three IV values and the best
 * league); at the normal size and bigger it shows everything. The switch has a little hysteresis so a finger jittering
 * around the limit while resizing doesn't flip the card back and forth.
 */
object CardMode {
    const val COMPACT_BELOW = 0.9f   // shrinking below this switches to the compact card
    const val FULL_FROM = 0.93f      // growing back to this (or more) switches to the full card
    fun isCompact(scale: Float, wasCompact: Boolean): Boolean =
        if (scale.isNaN()) false else if (wasCompact) scale < FULL_FROM else scale < COMPACT_BELOW
}

/**
 * How big the window is drawn, as a factor of its design size (width follows height). The player drags the bottom edge
 * to change it; these functions keep the result inside sensible limits and on screen.
 */
object OverlayScale {
    const val MIN = 0.65f
    const val MAX = 1.3f
    const val DEFAULT = 1f

    /**
     * [s] limited to [MIN]..[MAX], to the space right of / below the window's corner at ([x], [y]) and to [maxScale]
     * (the IV tab's band, see [OverlayBand]). Never throws.
     */
    fun fit(s: Float, x: Int, y: Int, screenW: Int, screenH: Int, panelWpx: Int, panelHpx: Int, maxScale: Float = MAX): Float {
        if (s.isNaN()) return DEFAULT
        val room = if (panelWpx > 0 && panelHpx > 0)
            minOf((screenW - x).toFloat() / panelWpx, (screenH - y).toFloat() / panelHpx) else MAX
        val hi = maxOf(MIN, minOf(MAX, room, maxScale))   // hi >= MIN, so coerceIn below can't fail near a screen edge
        return s.coerceIn(MIN, hi)
    }

    /** The scale after dragging the bottom edge [dy] pixels, starting from [startScale] at a window height of [startHpx]. */
    fun fromDrag(startScale: Float, startHpx: Int, dy: Float): Float =
        if (startHpx <= 0) startScale else startScale * (startHpx + dy) / startHpx
}

/**
 * The IV checker has to read the CP line (about 5-9 % of the screen height) and the HP line (about 46-48 %). The window
 * is part of the screen capture and is blacked out before reading, so on the IV tab it must not cover them. It can sit
 * in the free band ABOVE the HP line ([TOP]..[ABOVE_END]) or in the area BELOW it ([BELOW_START]..end of screen), and it
 * snaps away from the strip in between. (A real log showed a window covering the HP line: nothing could be read.)
 */
object OverlayBand {
    const val TOP = 0.10f          // below the CP line
    const val ABOVE_END = 0.44f    // above the HP line
    const val BELOW_START = 0.50f  // below the HP line

    fun top(realH: Int) = (realH * TOP).toInt()
    fun aboveEnd(realH: Int) = (realH * ABOVE_END).toInt()
    fun belowStart(realH: Int) = (realH * BELOW_START).toInt()

    /** The vertical positions [y] a window [h] pixels tall may have: above the HP line and / or below it. */
    fun ranges(h: Int, realH: Int, appraisal: Boolean = false): List<IntRange> {
        val above = (top(realH)..(aboveEnd(realH) - h)).takeIf { !it.isEmpty() }
        // The appraisal dialog fills the lower part of the screen (bars, "caught on" line): only the top band is free then.
        if (appraisal && above != null) return listOf(above)
        return listOfNotNull(above, (belowStart(realH)..(realH - h)).takeIf { !it.isEmpty() })
    }

    /** [y] moved to the nearest allowed position. With no room anywhere it is just kept on screen. */
    fun snapY(y: Int, h: Int, realH: Int, appraisal: Boolean = false): Int {
        val rs = ranges(h, realH, appraisal)
        if (rs.isEmpty()) return y.coerceIn(0, maxOf(0, realH - h))
        return rs.map { y.coerceIn(it.first, it.last) }.minByOrNull { kotlin.math.abs(it - y) }!!
    }

    /** The biggest scale at which the window, growing down from [y], stays inside the zone it is in. */
    fun maxScaleAt(y: Int, panelHpx: Int, realH: Int): Float {
        if (panelHpx <= 0) return OverlayScale.MAX
        val limit = if (y < belowStart(realH)) aboveEnd(realH) else realH
        return (limit - y).toFloat() / panelHpx
    }
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
    /** The last exception that was swallowed so the scan loop could carry on. */
    @Volatile var error: String? = null
    private val frames = java.util.ArrayDeque<String>()
    @Synchronized fun frame(s: String) { if (frames.size >= 12) frames.removeFirst(); frames.addLast(s) }
    @Synchronized fun recent(): String = frames.joinToString("\n")
}
