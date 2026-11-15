package dev.trainerlens

import kotlin.math.abs

data class RankRange(val best: Int, val worst: Int, val pctOfBest: Double)

data class IvResult(
    val species: Species,
    val fits: List<Fit>,
    val little: RankRange?,
    val great: RankRange?,
    val ultra: RankRange?,
    /** Other forms of the same Pokémon (e.g. Alolan) that also fit the numbers. */
    val otherForms: List<Species>,
    /** true when the level arc narrowed the result. */
    val arcUsed: Boolean = false,
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
     * [candidates] are the possible species/forms, most likely first (see SpeciesDb.guess).
     * [appraisal] (IVs measured from the appraisal bars) picks that spread; if a bar was misjudged by a point,
     * the closest spread that fits CP/HP is used. [arcLevel] (from the level arc) narrows the levels when it can –
     * if nothing fits at that level it is ignored.
     */
    fun analyze(
        candidates: List<Species>, cp: Int, hp: Int, dust: Int?,
        appraisal: Iv? = null, arcLevel: Double? = null,
    ): IvResult? {
        val all = Cpm.levels()
        fun solveAll(levels: List<Double>) =
            candidates.mapNotNull { sp -> IvSolver.solve(sp, cp, hp, levels).takeIf { it.isNotEmpty() }?.let { sp to it } }
        // A misread stardust number must not hide the right answer: fall back to every level.
        var matched = solveAll(Cpm.levelsForDust(dust, all)).ifEmpty { if (dust != null) solveAll(all) else emptyList() }
        if (matched.isEmpty()) return null

        var arcUsed = false
        if (arcLevel != null) {
            val near = matched.mapNotNull { (sp, fits) -> fits.filter { abs(it.level - arcLevel) <= 0.75 }.takeIf { it.isNotEmpty() }?.let { sp to it } }
            if (near.isNotEmpty()) { matched = near; arcUsed = true }
        }

        var pick = matched.first()
        if (appraisal != null) {
            fun dist(iv: Iv) = abs(iv.a - appraisal.a) + abs(iv.d - appraisal.d) + abs(iv.s - appraisal.s)
            val narrowed = matched.mapNotNull { (sp, fits) ->
                val d = fits.minOf { dist(it.iv) }
                if (d <= 2) Triple(sp, fits.filter { dist(it.iv) == d }, d) else null
            }.minByOrNull { it.third } // ties keep the candidate order
            if (narrowed != null) pick = narrowed.first to narrowed.second
        }
        val (sp, fits) = pick
        val ivs = fits.map { it.iv }.distinct()
        return IvResult(
            sp, fits,
            range(sp, 500, ivs), range(sp, 1500, ivs), range(sp, 2500, ivs),
            matched.map { it.first }.filter { it != sp && it.name == sp.name },
            arcUsed,
        )
    }

    private fun range(sp: Species, cap: Int, cands: List<Iv>): RankRange? {
        val t = Pvp.table(sp, cap) ?: return null
        val ranks = cands.mapNotNull { t[it] }
        if (ranks.isEmpty()) return null
        return RankRange(ranks.minOf { it.rank }, ranks.maxOf { it.rank }, ranks.maxOf { it.pctOfBest })
    }
}
