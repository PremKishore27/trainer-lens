package dev.trainerlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A renamed Pokémon is named by the "This X was caught on …" line that the appraisal dialog shows. */
class AppraisalNameTest {
    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()
    private val db by lazy { SpeciesDb.build(res("pokemon_stats.json"), res("pokemon_types.json"), res("pokemon_evolutions.json"), res("pokemon_height_weight_scale.json")) }
    private val H = 2589
    private fun line(text: String, top: Int = 2398) = Token(text, Box(123, top, 1123, top + 47))

    @Test fun plainCaughtLine() {
        assertEquals("Dragonite", SpeciesDb.readCaught(listOf(line("This Dragonite was caught on 13/11/2026")), db).first().name)
    }

    @Test fun regionalFormWordPicksTheForm() {
        val c = SpeciesDb.readCaught(listOf(line("This Alolan Exeggutor was caught on 13/11/2026")), db)
        assertTrue(c.isNotEmpty()); assertTrue(c.all { it.name == "Exeggutor" && it.form == "Alola" })
    }

    @Test fun lineWrappedBeforeWas() {
        val c = SpeciesDb.readCaught(listOf(line("This Alolan Exeggutor", 2398), line("was caught on 13/11/2026", 2450)), db)
        assertTrue(c.isNotEmpty()); assertTrue(c.all { it.form == "Alola" })
    }

    @Test fun megaAndLetterSuffixStillFindTheSpecies() {
        assertEquals("Charizard", SpeciesDb.readCaught(listOf(line("This Mega Charizard X was caught on 1/1/2026")), db).first().name)
    }

    @Test fun oneWrongLetterIsTolerated() {
        assertEquals("Exeggutor", SpeciesDb.readCaught(listOf(line("This Exeggutar was caught on 1/1/2026")), db).first().name)
    }

    @Test fun unrelatedTextIsNotACaughtLine() {
        assertTrue(SpeciesDb.readCaught(listOf(line("This is a long unrelated sentence about nothing at all"), line("was good")), db).isEmpty())
        assertTrue(SpeciesDb.readCaught(listOf(line("CP 1500")), db).isEmpty())
    }

    /** A Dragonite renamed "Zorbo": no candy line, no types, no size – nothing identifies it until the appraisal. */
    @Test fun appraisalNamesAnUnidentifiedPokemon() {
        val sp = db.byName["dragonite"]!!.first(); val iv = Iv(13, 14, 13)
        val lv = Cpm.levels().first { Cpm.cp(sp, iv, it) >= 2900 }
        val cp = Cpm.cp(sp, iv, lv); val hp = Cpm.hp(sp, iv, lv)
        val tr = MonTracker { m -> Identify.analyze(m, db) }
        val page = listOf(Token("CP $cp", Box(389, 134, 738, 229)), Token("Zorbo", Box(541, 1041, 854, 1126)),
            Token("$hp / $hp HP", Box(537, 1246, 726, 1276)))
        fun feed() = SummaryParser.parse(page, H)!!.let { tr.observe(it.cp, it.hp, it.dust, SpeciesDb.readClues(page, H, db)) }
        feed(); feed()
        assertNull(tr.result)                                      // can't tell which Pokémon it is
        assertNotNull(tr.current); assertTrue(tr.knowsHp(hp))

        // the appraisal dialog: no CP, but the HP and the caught line
        val appraisal = listOf(Token("$hp / $hp HP", Box(537, 1246, 726, 1276)), line("This Dragonite was caught on 13/11/2026"))
        assertNull(SummaryParser.parse(appraisal, H))
        assertTrue(tr.observeCaught(SummaryParser.hp(appraisal)!!, Clues(caught = SpeciesDb.readCaught(appraisal, db))))
        assertEquals("Dragonite", tr.result!!.species.name)
        tr.observeBars(iv); tr.observeBars(iv)
        assertEquals(iv, tr.result!!.exact)
    }

    /** The same for a regional form: its own base stats are used. */
    @Test fun appraisalNamesAnAlolanForm() {
        val sp = db.byName["exeggutor"]!!.first { it.form == "Alola" }; val iv = Iv(12, 13, 14)
        val lv = Cpm.levels().first { Cpm.cp(sp, iv, it) >= 2000 }
        val cp = Cpm.cp(sp, iv, lv); val hp = Cpm.hp(sp, iv, lv)
        val tr = MonTracker { m -> Identify.analyze(m, db) }
        val page = listOf(Token("CP $cp", Box(389, 134, 738, 229)), Token("Palmy", Box(541, 1041, 854, 1126)),
            Token("$hp / $hp HP", Box(537, 1246, 726, 1276)))
        repeat(2) { SummaryParser.parse(page, H)!!.let { r -> tr.observe(r.cp, r.hp, r.dust, SpeciesDb.readClues(page, H, db)) } }
        val appraisal = listOf(Token("$hp / $hp HP", Box(537, 1246, 726, 1276)), line("This Alolan Exeggutor was caught on 13/11/2026"))
        assertTrue(tr.observeCaught(hp, Clues(caught = SpeciesDb.readCaught(appraisal, db))))
        val r = tr.result!!
        assertEquals("Exeggutor", r.species.name); assertEquals("Alola", r.species.form)
        tr.observeBars(iv); tr.observeBars(iv)
        assertEquals(iv, tr.result!!.exact)
    }

    @Test fun aCaughtLineForAnotherHpIsIgnored() {
        val tr = MonTracker { m -> Identify.analyze(m, db) }
        assertFalse(tr.observeCaught(100, Clues(caught = db.byName["dragonite"]!!)))
        assertFalse(tr.knowsHp(100))
    }
}
