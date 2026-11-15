package dev.trainerlens

import android.graphics.Rect

/** One OCR text element with its position on screen. */
data class Token(val text: String, val box: Rect)

data class ParsedScreen(val counts: Map<String, Int>, val used: Int?, val capacity: Int?)

object ItemParser {
    private val capacityRx = Regex("(\\d{1,4})\\s*/\\s*(\\d{1,4})")
    private val countRx = Regex("^[xX×]?\\s*(\\d{1,4})$")
    private val inlineCountRx = Regex("[xX×]\\s*(\\d{1,4})\\s*$")

    // Longest names first so "Silver Pinap Berry" wins over "Pinap Berry", "Super Potion" over "Potion".
    private val known = Rules.DEFAULT_RULES.map { Rules.norm(it.name) to it.name }.sortedByDescending { it.first.length }

    /** Matches a label like "Poké Ball", "Poke Ball x20" or "Egg Incubator ∞" but not a long description line. */
    private fun matchName(text: String): String? {
        val n = Rules.norm(text)
        if (n.isEmpty()) return null
        return known.firstOrNull { (k, _) -> n.contains(k) && n.length <= k.length + 8 }?.second
    }

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
        val xNumbers = numbers.filter { it.text.trim().firstOrNull()?.let { c -> c == 'x' || c == 'X' || c == '×' } == true }
        val pool = xNumbers.ifEmpty { numbers }

        val counts = mutableMapOf<String, Int>()
        for (t in tokens) {
            val name = matchName(t.text) ?: continue
            // Count printed on the same line ("Poke Ball x20")
            val inline = inlineCountRx.find(t.text.trim())?.groupValues?.get(1)?.toIntOrNull()
            if (inline != null) { counts[name] = inline; continue }
            // Otherwise the count tile is the nearest number token to the name label
            val best = pool.minByOrNull { dist(it.box, t.box) } ?: continue
            if (dist(best.box, t.box) > 400) continue
            val v = countRx.find(best.text.trim())!!.groupValues[1].toInt()
            counts[name] = v
        }
        return ParsedScreen(counts, used, cap)
    }

    private fun dist(a: Rect, b: Rect): Double {
        val dx = (a.centerX() - b.centerX()).toDouble()
        val dy = (a.centerY() - b.centerY()).toDouble()
        return Math.sqrt(dx * dx + dy * dy)
    }
}
