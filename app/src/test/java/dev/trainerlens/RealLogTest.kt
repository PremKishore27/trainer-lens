package dev.trainerlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two "Copy log" dumps from a real phone (Trainer Lens 0.2.32, 1264 x 2780 display captured at 1264 x 2589):
 *  - the Items screen with MORE items than capacity ("1506 / 1425"): the bag size was rejected and the Bag tab stayed on
 *    "Reading your bag" with no advice;
 *  - a Garchomp appraisal while the window, at its largest size, covered the HP line: CP was read, HP never, and the IV
 *    card fell back to "Check a Pokémon".
 * (The place in the "caught on" line is replaced.)
 */
class RealLogTest {
    private fun tokens(text: String) = text.trimIndent().lines().filter { it.contains("  @") }.map { line ->
        val (t, pos) = line.split("  @")
        val (l, top, r, b) = pos.split(",").map { it.trim().toInt() }
        Token(t, Box(l, top, r, b))
    }

    private val itemsScreen = tokens("""
        |TEMS  @561,130,702,164
        1506/ 1425  @535,193,728,226
        FEATURED  @530,350,734,374
        x18  @785,644,839,671
        E  @170,695,180,729
        bn  @1064,1365,1106,1399
        x192  @156,1656,253,1698
        x103  @516,1656,612,1698
        ×99  @876,1657,943,1697
        Max Revive  @857,1783,1089,1821
        Max Potion  @139,1786,370,1820
        Revive  @549,1786,681,1820
        POKÉ BALLS  @524,1920,744,1955
        x46  @156,2226,226,2266
        209  @518,2227,614,2265
        214  @876,2227,972,2265
        Poké Ball  @176,2340,345,2388
        Grezall  @515,2350,709,2394
        Ultra Ball  @882,2353,1064,2386
        X  @616,2417,650,2451
        M  @249,2540,297,2574
    """)

    @Test fun bagSizeIsReadEvenWhenTheBagIsOverCapacity() {
        val p = ItemParser.parse(itemsScreen, 2589)
        assertEquals(1506, p.used); assertEquals(1425, p.capacity)
        assertEquals(mapOf("Poke Ball" to 46, "Ultra Ball" to 214, "Max Potion" to 192, "Revive" to 103, "Max Revive" to 99), p.counts)
    }

    @Test fun overCapacityBagAsksForADiscardPlan() {
        val plan = Recommender.plan(mapOf("Poke Ball" to 46, "Ultra Ball" to 214, "Max Potion" to 192, "Revive" to 103, "Max Revive" to 99), 1506, 1425, 30)
        assertEquals(111, plan.needed)                       // 81 over capacity + 30 free slots wanted
        assertTrue(plan.discards.isNotEmpty())
    }

    /** The newest frame of the IV log: no HP line (covered), but the page's labels are there. */
    private val appraisalCovered = tokens("""
        59.98kg  @181,1357,367,1413
        1.64m  @940,1367,1081,1406
        EIGHT  @988,1430,1067,1466
        WEIGHT  @209,1438,338,1461
        DRAGON / GROUNE  @485,1439,820,1465
        6,932  @375,1592,478,1651
        DUST  @394,1666,477,1697
        Attack  @183,1889,298,1918
        Defense  @182,1996,324,2025
        HP  @182,2105,233,2133
        This Garchomp was caught on 01/08/2020  @123,2334,1134,2381
        around Example Town, Example State,  @123,2398,1005,2442
        Country.  @122,2461,335,2500
    """) + Token("CP3493", Box(389, 120, 740, 220))

    @Test fun theAppraisalScreenCountsAsAPokemonPageEvenWithoutPowerUp() {
        assertFalse(SummaryParser.hasPowerUp(appraisalCovered))
        assertTrue(SummaryParser.looksLikePokemonPage(appraisalCovered))
        assertEquals(3493, SummaryParser.cp(appraisalCovered, 2589)); assertEquals(null, SummaryParser.hp(appraisalCovered))
    }

    /** "DRAGON / GROUNE": the last letter of GROUND was cut off by the page, and the whole type clue was lost. */
    @Test fun aCutOffTypeWordStillCounts() {
        assertEquals(setOf("dragon", "ground"), SpeciesDb.typesOnScreen(appraisalCovered, 2589))
        assertEquals(setOf("dragon", "flying"), SpeciesDb.typesOnScreen(listOf(Token("DRAGON / FLY", Box(515, 1498, 761, 1523))), 2589))
        assertEquals(emptySet<String>(), SpeciesDb.typesOnScreen(listOf(Token("Weight Height", Box(515, 1498, 761, 1523))), 2589))
    }

    @Test fun aPokemonListIsNotAPokemonPage() {
        val list = listOf(Token("CP 1234", Box(100, 300, 300, 340)), Token("CP 987", Box(500, 300, 700, 340)), Token("Pidgey", Box(100, 500, 300, 540)))
        assertFalse(SummaryParser.looksLikePokemonPage(list))
    }

    @Test fun coveredHpIsReportedAfterThreeFramesAndIgnoresFlickeringCp() {
        val c = CoverTracker()
        // the sequence from the log: CP read on most frames, null on some, HP never
        val cps = listOf(3493, 3493, 3493, null, null, 3493)
        val results = cps.map { c.update(it, null) }
        assertEquals(listOf(false, false, true, true, true, true), results)
        assertFalse(c.update(3493, 188))                     // HP visible again: the hint goes away
        assertEquals(0, c.count)
    }

    @Test fun aFrameWithoutCpNeitherCountsNorResets() {
        val c = CoverTracker()
        c.update(3493, null); c.update(3493, null)
        assertFalse(c.update(null, null)); assertEquals(2, c.count)
    }
}
