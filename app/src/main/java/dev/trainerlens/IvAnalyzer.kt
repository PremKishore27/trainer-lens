package dev.trainerlens

data class RankRange(val best: Int, val worst: Int, val pctOfBest: Double)

data class IvResult(
    val species: Species,
    val candidates: List<Iv>,
    val great: RankRange?,
    val ultra: RankRange?,
) {
    val pctMin get() = candidates.minOf { it.pct }
    val pctMax get() = candidates.maxOf { it.pct }
    val exact: Iv? get() = candidates.singleOrNull()
}

object IvAnalyzer {
    fun analyze(sp: Species, cp: Int, hp: Int, dust: Int?): IvResult? {
        val levels = Cpm.levelsForDust(dust, Cpm.levels(50.0))
        val cands = IvSolver.solve(sp, cp, hp, levels)
        if (cands.isEmpty()) return null
        return IvResult(sp, cands, range(sp, 1500, cands), range(sp, 2500, cands))
    }

    private fun range(sp: Species, cap: Int, cands: List<Iv>): RankRange? {
        val t = Pvp.table(sp, cap) ?: return null
        val ranks = cands.mapNotNull { t[it] }
        if (ranks.isEmpty()) return null
        return RankRange(ranks.minOf { it.rank }, ranks.maxOf { it.rank }, ranks.maxOf { it.pctOfBest })
    }
}
