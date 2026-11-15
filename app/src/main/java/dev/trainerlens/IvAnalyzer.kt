package dev.trainerlens

data class RankRange(val best: Int, val worst: Int, val pctOfBest: Double)

data class IvResult(
    val species: Species,
    val fits: List<Fit>,
    val little: RankRange?,
    val great: RankRange?,
    val ultra: RankRange?,
    /** Other forms of the same Pokémon (e.g. Alolan) that also fit the numbers. */
    val otherForms: List<Species>,
) {
    val candidates: List<Iv> get() = fits.map { it.iv }.distinct()
    val pctMin get() = candidates.minOf { it.pct }
    val pctMax get() = candidates.maxOf { it.pct }
    val exact: Iv? get() = candidates.singleOrNull()
    val levelMin get() = fits.minOf { it.level }
    val levelMax get() = fits.maxOf { it.level }
    val best: Iv get() = candidates.maxBy { it.a + it.d + it.s }
    val worst: Iv get() = candidates.minBy { it.a + it.d + it.s }
}

object IvAnalyzer {
    /** Tries every form of the species; the first form (Normal when it exists) with a match wins. */
    fun analyze(forms: List<Species>, cp: Int, hp: Int, dust: Int?): IvResult? {
        val levels = Cpm.levelsForDust(dust, Cpm.levels())
        val matched = forms.mapNotNull { sp -> IvSolver.solve(sp, cp, hp, levels).takeIf { it.isNotEmpty() }?.let { sp to it } }
        val (sp, fits) = matched.firstOrNull() ?: return null
        val ivs = fits.map { it.iv }.distinct()
        return IvResult(
            sp, fits,
            range(sp, 500, ivs), range(sp, 1500, ivs), range(sp, 2500, ivs),
            matched.drop(1).map { it.first },
        )
    }

    private fun range(sp: Species, cap: Int, cands: List<Iv>): RankRange? {
        val t = Pvp.table(sp, cap) ?: return null
        val ranks = cands.mapNotNull { t[it] }
        if (ranks.isEmpty()) return null
        return RankRange(ranks.minOf { it.rank }, ranks.maxOf { it.rank }, ranks.maxOf { it.pctOfBest })
    }
}
