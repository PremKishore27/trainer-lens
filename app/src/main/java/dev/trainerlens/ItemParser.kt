package dev.trainerlens

import android.graphics.Rect
import kotlin.math.abs

/** One OCR text element with its position on screen. */
data class Token(val text: String, val box: Rect)

data class ParsedScreen(val counts: Map<String, Int>, val used: Int?, val capacity: Int?)

object ItemParser {
    private val capacityRx = Regex("(\\d{1,4})\\s*/\\s*(\\d{1,4})")
    private val countRx = Regex("^[xX×]?\\s*(\\d{1,4})$")
    // Count printed in the same OCR line as the name, before or after it: "Poke Ball x20", "x20 Poke Ball", "×20"
    private val inlineCountRx = Regex("(?:^|\\s)[xX×]\\s?(\\d{1,4})(?=\\s|$)")

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
                ?: Regex("(\\d{1,4})\\s*$").find(t.text.trim())?.takeIf { Rules.norm(t.text).length > 4 }?.groupValues?.get(1)?.toIntOrNull()
            if (inline != null) counts[name] = inline else names += t to name
        }

        val numbers = tokens.filter { countRx.matches(it.text.trim()) && inSafeArea(it.box) }
        val pool = numbers

        // Pair names and numbers globally, closest pairs first, each number used at most once.
        // The list's row height ("pitch") is estimated from the gap between item labels; a count must be within
        // half a row of its label vertically (it may sit left, right, above or below the name inside that row).
        val ys = names.map { it.first.box.centerY() }.sorted()
        val minGap = names.maxOfOrNull { it.first.box.height() } ?: 20
        val gaps = ys.zipWithNext { a, b -> b - a }.filter { it > minGap }.sorted()
        val pitch = if (gaps.isNotEmpty()) gaps[gaps.size / 2] else (screenH / 6).coerceAtLeast(200)
        data class Pair3(val name: String, val label: Token, val num: Token, val d: Double)
        val pairs = ArrayList<Pair3>()
        for ((label, name) in names) {
            for (n in pool) {
                val dy = abs(n.box.centerY() - label.box.centerY())
                if (dy > pitch * 0.55) continue
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
