package dev.trainerlens

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Reads the three appraisal bars (Attack / Defense / HP) from the screenshot, like Poké Genie does.
 * OCR finds the three labels; directly below each label we scan pixel rows for the bar, measure the bar's
 * full width and how far the coloured fill reaches, and convert that to 0–15.
 */
object AppraisalReader {
    private val atkRx = Regex("^(attack|angriff|atk)$")
    private val defRx = Regex("^(defense|defence|verteidigung|def)$")
    private val hpRx = Regex("^(hp|kp|stamina)$")

    fun read(tokens: List<Token>, bmp: Bitmap): Iv? {
        fun find(rx: Regex) = tokens.firstOrNull { rx.matches(Rules.norm(it.text)) }
        val a = find(atkRx) ?: return null
        val d = find(defRx) ?: return null
        val h = tokens.filter { hpRx.matches(Rules.norm(it.text)) }.firstOrNull { it.box.top > d.box.top } ?: return null
        // The three labels are stacked and left-aligned in the appraisal box
        if (!(a.box.top < d.box.top && d.box.top < h.box.top)) return null
        val w = bmp.width
        if (abs(a.box.left - d.box.left) > w * 0.08 || abs(d.box.left - h.box.left) > w * 0.08) return null

        val bars = listOf(a, d, h).map { bar(it, bmp) ?: return null }
        val widths = bars.map { it.second }
        if (widths.min() < w * 0.2 || widths.max() > widths.min() * 1.15) return null // not three matching bars
        val v = bars.map { (fill, width) -> (fill.toDouble() / width * 15).roundToInt().coerceIn(0, 15) }
        return Iv(v[0], v[1], v[2])
    }

    /** Returns (filled pixels, bar width) for the bar under [label], or null if no bar is found. */
    private fun bar(label: Token, bmp: Bitmap): Pair<Int, Int>? {
        val lh = label.box.height().coerceAtLeast(10)
        val y0 = (label.box.bottom + 2).coerceAtMost(bmp.height - 1)
        val y1 = (label.box.bottom + lh * 3).coerceAtMost(bmp.height - 1)
        val xMin = (label.box.left - lh * 2).coerceAtLeast(0)
        val xMaxStart = label.box.left + lh * 2
        val gapTol = (bmp.width * 0.012).toInt().coerceAtLeast(4) // small white dividers between bar segments
        val row = IntArray(bmp.width)
        var best: Triple<Int, Int, Int>? = null // y, start, end
        for (y in y0..y1) {
            bmp.getPixels(row, 0, bmp.width, 0, y, bmp.width, 1)
            var x = xMin
            while (x < bmp.width) {
                if (!isBar(row[x])) { x++; continue }
                val start = x
                var end = x; var gap = 0
                while (x < bmp.width && gap <= gapTol) {
                    if (isBar(row[x])) { end = x; gap = 0 } else gap++
                    x++
                }
                if (start <= xMaxStart && (best == null || end - start > best.third - best.second)) best = Triple(y, start, end)
            }
        }
        val (y, s, e) = best ?: return null
        val width = e - s + 1
        bmp.getPixels(row, 0, bmp.width, 0, y, bmp.width, 1)
        var fillEnd = s - 1
        for (x in s..e) if (isFill(row[x])) fillEnd = x
        return (fillEnd - s + 1).coerceAtLeast(0) to width
    }

    /** Anything that isn't the white card background: grey empty bar or coloured fill. */
    private fun isBar(c: Int): Boolean {
        val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
        val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
        return (mx - mn > 40) || (mx in 150..242 && mx - mn < 20)
    }

    /** Orange (or red for 15) fill: strongly coloured, bright enough. */
    private fun isFill(c: Int): Boolean {
        val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
        val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
        return mx > 120 && (mx - mn).toDouble() / mx > 0.3
    }
}
