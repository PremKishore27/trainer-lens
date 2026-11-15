package dev.trainerlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * Nicknamed Pokémon, flickering frames and swiping – with real screen positions (a 1080 x 2400 phone), so the
 * layout-dependent code (candy label next to its name, EVOLVE button, types above POWER UP) is exercised too.
 */
class NicknameTest {
    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()
    private val db by lazy { SpeciesDb.build(res("pokemon_stats.json"), res("pokemon_types.json"), res("pokemon_evolutions.json"), res("pokemon_height_weight_scale.json")) }
    private val H = 2400
    private fun t(text: String, l: Int, top: Int, r: Int = l + 40 + text.length * 22, b: Int = top + 44) = Token(text, Box(l, top, r, b))
    private fun sp(name: String, form: String = "Normal") = db.byName[Rules.norm(name)]!!.first { it.form.equals(form, true) }

    /** A Pokémon page the way the game lays it out (name line = nickname). */
    private fun page(
        nick: String, cp: Int, hp: Int, dust: Int?, candy: String? = null, types: String? = null, weight: String? = null,
        height: String? = null, evolve: Int? = null, candySplit: Boolean = false,
    ): List<Token> = buildList {
        add(t("CP $cp", 420, 250)); add(t(nick, 400, 1250)); add(t("$hp / $hp HP", 400, 1330))
        if (candy != null) {
            if (candySplit) { add(t(candy.uppercase(), 300, 1700)); add(t("CANDY", 300 + 40 + candy.length * 22 + 20, 1700)) }
            else add(t("245 ${candy.uppercase()} CANDY", 300, 1700))
        }
        if (types != null) add(t(types, 460, 1450))
        weight?.let { add(t(it, 150, 1450)) }; height?.let { add(t(it, 800, 1450)) }
        if (dust != null) add(t("$dust", 300, 2030))
        add(t("POWER UP", 300, 2000)); if (evolve != null) { add(t("EVOLVE", 700, 2000)); add(t("$evolve", 840, 2000)) }
    }

    private fun fits(s: Species, iv: Iv, lv: Double) = Triple(Cpm.cp(s, iv, lv), Cpm.hp(s, iv, lv), Cpm.baseDust(lv))

    private fun tracker() = MonTracker { m -> Identify.analyze(m, db) }
    private fun feed(tr: MonTracker, tokens: List<Token>): Boolean {
        val read = SummaryParser.parse(tokens, H) ?: return false
        return tr.observe(read.cp, read.hp, read.dust, SpeciesDb.readClues(tokens, H, db))
    }

    @Test fun nicknamedDragoniteFromCandyAndTypes() {
        val s = sp("Dragonite"); val (cp, hp, dust) = fits(s, Iv(14, 15, 13), 41.5)
        val tr = tracker()
        assertTrue(feed(tr, page("Dino", cp, hp, dust, "Dratini", "DRAGON / FLYING")))
        assertEquals("Dragonite", tr.result!!.species.name); assertTrue(tr.result!!.nickname)
    }

    @Test fun candyLabelSplitIntoTwoLines() {
        val s = sp("Dragonite"); val (cp, hp, dust) = fits(s, Iv(10, 10, 10), 35.0)
        val tr = tracker()
        feed(tr, page("Dino", cp, hp, dust, "Dratini", "DRAGON / FLYING", candySplit = true))
        assertEquals("Dragonite", tr.result!!.species.name)
        // the lone DRATINI line must not be mistaken for the Pokémon's name
        val c = SpeciesDb.readClues(page("Dino", cp, hp, dust, "Dratini", "DRAGON / FLYING", candySplit = true), H, db)
        assertTrue(c.names.isEmpty()); assertNotNull(c.family)
    }

    @Test fun candyLabelWithOneWrongLetter() {
        val c = SpeciesDb.readClues(listOf(t("245 DRATIN1 CANDY", 300, 1700)), H, db)
        assertEquals("Dratini", c.family!!.first().name)
        val c2 = SpeciesDb.readClues(listOf(t("MR. MIME CANDY", 300, 1700)), H, db)
        assertTrue(c2.family!!.any { it.name == "Mr. Mime" })
    }

    /** The bug: clues missing on some frames used to change the candidate list and reset the card. */
    @Test fun flickeringCluesDontChangeTheAnswer() {
        val s = sp("Dragonite"); val iv = Iv(13, 14, 15); val (cp, hp, dust) = fits(s, iv, 38.0)
        val tr = tracker()
        val frames = listOf(
            page("Dino", cp, hp, dust, "Dratini", "DRAGON / FLYING"),
            page("Dino", cp, hp, dust, null, null),                       // candy + types not read
            page("Dino", cp, hp, null, "Dratini", null),                  // dust not read
            page("Dino", cp, hp, dust, "Dratini", "FLYING"),              // half a type line
            page("Dino", cp, hp, dust, null, "DRAGON / FLYING"),
        )
        feed(tr, frames[0]); val first = tr.result!!
        assertEquals("Dragonite", first.species.name)
        for (f in frames.drop(1)) { feed(tr, f); assertEquals("Dragonite", tr.result!!.species.name) }
        assertFalse(tr.current!!.provisional)
        assertTrue(tr.observeBars(iv).not()); assertTrue(tr.observeBars(iv)) // appraisal confirmed on 2nd frame
        assertEquals(iv, tr.result!!.exact)
    }

    @Test fun swipingAcceptsNewPokemonOnItsFirstFrame() {
        val a = sp("Dragonite"); val b = sp("Kingdra")
        val (cpA, hpA, dA) = fits(a, Iv(12, 13, 13), 40.0); val (cpB, hpB, dB) = fits(b, Iv(15, 13, 13), 39.0)
        val tr = tracker()
        feed(tr, page("Dino", cpA, hpA, dA, "Dratini", "DRAGON / FLYING")); feed(tr, page("Dino", cpA, hpA, dA, "Dratini", "DRAGON / FLYING"))
        assertFalse(tr.current!!.provisional)
        assertTrue(feed(tr, page("Squirt", cpB, hpB, dB, "Horsea", "WATER / DRAGON")))
        assertEquals("Kingdra", tr.result!!.species.name); assertTrue(tr.current!!.provisional) // shown at once, confirmed later
        assertEquals(cpB, tr.current!!.cp)
    }

    /** A misread that fits no IV spread at all (animation over the CP) is ignored. One that happens to fit is shown
     *  for a single frame (⏳) and corrected on the next, because the real CP is read again. */
    @Test fun impossibleMisreadFrameDoesNotReplaceTheCard() {
        val s = sp("Dragonite"); val (cp, hp, dust) = fits(s, Iv(9, 9, 9), 30.0)
        val tr = tracker()
        feed(tr, page("Dino", cp, hp, dust, "Dratini", "DRAGON / FLYING"))
        assertFalse(feed(tr, page("Dino", cp + 2000, hp, dust, "Dratini", "DRAGON / FLYING"))) // animation overlapped a digit
        assertEquals(cp, tr.current!!.cp); assertEquals("Dragonite", tr.result!!.species.name)
    }

    @Test fun noCandyNoTypesButSizeAndDustFindsIt() {
        val s = sp("Dragonite"); val (cp, hp, dust) = fits(s, Iv(15, 14, 15), 36.0)
        val tr = tracker()
        feed(tr, page("Dino", cp, hp, dust, null, "DRAGON / FLYING", weight = "210.40kg", height = "2.25m"))
        assertEquals("Dragonite", tr.result!!.species.name); assertTrue(tr.result!!.guessed)
    }

    @Test fun nothingToIdentifyWithGivesNoAnswer() {
        val s = sp("Dragonite"); val (cp, hp, dust) = fits(s, Iv(15, 14, 15), 36.0)
        val tr = tracker()
        feed(tr, page("Dino", cp, hp, dust)); feed(tr, page("Dino", cp, hp, dust))
        assertNull(tr.result) // never a coin flip
    }

    @Test fun evolveCostSeparatesDratiniFromDragonair() {
        val dratini = sp("Dratini"); val dragonair = sp("Dragonair")
        val (cp1, hp1, d1) = fits(dratini, Iv(10, 10, 10), 10.0)
        val (cp2, hp2, d2) = fits(dragonair, Iv(10, 10, 10), 10.0)
        val t1 = tracker(); feed(t1, page("A", cp1, hp1, d1, "Dratini", "DRAGON", evolve = 25))
        assertEquals("Dratini", t1.result!!.species.name)
        val t2 = tracker(); feed(t2, page("B", cp2, hp2, d2, "Dratini", "DRAGON", evolve = 100))
        assertEquals("Dragonair", t2.result!!.species.name)
    }

    @Test fun sizeSeparatesFamilyMembersWithSameTypes() {
        val c = SpeciesDb.readClues(page("X", 500, 80, 1000, "Dratini", "DRAGON", weight = "16.20kg", height = "4.10m"), H, db)
        assertEquals("Dragonair", SpeciesDb.candidates(c, db)!!.candidates.first().name)
        val c2 = SpeciesDb.readClues(page("X", 500, 80, 1000, "Dratini", "DRAGON", weight = "3.50kg", height = "1.70m"), H, db)
        assertEquals("Dratini", SpeciesDb.candidates(c2, db)!!.candidates.first().name)
        assertEquals(16.2, c.weightKg!!, 1e-9); assertEquals(4.1, c.heightM!!, 1e-9)
    }

    @Test fun alolanExeggutorWithNickname() {
        val a = sp("Exeggutor", "Alola"); val iv = Iv(13, 11, 15); val (cp, hp, dust) = fits(a, iv, 31.5)
        val tr = tracker()
        feed(tr, page("Palm", cp, hp, dust, "Exeggcute", "GRASS / DRAGON"))
        assertEquals("alola", tr.result!!.species.form.lowercase())
    }

    @Test fun tappingAnotherSpeciesSwitchesTheAnswer() {
        val s = sp("Dragonite"); val (cp, hp, dust) = fits(s, Iv(12, 12, 12), 38.0)
        val tr = tracker()
        feed(tr, page("Dino", cp, hp, dust, "Dratini")) // no types: whole family is a candidate
        assertEquals("Dragonite", tr.result!!.species.name)
        assertFalse(tr.choose(sp("Dratini"))) // doesn't fit these numbers -> choice refused, answer unchanged
        assertEquals("Dragonite", tr.result!!.species.name)
    }

    @Test fun misreadThatFitsIsCorrectedNextFrame() {
        val s = sp("Dragonite"); val (cp, hp, dust) = fits(s, Iv(9, 9, 9), 30.0)
        val tr = tracker()
        feed(tr, page("Dino", cp, hp, dust, "Dratini", "DRAGON / FLYING"))
        feed(tr, page("Dino", cp + 7, hp, dust, "Dratini", "DRAGON / FLYING")) // may be shown once, provisional
        feed(tr, page("Dino", cp, hp, dust, "Dratini", "DRAGON / FLYING"))
        assertEquals(cp, tr.current!!.cp)
    }

    @Test fun sizeAndEvolveCostParsing() {
        val c = SpeciesDb.readClues(listOf(t("EVOLVE", 700, 2000), t("100", 840, 2000), t("2.20 m", 800, 1450), t("210,50kg", 150, 1450)), H, db)
        assertEquals(100, c.evolveCost); assertEquals(2.2, c.heightM!!, 1e-9); assertEquals(210.5, c.weightKg!!, 1e-9)
        // a number far from the button, or one that isn't a candy cost, is not the evolve cost
        assertNull(SpeciesDb.readClues(listOf(t("EVOLVE", 700, 2000), t("7", 840, 2000)), H, db).evolveCost)
    }

    /** Every Pokémon, renamed: candy + types alone must lead to the right species (and form) first or second. */
    @Test fun everyPokemonRenamedIsFoundFromCandyTypesAndSize() {
        var n = 0; val bad = ArrayList<String>()
        for ((_, forms) in db.byName) for (s in forms) {
            val base = db.family[s.dex]!!.first()
            val sz = db.size[s.dex to s.form.lowercase()] ?: db.size[s.dex to "normal"]
            val tokens = page(
                "Nick", 100, 50, null, base.name, s.types.joinToString(" / ").uppercase(),
                weight = sz?.let { "%.2fkg".format(it.second) }, height = sz?.let { "%.2fm".format(it.first) },
                evolve = db.evolveCosts[s.dex]?.minOrNull(), // the EVOLVE button shows only on Pokémon that can evolve
            )
            val g = SpeciesDb.guess(tokens, H, db)
            val top = g?.candidates?.firstOrNull()
            n++
            if (g == null || top == null || top.dex != s.dex || top.types != s.types) bad += "${s.label} -> ${top?.label}"
        }
        println("renamed lookup checked for $n forms, ${bad.size} wrong: $bad")
        assertTrue("${bad.size} wrong, e.g. ${bad.take(25)}", bad.size <= 4) // Palafin Hero/Zero etc. are the same page
    }

    @Test fun pvpTableMatchesLinearSearch() {
        val k = sp("Kingdra"); Pvp.clearCache()
        for (cap in listOf(1500, 2500)) {
            val table = Pvp.table(k, cap)!!
            val levels = Cpm.levels().reversed()
            for (iv in listOf(Iv(0, 0, 0), Iv(15, 15, 15), Iv(3, 14, 9))) {
                val lv = levels.first { Cpm.cp(k, iv, it) <= cap }
                assertEquals(lv, table[iv]!!.level, 0.0)
            }
        }
    }

    @Test fun widenedSearchIsFastEnough() {
        val s = sp("Dragonite"); val (cp, hp, dust) = fits(s, Iv(15, 14, 15), 36.0)
        val clues = SpeciesDb.readClues(page("Dino", cp, hp, dust, null, "DRAGON / FLYING", weight = "210.40kg", height = "2.25m"), H, db)
        val t0 = System.nanoTime()
        val r = IvAnalyzer.analyze(SpeciesDb.widened(clues, db)!!, cp, hp, dust)!!
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("widened analysis took $ms ms"); assertEquals("Dragonite", r.species.name)
        assertTrue("too slow: $ms ms", ms < 1500)
        assertEquals(98, Iv(15, 14, 15).pct.roundToInt())
    }
}
