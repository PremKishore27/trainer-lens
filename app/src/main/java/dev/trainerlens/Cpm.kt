package dev.trainerlens

import kotlin.math.sqrt

/** Level / CP-multiplier / stardust tables. */
object Cpm {
    private val whole = doubleArrayOf( // levels 1..40
        0.094, 0.16639787, 0.21573247, 0.25572005, 0.29024988, 0.3210876, 0.34921268, 0.37523559, 0.39956728, 0.42250001,
        0.44310755, 0.46279839, 0.48168495, 0.49985844, 0.51739395, 0.53435433, 0.55079269, 0.56675452, 0.58227891, 0.59740001,
        0.61215729, 0.62656713, 0.64065295, 0.65443563, 0.667934, 0.68116492, 0.69414365, 0.70688421, 0.71939909, 0.7317,
        0.73776948, 0.74378943, 0.74976104, 0.75568551, 0.76156384, 0.76739717, 0.7731865, 0.77893275, 0.78463697, 0.79030001
    )

    private fun wholeLevel(n: Int): Double = if (n <= 40) whole[n - 1] else 0.79030001 + 0.005 * (n - 40)

    fun at(level: Double): Double {
        val n = level.toInt()
        val c = wholeLevel(n)
        if (level - n < 0.25) return c
        val d = wholeLevel(n + 1)
        return sqrt((c * c + d * d) / 2)
    }

    fun levels(maxLevel: Double = 50.0): List<Double> =
        generateSequence(1.0) { it + 0.5 }.takeWhile { it <= maxLevel }.toList()

    /** Stardust per power-up for levels 1..40.5 (one entry per two levels). Costs above 40.5 are not tabulated. */
    private val dustBuckets = intArrayOf(
        200, 400, 600, 800, 1000, 1300, 1600, 1900, 2200, 2500,
        3000, 3500, 4000, 4500, 5000, 6000, 7000, 8000, 9000, 10000
    )
    val validDust: Set<Int> = dustBuckets.toSet()

    /** Levels consistent with the power-up stardust cost shown on screen. Unknown cost -> all levels. */
    fun levelsForDust(dust: Int?, all: List<Double>): List<Double> {
        val idx = dustBuckets.indexOf(dust ?: return all)
        if (idx < 0) return all
        // 10000 is shown at 39-40.5 and (by assumption) possibly above, so stay inclusive
        return if (dust == 10000) all.filter { it >= 39.0 } else all.filter { ((it - 1) / 2).toInt() == idx }
    }

    fun cp(base: Species, iv: Iv, level: Double): Int {
        val c = at(level)
        val raw = (base.atk + iv.a) * sqrt((base.def + iv.d).toDouble()) * sqrt((base.hp + iv.s).toDouble()) * c * c / 10
        return maxOf(10, Math.floor(raw).toInt())
    }
}
