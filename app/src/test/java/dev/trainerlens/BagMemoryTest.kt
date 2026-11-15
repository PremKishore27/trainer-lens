package dev.trainerlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BagMemoryTest {
    @Test fun itemSeenOnTwoFramesInARowIsConfirmed() {
        val m = BagMemory()
        m.observe(mapOf("Potion" to 84)); m.observe(mapOf("Potion" to 84))
        assertEquals(mapOf("Potion" to 84), m.confirmed()); assertTrue(m.unconfirmed().isEmpty())
    }

    /** The bug: an item that scrolled past in one frame used to be forgotten. Now it is remembered, but flagged. */
    @Test fun itemSeenOnlyOnceIsRememberedButNotConfirmed() {
        val m = BagMemory()
        m.observe(mapOf("Potion" to 84)); m.observe(mapOf("Revive" to 30)); m.observe(emptyMap())
        assertEquals(2, m.size)
        assertEquals(mapOf("Potion" to 84, "Revive" to 30), m.counts())
        assertTrue(m.confirmed().isEmpty())
        assertEquals(setOf("Potion", "Revive"), m.unconfirmed().keys)
    }

    @Test fun scrollingThroughAWholeListRemembersEveryItem() {
        val items = (1..25).associate { "Item$it" to it * 3 }.toList()
        val m = BagMemory()
        // each frame shows 6 rows and the list moves 4 rows between frames, so rows are seen once or twice
        var top = 0
        while (top < items.size) { m.observe(items.drop(top).take(6).toMap()); top += 4 }
        assertEquals(25, m.size)
        assertEquals(items.toMap(), m.counts())
    }

    @Test fun aStableReadingBeatsALaterMisread() {
        val m = BagMemory()
        m.observe(mapOf("Poke Ball" to 84)); m.observe(mapOf("Poke Ball" to 84)); m.observe(mapOf("Poke Ball" to 34))
        assertEquals(84, m.counts()["Poke Ball"])
    }

    @Test fun aStableReadingBeatsAnEarlierMisread() {
        val m = BagMemory()
        m.observe(mapOf("Poke Ball" to 14)); m.observe(mapOf("Poke Ball" to 140)); m.observe(mapOf("Poke Ball" to 140))
        assertEquals(140, m.counts()["Poke Ball"]); assertEquals(140, m.confirmed()["Poke Ball"])
    }

    @Test fun sameReadingOnTwoSeparateFramesIsConfirmed() {
        val m = BagMemory()
        m.observe(mapOf("Revive" to 30)); m.observe(emptyMap()); m.observe(mapOf("Revive" to 30))
        assertEquals(30, m.confirmed()["Revive"])
    }

    @Test fun whenTwoReadingsTieTheNewestWins() {
        val m = BagMemory()
        m.observe(mapOf("Potion" to 10)); m.observe(emptyMap()); m.observe(mapOf("Potion" to 9))
        assertEquals(9, m.counts()["Potion"]) // the player used one
    }

    @Test fun clearForgetsEverything() {
        val m = BagMemory()
        m.observe(mapOf("Potion" to 5)); m.observe(mapOf("Potion" to 5)); m.clear()
        assertEquals(0, m.size); assertTrue(m.counts().isEmpty()); assertFalse(m.confirmed().containsKey("Potion"))
    }

    @Test fun newerItemsAreRecognised() {
        val parsed = ItemParser.parse(listOf(Token("Rocket Radar x3"), Token("Mysterious Component x12"), Token("Razor Claw x2")), 0)
        assertEquals(mapOf("Rocket Radar" to 3, "Mysterious Component" to 12, "Razor Claw" to 2), parsed.counts)
    }
}
