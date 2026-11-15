package dev.trainerlens

import android.graphics.Rect

/** One OCR text element with its position on screen. */
data class Token(val text: String, val box: Rect)

data class ParsedScreen(val counts: Map<String, Int>, val used: Int?, val capacity: Int?)

object ItemParser {
    private val capacityRx = Regex("(\\d{1,4})\\s*/\\s*(\\d{1,4})")
    private val countRx = Regex("^[xX×]?\\s*(\\d{1,4})$")
    private val known = Rules.DEFAULT_RULES.map { Rules.norm(it.name) to it.name }

    fun parse(tokens: List<Token>): ParsedScreen {
        var used: Int? = null
        var cap: Int? = null
        // Bag fill like "342/350"; take the one with the largest capacity (item list, not other UI)
        for (t in tokens) {
            capacityRx.find(t.text)?.let {
                val u = it.groupValues[1].toInt(); val c = it.groupValues[2].toInt()
                if (c in 100..5000 && u <= c && (cap == null || c > cap!!)) { used = u; cap = c }
            }
        }
        val numbers = tokens.filter { countRx.matches(it.text.trim()) }
        val counts = mutableMapOf<String, Int>()
        for (t in tokens) {
            val n = Rules.norm(t.text)
            val match = known.firstOrNull { it.first == n } ?: continue
            // the count tile is the nearest number token to the name label
            val best = numbers.minByOrNull { dist(it.box, t.box) } ?: continue
            if (dist(best.box, t.box) > 400) continue
            val v = countRx.find(best.text.trim())!!.groupValues[1].toInt()
            counts[match.second] = v
        }
        return ParsedScreen(counts, used, cap)
    }

    private fun dist(a: Rect, b: Rect): Double {
        val dx = (a.centerX() - b.centerX()).toDouble()
        val dy = (a.centerY() - b.centerY()).toDouble()
        return Math.sqrt(dx * dx + dy * dy)
    }
}
