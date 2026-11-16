package dev.trainerlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The walkthrough hints: which one shows where, that it fits the card, and that a Pokémon page isn't mistaken for the bag. */
class HintsTest {
    private fun lines(s: String) = s.lines()

    @Test fun ivTabSaysWhereToGo() {
        val idle = CardModels.idle(); val wrong = CardModels.idle(onItems = true)
        assertEquals(Art.POKEMON, idle.art); assertEquals(Art.POKEMON, wrong.art)
        assertTrue(idle.message!!.contains("Pokémon")); assertEquals("Wrong screen", wrong.title)
        assertTrue(wrong.message!!.contains("Item bag"))
    }

    @Test fun drawingPlusTextFitsTheCardBody() {
        for (c in listOf(CardModels.idle(), CardModels.idle(true))) {
            val ls = lines(c.message!!)
            assertTrue("${ls.size} lines", ls.size <= Metrics.MESSAGE_LINES_WITH_ART)
            assertTrue("a line is too long to stay on one row: $ls", ls.all { it.length <= 34 })
            val need = Metrics.ART + Metrics.ART_GAP + ls.size * Metrics.MESSAGE_LINE
            assertTrue("needs ${need}dp, body has ${Metrics.IV_BODY}dp", need <= Metrics.IV_BODY)
        }
    }

    @Test fun bagHintDependsOnWhereThePlayerIs() {
        assertEquals(BagHint.NONE, Hints.bag(onItems = true, remembered = 0))
        assertEquals(BagHint.NONE, Hints.bag(onItems = true, remembered = 12))
        assertEquals(BagHint.GO_TO_BAG, Hints.bag(onItems = false, remembered = 0))
        assertEquals(BagHint.NOT_ON_ITEMS, Hints.bag(onItems = false, remembered = 12)) // keep the remembered list
    }

    private val pokemonPage = listOf(
        Token("CP 3259", Box(389, 134, 738, 229)), Token("Dragonite", Box(541, 1041, 854, 1126)),
        Token("163 /163 HP", Box(537, 1246, 726, 1276)), Token("DRAGON / FLYING", Box(515, 1498, 761, 1523)),
        Token("5,657", Box(369, 1648, 485, 1704)), Token("POWER UP", Box(300, 1900, 780, 1990)),
        Token("This Dragonite was caught on 13/11/2026", Box(123, 2398, 1123, 2445)),
    )

    @Test fun aPokemonPageIsNotTheItemBag() {
        val p = ItemParser.parse(pokemonPage, 2589)
        assertTrue(p.counts.isEmpty()); assertNull(p.capacity); assertNull(p.used)
    }

    @Test fun theBagFillIsStillRead() {
        val p = ItemParser.parse(listOf(Token("342 / 350", Box(800, 150, 1000, 200)), Token("Poke Ball x20", Box(100, 600, 700, 680))), 2589)
        assertEquals(350, p.capacity); assertEquals(342, p.used); assertEquals(20, p.counts["Poke Ball"])
    }
}
