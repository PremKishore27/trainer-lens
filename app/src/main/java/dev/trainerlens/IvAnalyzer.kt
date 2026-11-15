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
}

object IvAnalyzer {
    /**
     * Tries every form of the species; the first form (Normal when it exists) with a match wins.
     * [appraisal] (IVs measured from the appraisal bars) narrows the result to that spread; if a bar was misjudged by
     * one point, the closest spread that fits CP/HP is used instead.
     */
    fun analyze(forms: List<Species>, cp: Int, hp: Int, dust: Int?, appraisal: Iv? = null): IvResult? {
        val all = Cpm.levels()
        fun solveAll(levels: List<Double>) =
            forms.mapNotNull { sp -> IvSolver.solve(sp, cp, hp, levels).takeIf { it.isNotEmpty() }?.let { sp to it } }
        // A misread stardust number must not hide the right answer: fall back to every level.
        val matched = solveAll(Cpm.levelsForDust(dust, all)).ifEmpty { if (dust != null) solveAll(all) else emptyList() }
        if (matched.isEmpty()) return null

        var pick = matched.first()
        if (appraisal != null) {
            fun dist(iv: Iv) = Math.abs(iv.a - appraisal.a) + Math.abs(iv.d - appraisal.d) + Math.abs(iv.s - appraisal.s)
            val narrowed = matched.mapNotNull { (sp, fits) ->
                val d = fits.minOf { dist(it.iv) }
                if (d <= 2) Triple(sp, fits.filter { dist(it.iv) == d }, d) else null
            }.minByOrNull { it.third }
            if (narrowed != null) pick = narrowed.first to narrowed.second
        }
        val (sp, fits) = pick
        val ivs = fits.map { it.iv }.distinct()
        return IvResult(
            sp, fits,
            range(sp, 500, ivs), range(sp, 1500, ivs), range(sp, 2500, ivs),
            matched.map { it.first }.filter { it != sp },
        )
    }

    private fun range(sp: Species, cap: Int, cands: List<Iv>): RankRange? {
        val t = Pvp.table(sp, cap) ?: return null
        val ranks = cands.mapNotNull { t[it] }
        if (ranks.isEmpty()) return null
        return RankRange(ranks.minOf { it.rank }, ranks.maxOf { it.rank }, ranks.maxOf { it.pctOfBest })
    }
}
