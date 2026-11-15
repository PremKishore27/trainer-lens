package dev.trainerlens

import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Level / CP-multiplier / stardust tables. See docs/IV-CALCULATIONS.md for the formulas. */
object Cpm {
    /**
     * CP multiplier (CPM) for levels 1, 1.5, 2 … 51. Values for 1–45 are the game-master numbers published by
     * pogoapi.net (cp_multiplier.json); 45.5–51 continue the game's fixed +0.0025 per half level.
     */
    private val table = doubleArrayOf(
        0.094, 0.13513743, 0.16639787, 0.19265091, 0.21573247, 0.23657265,
        0.25572005, 0.27353038, 0.29024988, 0.30605738, 0.3210876, 0.33544503,
        0.34921268, 0.36245774, 0.37523559, 0.38759241, 0.39956728, 0.41119355,
        0.42250001, 0.43292641, 0.44310755, 0.45305995, 0.46279839, 0.47233608,
        0.48168495, 0.49085581, 0.49985844, 0.50870176, 0.51739395, 0.52594251,
        0.53435433, 0.54263576, 0.55079269, 0.5588306, 0.56675452, 0.57456915,
        0.58227891, 0.58988791, 0.59740001, 0.60482366, 0.61215729, 0.61940411,
        0.62656713, 0.63364918, 0.64065295, 0.64758097, 0.65443563, 0.66121927,
        0.667934, 0.6745819, 0.68116492, 0.68768491, 0.69414365, 0.7005429,
        0.70688421, 0.7131691, 0.71939909, 0.72557561, 0.7317, 0.73474102,
        0.73776948, 0.74078558, 0.74378943, 0.74678122, 0.74976104, 0.7527291,
        0.75568551, 0.75863037, 0.76156384, 0.76448607, 0.76739717, 0.77029727,
        0.7731865, 0.77606495, 0.77893275, 0.78179006, 0.78463697, 0.78747358,
        0.79030001, 0.79280001, 0.79530001, 0.79780001, 0.8003, 0.8028,
        0.8053, 0.8078, 0.81029999, 0.81279999, 0.81529999, 0.81779999,
        0.82029999, 0.82279999, 0.82529999, 0.82779999, 0.83029999, 0.83279999,
        0.83529999, 0.83779999, 0.84029999, 0.84279999, 0.84529999,
    )
    const val MAX_LEVEL = 51.0

    fun at(level: Double): Double = table[((level - 1.0) * 2).roundToInt().coerceIn(0, table.size - 1)]

    fun levels(maxLevel: Double = MAX_LEVEL): List<Double> =
        generateSequence(1.0) { it + 0.5 }.takeWhile { it <= maxLevel }.toList()

    /**
     * Stardust for one power-up starting at [level] (normal Pokémon), from pogoapi.net
     * pokemon_powerup_requirements.json. Null at level 50+ (can't be powered up further).
     */
    fun baseDust(level: Double): Int? = when {
        level < 1.0 || level >= 50.0 -> null
        level < 41.0 -> intArrayOf(
            200, 400, 600, 800, 1000, 1300, 1600, 1900, 2200, 2500,
            3000, 3500, 4000, 4500, 5000, 6000, 7000, 8000, 9000, 10000
        )[((level - 1) / 2).toInt()]
        level < 43.0 -> 11000
        level < 45.0 -> 12000
        level < 47.0 -> 13000
        level < 49.0 -> 14000
        else -> 15000
    }

    /** Cost multipliers: normal, shadow (+20%), purified (-10%), lucky (-50%). */
    val dustMultipliers = doubleArrayOf(1.0, 1.2, 0.9, 0.5)

    /** dust cost shown on screen -> every level where some Pokémon variant would show that cost. */
    private val dustToLevels: Map<Int, List<Double>> = HashMap<Int, MutableList<Double>>().also { m ->
        for (lv in levels(49.5)) {
            val base = baseDust(lv) ?: continue
            for (mult in dustMultipliers) m.getOrPut((base * mult).roundToInt()) { mutableListOf() }.add(lv)
        }
    }.mapValues { it.value.distinct().sorted() }

    val validDust: Set<Int> = dustToLevels.keys

    /** Levels consistent with the power-up stardust cost shown on screen. Unknown cost -> all levels. */
    fun levelsForDust(dust: Int?, all: List<Double>): List<Double> =
        dust?.let { dustToLevels[it] }?.filter { it in all }?.takeIf { it.isNotEmpty() } ?: all

    fun cp(base: Species, iv: Iv, level: Double): Int {
        val c = at(level)
        val raw = (base.atk + iv.a) * sqrt((base.def + iv.d).toDouble()) * sqrt((base.hp + iv.s).toDouble()) * c * c / 10
        return maxOf(10, Math.floor(raw).toInt())
    }

    fun hp(base: Species, iv: Iv, level: Double): Int = maxOf(10, Math.floor((base.hp + iv.s) * at(level)).toInt())
}
