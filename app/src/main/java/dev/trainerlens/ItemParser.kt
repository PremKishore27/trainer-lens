package dev.trainerlens

import android.graphics.Rect
import kotlin.math.abs

/** One OCR text element with its position on screen. */
data class Token(val text: String, val box: Rect)

data class ParsedScreen(val counts: Map<String, Int>, val used: Int?, val capacity: Int?)

object ItemParser {
    private val capacityRx = Regex("(\\d{1,4})\\s*/\\s*(\\d{1,4})")
    private val countRx = Regex("^[xX×]?\\s*(\\d{1,4})$")
    private val inlineCountRx = Regex("[xX×]\\s*(\\d{1,4})\\s*$")

    // Longest names first so "Max Potion" wins over "Potion", "Silver Pinap Berry" over "Pinap Berry".
    private val known = Rules.DEFAULT_RULES.map { Rules.norm(it.name) to it.name }.sortedByDescending { it.first.length }

    /** Matches a label like "Poké Ball", "Poke Ball x20" or "Egg Incubator ∞" but not a long description line. */
    private fun matchName(text: String): String? {
        val n = Rules.norm(text)
        if (n.isEmpty()) return null
        return known.firstOrNull { (k, _) -> n.contains(k) && n.length <= k.length + 8 }?.second
    }

    fun parse(tokens: List<Token>, screenH: Int = 0): ParsedScreen {
        var used: Int? = null
        var cap: Int? = null
        // Bag fill like "342/350"; take the one with the largest capacity (item list, not other UI)
        for (t in tokens) {
            capacityRx.find(t.text)?.let {
                val u = it.groupValues[1].toInt(); val c = it.groupValues[2].toInt()
                if (c in 100..5000 && u <= c && (cap == null || c > cap!!)) { used = u; cap = c }
            }
        }

        // Rows that are cut off at the top or bottom of the screen while scrolling are the main source of
        // wrong pairs (a name whose own count is off-screen grabs the neighbour's count), so skip them.
        fun inSafeArea(r: Rect) = screenH <= 0 || (r.top > screenH * 0.08 && r.bottom < screenH * 0.94)

        val counts = mutableMapOf<String, Int>()
        val names = ArrayList<Pair<Token, String>>()
        for (t in tokens) {
            if (!inSafeArea(t.box)) continue
            val name = matchName(t.text) ?: continue
            // Count printed on the same line ("Poke Ball x20")
            val inline = inlineCountRx.find(t.text.trim())?.groupValues?.get(1)?.toIntOrNull()
            if (inline != null) counts[name] = inline else names += t to name
        }

        val numbers = tokens.filter { countRx.matches(it.text.trim()) && inSafeArea(it.box) }
        val xNumbers = numbers.filter { it.text.trim().firstOrNull()?.let { c -> c == 'x' || c == 'X' || c == '×' } == true }
        val pool = xNumbers.ifEmpty { numbers }

        // Pair names and numbers globally, closest pairs first, each number used at most once. A count must sit
        // in the same row as its label: vertically within ~2.5 label heights.
        data class Pair3(val name: String, val label: Token, val num: Token, val d: Double)
        val pairs = ArrayList<Pair3>()
        for ((label, name) in names) {
            val h = label.box.height().coerceAtLeast(20)
            for (n in pool) {
                val dy = abs(n.box.centerY() - label.box.centerY())
                if (dy > h * 2.5) continue
                val dx = abs(n.box.centerX() - label.box.centerX())
                pairs += Pair3(name, label, n, Math.hypot(dx.toDouble(), dy * 2.0))
            }
        }
        pairs.sortBy { it.d }
        val usedNums = HashSet<Token>(); val usedLabels = HashSet<Token>()
        for (p in pairs) {
            if (p.num in usedNums || p.label in usedLabels || p.name in counts) continue
            usedNums += p.num; usedLabels += p.label
            counts[p.name] = countRx.find(p.num.text.trim())!!.groupValues[1].toInt()
        }
        return ParsedScreen(counts, used, cap)
    }
}
