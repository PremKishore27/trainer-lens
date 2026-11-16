package dev.trainerlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The overlay must never hide what the IV checker reads, and its parts must fit their fixed sizes.
 * The token positions come from a real "Copy screen text" dump (a 1080 x 2589 phone), as fractions of the height.
 */
class OverlayTest {
    private val H = 2589
    /** Real positions on a Pokémon page: CP, name, HP, size / types, stardust cost, caught line. */
    private val cp = Box(389, 134, 738, 229)
    private val name = Box(541, 1041, 854, 1126)
    private val hp = Box(537, 1246, 726, 1276)
    private val types = Box(515, 1498, 761, 1523)
    private val dust = Box(369, 1648, 485, 1704)

    private data class Phone(val w: Int, val h: Int, val density: Float)
    private val phones = listOf(
        Phone(1080, 2589, 2.6f), Phone(1080, 2400, 2.625f), Phone(1080, 2340, 2.75f),
        Phone(1440, 3120, 3.5f), Phone(720, 1600, 2.0f), Phone(1080, 2640, 2.7f),
    )

    /** y of a real-dump token on another phone: the page layout scales with the screen height. */
    private fun y(v: Int, p: Phone) = v * p.h / H.toDouble()

    @Test fun windowIsBelowTheCpLineAndAboveHpTypesAndDustOnTypicalPhones() {
        for (p in phones) {
            val r = OverlayLayout.panelRect(p.w, p.h, p.density)
            assertTrue("window top ${r.top} covers the CP line on ${p.w}x${p.h}", r.top > y(cp.bottom, p))
            assertTrue("window bottom ${r.bottom} covers the HP line on ${p.w}x${p.h}", r.bottom < y(hp.top, p))
            assertTrue(r.bottom < y(types.top, p) && r.bottom < y(dust.top, p))
            assertTrue("window must be on screen on ${p.w}x${p.h}", r.left >= 0 && r.right <= p.w && r.bottom <= p.h)
        }
    }

    @Test fun bothWindowSizesStayBetweenCpAndHp() {
        for (scale in listOf(WindowSize.SMALL, WindowSize.LARGE)) for (p in phones) {
            val r = OverlayLayout.panelRect(p.w, p.h, p.density, scale)
            assertTrue("scale $scale: top ${r.top} covers CP on ${p.w}x${p.h}", r.top > y(cp.bottom, p))
            assertTrue("scale $scale: bottom ${r.bottom} covers HP on ${p.w}x${p.h}", r.bottom < y(hp.top, p))
            assertTrue(r.left >= 0 && r.right <= p.w)
        }
        assertTrue(WindowSize.SMALL < 1f && WindowSize.LARGE > 1f && WindowSize.LARGE <= 1.2f)
    }

    @Test fun windowMayOnlyCoverThePokemonNameOnShortScreens() {
        val p = Phone(720, 1600, 2.0f)
        val r = OverlayLayout.panelRect(p.w, p.h, p.density)
        // the name line is a nice-to-have clue (candy and types identify the species too); HP is what must stay visible
        println("720x1600: window bottom ${r.bottom}, name line at ${y(name.top, p).toInt()}, HP line at ${y(hp.top, p).toInt()}")
        assertTrue(r.bottom < y(hp.top, p))
    }

    @Test fun ivCardFitsItsFixedHeight() {
        assertTrue("IV card needs ${Metrics.IV_NEEDED}dp, has ${Metrics.INNER_H}dp", Metrics.IV_NEEDED <= Metrics.INNER_H)
    }

    @Test fun bagPanelLeavesRoomForTheList() {
        assertTrue("bag list only ${Metrics.BAG_LIST}dp tall", Metrics.BAG_LIST >= 2 * Metrics.BAG_ROW + 8)
    }

    @Test fun maskIsTheWindowWhenTheBitmapIsTheDisplay() {
        val m = OverlayMask.map(Box(100, 300, 500, 900), 1080, 2400, 1080, 2400, 0)
        assertEquals(Box(100, 300, 500, 900), m)
    }

    @Test fun maskFollowsTheMirrorWhenTheBitmapIsSmallerThanTheDisplay() {
        // real display 1080x2640, capture bitmap 1080x2589: the mirror scales by 0.98068 and centres horizontally
        val m = OverlayMask.map(Box(0, 0, 1080, 2640), 1080, 2640, 1080, 2589, 0)
        assertTrue(m.top == 0 && m.bottom == 2589)
        assertTrue("left ${m.left}", m.left in 9..11)
        val win = OverlayMask.map(Box(100, 1000, 500, 1500), 1080, 2640, 1080, 2589, 0)
        assertTrue("top ${win.top}", win.top in 979..981)     // 1000 * 0.98068
        assertTrue("bottom ${win.bottom}", win.bottom in 1472..1474)
    }

    @Test fun maskStaysInsideTheBitmap() {
        val m = OverlayMask.map(Box(-50, -50, 5000, 5000), 1080, 2400, 1080, 2400, 20)
        assertEquals(Box(0, 0, 1080, 2400), m)
    }

    @Test fun maskWorksWithoutKnowingTheDisplaySize() {
        assertEquals(Box(90, 290, 510, 910), OverlayMask.map(Box(100, 300, 500, 900), 0, 0, 1080, 2400, 10))
    }

    @Test fun cpIsReadableWithoutHp() {
        val tokens = listOf(Token("CP 3259", cp), Token("POWER UP", Box(300, 2000, 500, 2044)))
        assertEquals(3259, SummaryParser.cp(tokens, H))
        assertNull(SummaryParser.hp(tokens)); assertNull(SummaryParser.parse(tokens, H))
        assertTrue(SummaryParser.hasPowerUp(tokens))
        assertFalse(SummaryParser.hasPowerUp(listOf(Token("CP 3259", cp))))
    }

    @Test fun cpInTheMoveListIsNotTheCp() {
        assertNull(SummaryParser.cp(listOf(Token("CP 99", Box(0, 2000, 100, 2040))), H))
    }
}
