package dev.trainerlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The IV card's content as plain data: what is shown, and that its structure never changes (so the window can't resize). */
class CardModelTest {
    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()
    private val db by lazy { SpeciesDb.build(res("pokemon_stats.json"), res("pokemon_types.json"), res("pokemon_evolutions.json"), res("pokemon_height_weight_scale.json")) }
    private fun sp(name: String) = db.byName[Rules.norm(name)]!!.first()

    private fun mon(s: Species, iv: Iv, lv: Double, appraised: Boolean, frames: Int = 2): Pair<Mon, IvResult> {
        val cp = Cpm.cp(s, iv, lv); val hp = Cpm.hp(s, iv, lv); val dust = Cpm.baseDust(lv)
        val m = Mon(cp, hp)
        repeat(frames) { m.add(Clues(), dust) }
        if (appraised) m.appraised = iv
        val r = IvAnalyzer.analyze(listOf(s), cp, hp, dust, m.appraised)!!
        return m to r
    }

    @Test fun exactResultFromAppraisal() {
        val (m, r) = mon(sp("Dragonite"), Iv(13, 14, 13), 31.0, appraised = true)
        val c = CardModels.from(r, m)
        assertEquals("Dragonite", c.title)
        assertNull(c.badge)
        assertEquals("89%", c.pct); assertEquals("40/45", c.pctSub)
        assertEquals(listOf("13", "14", "13"), c.stats.map { it.text })
        assertEquals("Lv 31", c.chips[0]); assertEquals("CP ${m.cp}", c.chips[1]); assertEquals("HP ${m.hp}", c.chips[2])
        assertEquals(0.89f, c.ring, 0.01f)
        assertTrue(c.status.startsWith("Excellent"))
        assertEquals(listOf("Little", "Great", "Ultra", "Master"), c.leagues.map { it.name })
    }

    @Test fun rangeWithoutAppraisalShowsRanges() {
        val (m, r) = mon(sp("Dragonite"), Iv(13, 14, 13), 5.0, appraised = false)
        val c = CardModels.from(r, m)
        assertTrue(c.pct.endsWith("%"))
        assertTrue(c.pctSub, c.pctSub.endsWith("possible") || c.pctSub.endsWith("/45"))
        assertEquals("Appraise in-game for exact IVs", c.status)
        assertEquals(3, c.stats.size)
    }

    @Test fun badgesFollowTheStateOfTheReading() {
        val (m1, r1) = mon(sp("Kingdra"), Iv(15, 13, 13), 39.0, appraised = true, frames = 1)
        assertEquals("reading…", CardModels.from(r1, m1).badge)
        val (m2, r2) = mon(sp("Kingdra"), Iv(15, 13, 13), 39.0, appraised = true)
        assertEquals("nickname", CardModels.from(r2.copy(nickname = true), m2).badge)
        assertEquals("best guess", CardModels.from(r2.copy(guessed = true), m2).badge)
    }

    @Test fun masterLeagueNeedsHighCpAndNearPerfectIvs() {
        val (m, r) = mon(sp("Mewtwo"), Iv(15, 15, 15), 40.0, appraised = true)
        val master = CardModels.from(r, m).leagues.last()
        assertEquals(Mark.GOOD, master.mark); assertEquals("Good IVs", master.value)
        val (m2, r2) = mon(sp("Mewtwo"), Iv(5, 5, 5), 40.0, appraised = true)
        assertEquals(Mark.NONE, CardModels.from(r2, m2).leagues.last().mark)
    }

    @Test fun cpAboveTheCapIsShownAsTooHigh() {
        val (m, r) = mon(sp("Dragonite"), Iv(13, 14, 13), 31.0, appraised = true) // CP ~3200
        val great = CardModels.from(r, m).leagues.first { it.name == "Great" }
        assertEquals("CP too high", great.value); assertEquals(Mark.NONE, great.mark)
    }

    @Test fun greatLeagueSpreadIsMarkedGood() {
        val s = sp("Skarmory"); Pvp.clearCache()
        val table = Pvp.table(s, 1500)!!
        val (iv, rank) = table.entries.first { it.value.rank == 1 }.let { it.key to it.value }
        val (m, r) = mon(s, iv, rank.level, appraised = true)
        val great = CardModels.from(r, m).leagues.first { it.name == "Great" }
        assertEquals("#1 ✓", great.value); assertEquals(Mark.GOOD, great.mark)
    }

    /** The point of the redesign: whatever the Pokémon, the card has the same parts, so it can't change size. */
    @Test fun resultCardsAlwaysHaveTheSameStructure() {
        var n = 0
        for ((_, forms) in db.byName) for (s in forms) {
            Pvp.clearCache()
            for ((iv, lv, ap) in listOf(Triple(Iv(7, 12, 3), 25.0, false), Triple(Iv(15, 15, 15), 50.0, true), Triple(Iv(0, 0, 0), 1.0, false))) {
                val cp = Cpm.cp(s, iv, lv); val hp = Cpm.hp(s, iv, lv); val dust = Cpm.baseDust(lv)
                val m = Mon(cp, hp).also { it.add(Clues(), dust); it.add(Clues(), dust); if (ap) it.appraised = iv }
                val r = IvAnalyzer.analyze(listOf(s), cp, hp, dust, m.appraised) ?: continue
                val c = CardModels.from(r, m)
                assertEquals(s.label, 3, c.stats.size); assertEquals(s.label, 3, c.chips.size); assertEquals(s.label, 4, c.leagues.size)
                assertTrue(s.label, c.others.size <= 3)
                assertTrue(s.label, c.ring in 0f..1f)
                assertNull(c.message)
                n++
            }
        }
        println("card structure checked for $n results")
    }

    @Test fun messageCardsHaveNoResultParts() {
        val c = CardModels.idle()
        assertEquals("Check a Pokémon", c.title); assertEquals(CardModels.IDLE_TEXT, c.message)
        assertTrue(c.stats.isEmpty() && c.chips.isEmpty() && c.leagues.isEmpty())
    }

    @Test fun numbersDontDependOnTheDeviceLanguage() {
        val old = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)   // "31,5" in German formatting
            assertEquals("31.5", CardModels.lv(31.5)); assertEquals("89", CardModels.pct0(88.9))
        } finally { java.util.Locale.setDefault(old) }
    }

    @Test fun ratingWords() {
        assertEquals("Perfect ★★★", CardModels.rating(45)); assertEquals("Weak", CardModels.rating(10))
    }
}
