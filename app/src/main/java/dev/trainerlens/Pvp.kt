package dev.trainerlens

import kotlin.math.floor

/** PvP IV rank among all 4096 spreads of a species under a CP cap (level capped at maxLevel). */
object Pvp {
    data class Rank(val rank: Int, val pctOfBest: Double, val level: Double)

    private val cache = HashMap<String, Map<Iv, Rank>?>()

    /** Frees the rank tables (they're ~4,096 entries per species and league). */
    @Synchronized fun clearCache() = cache.clear()

    @Synchronized fun table(sp: Species, capCp: Int, maxLevel: Double = 50.0): Map<Iv, Rank>? =
        cache.getOrPut("${sp.id}-${sp.form}-$capCp-$maxLevel") { build(sp, capCp, maxLevel) }

    private fun build(sp: Species, capCp: Int, maxLevel: Double): Map<Iv, Rank>? {
        val levels = Cpm.levels(maxLevel) // ascending; CP never goes down with level, so binary search works
        class Row(val iv: Iv, val level: Double, val product: Double)
        val rows = ArrayList<Row>(4096)
        for (a in 0..15) for (d in 0..15) for (s in 0..15) {
            val iv = Iv(a, d, s)
            if (Cpm.cp(sp, iv, levels[0]) > capCp) return null // species can't fit the league
            var lo = 0; var hi = levels.size - 1 // highest level whose CP is still within the cap
            while (lo < hi) { val mid = (lo + hi + 1) / 2; if (Cpm.cp(sp, iv, levels[mid]) <= capCp) lo = mid else hi = mid - 1 }
            val lv = levels[lo]
            val c = Cpm.at(lv)
            rows += Row(iv, lv, (sp.atk + a) * c * (sp.def + d) * c * floor((sp.hp + s) * c))
        }
        rows.sortByDescending { it.product }
        val best = rows.first().product
        val out = HashMap<Iv, Rank>(4096)
        rows.forEachIndexed { i, r -> out[r.iv] = Rank(i + 1, r.product / best * 100.0, r.level) }
        return out
    }
}
