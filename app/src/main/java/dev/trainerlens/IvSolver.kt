package dev.trainerlens

/** One possible answer: an IV spread at a level. */
data class Fit(val iv: Iv, val level: Double)

/** All IV spreads (0-15 each) that reproduce the CP and HP shown on screen at one of the given levels. */
object IvSolver {
    fun solve(sp: Species, cp: Int, hp: Int, levels: List<Double>): List<Fit> {
        val out = ArrayList<Fit>()
        for (lv in levels) {
            for (s in 0..15) {
                if (Cpm.hp(sp, Iv(0, 0, s), lv) != hp) continue
                for (a in 0..15) for (d in 0..15) {
                    val iv = Iv(a, d, s)
                    if (Cpm.cp(sp, iv, lv) == cp) out.add(Fit(iv, lv))
                }
            }
        }
        return out
    }
}
