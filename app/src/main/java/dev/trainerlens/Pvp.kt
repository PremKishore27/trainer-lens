package dev.trainerlens

import kotlin.math.floor

/** PvP IV rank among all 4096 spreads of a species under a CP cap (level capped at maxLevel). */
object Pvp {
    data class Rank(val rank: Int, val pctOfBest: Double, val level: Double)

    private val cache = HashMap<String, Map<Iv, Rank>?>()

    /** Frees the rank tables (they're ~4,096 entries per species and league). */
    fun clearCache() = cache.clear()

    fun table(sp: Species, capCp: Int, maxLevel: Double = 50.0): Map<Iv, Rank>? =
        cache.getOrPut("${sp.id}-${sp.form}-$capCp-$maxLevel") { build(sp, capCp, maxLevel) }

    private fun build(sp: Species, capCp: Int, maxLevel: Double): Map<Iv, Rank>? {
        val levels = Cpm.levels(maxLevel).reversed()
        class Row(val iv: Iv, val level: Double, val product: Double)
        val rows = ArrayList<Row>(4096)
        for (a in 0..15) for (d in 0..15) for (s in 0..15) {
            val iv = Iv(a, d, s)
            val lv = levels.firstOrNull { Cpm.cp(sp, iv, it) <= capCp } ?: return null // species can't fit the league
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
