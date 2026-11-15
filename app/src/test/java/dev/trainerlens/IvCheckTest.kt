package dev.trainerlens

import android.graphics.Rect
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Checks the IV maths against the game data (pogoapi.net snapshots in src/test/resources) for EVERY Pokémon and
 * form: build the CP / HP / stardust the game would show, run them back through the app's solver, and make sure
 * the true IVs and level always come out.
 */
class IvCheckTest {
    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()
    private val db by lazy { SpeciesDb.build(res("pokemon_stats.json"), res("pokemon_types.json"), res("pokemon_evolutions.json")) }
    private fun tok(text: String) = Token(text, Rect())

    @Test fun cpMultiplierMatchesGameMaster() {
        val arr = JSONArray(res("cp_multiplier.json"))
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val lv = o.getDouble("level")
            assertEquals("CPM at level $lv", o.getDouble("multiplier"), Cpm.at(lv), 1e-7)
        }
    }

    @Test fun stardustMatchesPowerUpTable() {
        val o = JSONObject(res("pokemon_powerup_requirements.json"))
        for (k in o.keys()) {
            val row = o.getJSONObject(k)
            val lv = row.getDouble("current_level")
            assertEquals("dust at level $lv", row.getInt("stardust_to_upgrade"), Cpm.baseDust(lv))
            for (m in Cpm.dustMultipliers) {
                val shown = (row.getInt("stardust_to_upgrade") * m).roundToInt()
                assertTrue("level $lv allowed for dust $shown", lv in Cpm.levelsForDust(shown, Cpm.levels()))
            }
        }
    }

    @Test fun knownValues() {
        val mewtwo = db.byName["mewtwo"]!!.first()
        assertEquals(4178, Cpm.cp(mewtwo, Iv(15, 15, 15), 40.0))
        assertEquals(4724, Cpm.cp(mewtwo, Iv(15, 15, 15), 50.0))
        val kingdra = db.byName["kingdra"]!!
        val k = IvAnalyzer.analyze(kingdra, 2578, 152, 10000, Iv(15, 13, 13))!!
        assertEquals(Iv(15, 13, 13), k.exact); assertEquals(39.0, k.levelMin, 0.0); assertEquals(39.0, k.levelMax, 0.0)
        val emp = db.byName["empoleon"]!!.first()
        val cp = Cpm.cp(emp, Iv(14, 14, 13), 38.0); val hp = Cpm.hp(emp, Iv(14, 14, 13), 38.0)
        assertEquals(2785, cp); assertEquals(163, hp)
        val e = IvAnalyzer.analyze(listOf(emp), cp, hp, Cpm.baseDust(38.0), Iv(14, 14, 13))!!
        assertEquals(Iv(14, 14, 13), e.exact); assertEquals(91, e.exact!!.pct.roundToInt())
    }

    /** Every Pokémon and every form, at many IVs and levels, as normal / shadow / purified / lucky. */
    @Test fun everyPokemonEveryForm() {
        val ivs = listOf(Iv(0, 0, 0), Iv(15, 15, 15), Iv(15, 13, 13), Iv(7, 12, 3), Iv(1, 15, 15))
        val levels = listOf(1.0, 8.5, 15.0, 20.0, 25.5, 30.0, 35.0, 39.0, 40.0, 42.5, 46.0, 49.5, 50.0)
        val failures = ArrayList<String>()
        var cases = 0
        for ((_, forms) in db.byName) for (sp in forms) {
            Pvp.clearCache()
            // what the app would consider for this name + the types shown on screen
            val cands = forms.filter { it.types == sp.types }.ifEmpty { forms }
            for ((i, iv) in ivs.withIndex()) for ((j, lv) in levels.withIndex()) {
                val cp = Cpm.cp(sp, iv, lv); val hp = Cpm.hp(sp, iv, lv)
                val mult = Cpm.dustMultipliers[(i + j) % Cpm.dustMultipliers.size]
                val dust = Cpm.baseDust(lv)?.let { (it * mult).roundToInt() }
                cases++
                // 1. without appraisal the true spread must be among the candidates
                val r = IvAnalyzer.analyze(listOf(sp), cp, hp, dust)
                if (r == null || r.fits.none { it.iv == iv && it.level == lv }) {
                    failures += "${sp.label} $iv L$lv CP$cp HP$hp dust$dust: not in range"; continue
                }
                // 2. with the appraisal it must be exact, the right form, and include the right level
                val a = IvAnalyzer.analyze(cands, cp, hp, dust, iv)
                when {
                    a == null -> failures += "${sp.label} $iv L$lv: no result with appraisal"
                    a.exact != iv -> failures += "${sp.label} $iv L$lv: appraisal gave ${a.candidates}"
                    a.species.atk != sp.atk || a.species.def != sp.def || a.species.hp != sp.hp ->
                        if (Cpm.cp(a.species, iv, lv) != cp || Cpm.hp(a.species, iv, lv) != hp)
                            failures += "${sp.label} $iv L$lv: picked ${a.species.label}"
                    lv !in a.fits.map { it.level } -> failures += "${sp.label} $iv L$lv: level ${a.levelMin}–${a.levelMax}"
                }
            }
        }
        println("checked $cases cases, ${db.byName.values.sumOf { it.size }} forms")
        if (failures.isNotEmpty()) fail("${failures.size} failures, e.g.\n" + failures.take(40).joinToString("\n"))
    }

    @Test fun appraisalBarOffByOneIsCorrected() {
        val sp = db.byName["dragonite"]!!.first()
        val iv = Iv(14, 15, 13); val lv = 41.5
        val r = IvAnalyzer.analyze(listOf(sp), Cpm.cp(sp, iv, lv), Cpm.hp(sp, iv, lv), Cpm.baseDust(lv), Iv(14, 14, 13))!!
        assertTrue(iv in r.candidates)
    }

    @Test fun nicknamedPokemonFoundViaCandyAndTypes() {
        val g = SpeciesDb.guess(listOf(tok("Dino"), tok("DRAGON / FLYING"), tok("245 DRATINI CANDY"), tok("152 / 152 HP")), 0, db)
        assertNotNull(g); assertEquals("Dragonite", g!!.candidates.first().name); assertTrue(g.nickname)
    }

    @Test fun nicknameThatIsAnotherPokemonsName() {
        val g = SpeciesDb.guess(listOf(tok("Pikachu"), tok("DRAGON / FLYING"), tok("DRATINI CANDY")), 0, db)!!
        assertEquals("Dragonite", g.candidates.first().name)
    }

    @Test fun alolanExeggutorByType() {
        val g = SpeciesDb.guess(listOf(tok("Exeggutor"), tok("GRASS / DRAGON"), tok("EXEGGCUTE CANDY")), 0, db)!!
        val first = g.candidates.first()
        assertEquals("Exeggutor", first.name); assertEquals("alola", first.form.lowercase())
        // and a real Alolan Exeggutor solves to the Alolan form
        val iv = Iv(13, 11, 15); val lv = 31.5
        val r = IvAnalyzer.analyze(g.candidates, Cpm.cp(first, iv, lv), Cpm.hp(first, iv, lv), Cpm.baseDust(lv), iv)!!
        assertEquals("alola", r.species.form.lowercase()); assertEquals(iv, r.exact)
        val normal = SpeciesDb.guess(listOf(tok("Exeggutor"), tok("GRASS / PSYCHIC"), tok("EXEGGCUTE CANDY")), 0, db)!!
        assertTrue(normal.candidates.first().isNormal)
    }

    @Test fun everyFamilyMemberFoundFromCandyWhenNicknamed() {
        var checked = 0
        for ((_, forms) in db.byName) {
            val sp = forms.first()
            val fam = db.family[sp.dex] ?: continue
            val base = fam.first()
            val typesLine = sp.types.joinToString(" / ").uppercase()
            val g = SpeciesDb.guess(listOf(tok("Nick"), tok(typesLine), tok("${base.name.uppercase()} CANDY")), 0, db)
            assertNotNull("${sp.label} via ${base.name} candy", g)
            assertTrue("${sp.label} not among ${g!!.candidates.map { it.label }}", g.candidates.any { it.dex == sp.dex && it.types == sp.types })
            checked++
        }
        println("nickname lookup checked for $checked Pokémon")
    }

    @Test fun ocrLookAlikesInCpAndHp() {
        val r = SummaryParser.parse(listOf(tok("CP 2S78"), tok("Kingdra"), tok("152 / 152 HP")), 100)!!
        assertEquals(2578, r.cp); assertEquals(152, r.hp)
        val r2 = SummaryParser.parse(listOf(tok("CP1O45"), tok("l52/l52 HP")), 100)!!
        assertEquals(1045, r2.cp); assertEquals(152, r2.hp)
    }

    @Test fun ivPercentages() {
        assertEquals(100.0, Iv(15, 15, 15).pct, 1e-9)
        assertEquals(84, Iv(12, 13, 13).pct.roundToInt())
        assertEquals(80.0, Iv(12, 12, 12).pct, 1e-9)
        assertEquals(91, Iv(14, 14, 13).pct.roundToInt())
        assertTrue(abs(Iv(15, 13, 13).pct - 91.11) < 0.01)
    }
}
