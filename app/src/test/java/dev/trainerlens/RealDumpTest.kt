package dev.trainerlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A real "📋 Copy screen text" dump from a Motorola Signature (2589 px tall), Trainer Lens 0.2.17:
 * a Lucky Dragonite nicknamed "CutieFly" (also a Pokémon's name!) with the appraisal open.
 * Before the fix the CP ("c3259") wasn't read, the previous Pokémon (Rhyperior) stayed on the card and got this
 * Dragonite's appraisal.
 */
class RealDumpTest {
    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()
    private val db by lazy { SpeciesDb.build(res("pokemon_stats.json"), res("pokemon_types.json"), res("pokemon_evolutions.json"), res("pokemon_height_weight_scale.json")) }
    private val H = 2589

    private val dump = """
        c3259  @389,134,738,229
        CutieFly/  @541,1041,854,1126
        LUCKY POKÉMON  @469,1141,794,1176
        163 /163 HP  @537,1246,726,1276
        314.45kg  @181,1420,399,1474
        66m  @1007,1420,1091,1466
        EST  @1031,1494,1077,1523
        HEAVIEST  @213,1496,367,1520
        DRAGON / FLY  @515,1498,761,1523
        5,657  @369,1648,485,1704
        DUST  @400,1723,470,1756
        Attack  @183,1952,298,1981
        Defense  @182,2060,323,2089
        HP  @182,2168,232,2197
        This Dragonite was caught on 13/11/2026  @123,2398,1123,2445
        und Nürnberg, Bayern, Germany.  @216,2459,958,2507
    """.trimIndent()

    /** Parses the dump format "text  @l,t,r,b" back into tokens. */
    private fun tokens(text: String = dump) = text.lines().filter { it.contains("  @") }.map { line ->
        val (t, pos) = line.split("  @")
        val (l, top, r, b) = pos.split(",").map { it.trim().toInt() }
        Token(t, Box(l, top, r, b))
    }
    private fun without(vararg starts: String) = tokens().filter { t -> starts.none { t.text.startsWith(it) } }

    @Test fun readsCpAndHp() {
        val r = SummaryParser.parse(tokens(), H)!!
        assertEquals(3259, r.cp); assertEquals(163, r.hp)
    }

    @Test fun readsTheClues() {
        val c = SpeciesDb.readClues(tokens(), H, db)
        assertEquals("Dragonite", c.caught.first().name)
        assertEquals(setOf("dragon", "flying"), c.types)            // "FLY" was cut off at the screen edge
        assertEquals(314.45, c.weightKg!!, 1e-9)
        assertNull(c.heightM)                                      // "66m" (really 2.66m) is not trusted
        assertEquals("Cutiefly", c.names.first().name)             // the nickname
    }

    @Test fun picksDragoniteNotCutiefly() {
        val g = SpeciesDb.candidates(SpeciesDb.readClues(tokens(), H, db), db)!!
        assertEquals("Dragonite", g.candidates.first().name); assertTrue(g.nickname)
    }

    @Test fun fullSequenceFromThePreviousPokemonToTheAppraisal() {
        val tr = MonTracker { m -> Identify.analyze(m, db) }
        // the Rhyperior that was on screen before
        val rhy = db.byName["rhyperior"]!!.first(); val ivR = Iv(10, 12, 11)
        val lvR = Cpm.levels().first { Cpm.cp(rhy, ivR, it) >= 3200 }
        val rhyPage = listOf(
            Token("CP ${Cpm.cp(rhy, ivR, lvR)}", Box(389, 134, 738, 229)), Token("Rhyperior", Box(541, 1041, 854, 1126)),
            Token("${Cpm.hp(rhy, ivR, lvR)} / ${Cpm.hp(rhy, ivR, lvR)} HP", Box(537, 1246, 726, 1276)),
            Token("GROUND / ROCK", Box(515, 1498, 761, 1523)),
        )
        fun feed(t: List<Token>) = SummaryParser.parse(t, H)?.let { tr.observe(it.cp, it.hp, it.dust, SpeciesDb.readClues(t, H, db)) } ?: false
        feed(rhyPage); feed(rhyPage)
        assertEquals("Rhyperior", tr.result!!.species.name)

        // a frame of the Dragonite where the CP isn't readable: the visible HP shows the card is stale
        val noCp = without("c3259")
        assertEquals(null, SummaryParser.parse(noCp, H))
        assertTrue(tr.hpOnly(SummaryParser.hp(noCp)!!))
        assertNull(tr.current)
        assertFalse(tr.observeBars(Iv(13, 14, 13)))                // never attached to the Rhyperior

        // the dump itself
        feed(tokens()); feed(tokens())
        assertEquals("Dragonite", tr.result!!.species.name)
        tr.observeBars(Iv(13, 14, 13)); tr.observeBars(Iv(13, 14, 13))
        val r = tr.result!!
        assertEquals(Iv(13, 14, 13), r.exact); assertEquals(31.0, r.levelMin, 0.0); assertEquals(31.0, r.levelMax, 0.0)
        assertEquals(87, Math.round(r.exact!!.pct).toInt())
    }

    @Test fun withoutTheCaughtLineTypesAndWeightStillReject_Cutiefly() {
        val t = without("This Dragonite")
        val c = SpeciesDb.readClues(t, H, db)
        assertNull(SpeciesDb.candidates(c, db))                    // Cutiefly contradicts Dragon/Flying + 314 kg
        val tr = MonTracker { m -> Identify.analyze(m, db) }
        val read = SummaryParser.parse(t, H)!!
        tr.observe(read.cp, read.hp, read.dust, c); tr.observe(read.cp, read.hp, read.dust, c) // best guess needs 2 frames
        tr.observeBars(Iv(13, 14, 13)); tr.observeBars(Iv(13, 14, 13))
        val r = tr.result!!
        println("widened: ${r.species.label}, others ${r.others.map { it.label }}")
        assertEquals("Dragonite", r.species.name); assertTrue(r.guessed); assertEquals(Iv(13, 14, 13), r.exact)
    }
}
