package dev.trainerlens

import kotlin.math.floor
import kotlin.math.sqrt

/** All IV spreads (0-15 each) that reproduce the CP and HP shown on screen at one of the given levels. */
object IvSolver {
    fun solve(sp: Species, cp: Int, hp: Int, levels: List<Double>): List<Iv> {
        val out = LinkedHashSet<Iv>()
        for (lv in levels) {
            val c = Cpm.at(lv)
            for (s in 0..15) {
                if (maxOf(10, floor((sp.hp + s) * c).toInt()) != hp) continue
                for (a in 0..15) for (d in 0..15) {
                    val iv = Iv(a, d, s)
                    if (Cpm.cp(sp, iv, lv) == cp) out.add(iv)
                }
            }
        }
        return out.toList()
    }
}
