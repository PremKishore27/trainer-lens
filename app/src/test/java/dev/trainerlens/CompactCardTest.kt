package dev.trainerlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The compact IV card (small window): when it is used, that it fits, and the one-line league answer it gives. */
class CompactCardTest {
    @Test fun smallWindowIsCompactAndNormalOrBiggerIsFull() {
        assertFalse(CardMode.isCompact(OverlayScale.DEFAULT, false))   // the normal size keeps the full card, as before
        assertFalse(CardMode.isCompact(OverlayScale.MAX, false))
        assertFalse(CardMode.isCompact(0.9f, false))
        assertTrue(CardMode.isCompact(0.89f, false))
        assertTrue(CardMode.isCompact(OverlayScale.MIN, false))
        assertFalse(CardMode.isCompact(Float.NaN, true))
    }

    @Test fun switchingBackNeedsAClearStepSoItDoesNotFlicker() {
        assertTrue(CardMode.isCompact(0.92f, true))     // already compact: stays until the window is a bit bigger
        assertFalse(CardMode.isCompact(0.93f, true))
        assertFalse(CardMode.isCompact(0.92f, false))   // already full: stays full at the same size
        // dragging the edge through the whole range changes the form once each way, never back and forth
        var compact = false; var changes = 0
        var s = OverlayScale.MAX
        while (s > OverlayScale.MIN) { val n = CardMode.isCompact(s, compact); if (n != compact) changes++; compact = n; s -= 0.005f }
        while (s < OverlayScale.MAX) { val n = CardMode.isCompact(s, compact); if (n != compact) changes++; compact = n; s += 0.005f }
        assertEquals(2, changes)
    }

    @Test fun compactCardFitsTheFixedContentArea() {
        assertTrue(Metrics.COMPACT_NEEDED <= Metrics.INNER_H)
        assertTrue(3 * Metrics.COMPACT_BAR_ROW <= Metrics.COMPACT_RING)
        assertTrue("the compact form is not taller than the full one", Metrics.COMPACT_NEEDED <= Metrics.INNER_H)
    }

    private fun rows(vararg m: Mark) = listOf("Little", "Great", "Ultra", "Master").zip(m.toList()).map { (n, k) -> LeagueRow(n, "x", k) }

    @Test fun leaguePickNamesEveryGoodLeague() {
        assertEquals(LeaguePick("Good for Great · Master", Mark.GOOD), CardModels.leaguePick(rows(Mark.NONE, Mark.GOOD, Mark.BAD, Mark.GOOD)))
        assertEquals(LeaguePick("Good for Little", Mark.GOOD), CardModels.leaguePick(rows(Mark.GOOD, Mark.MAYBE, Mark.NONE, Mark.NONE)))
    }

    @Test fun maybeIsOnlyUsedWhenNothingIsGood() {
        assertEquals(LeaguePick("Maybe Great · Ultra", Mark.MAYBE), CardModels.leaguePick(rows(Mark.NONE, Mark.MAYBE, Mark.MAYBE, Mark.BAD)))
    }

    @Test fun noLeagueFit() {
        assertEquals(LeaguePick("No league fit", Mark.BAD), CardModels.leaguePick(rows(Mark.BAD, Mark.BAD, Mark.NONE, Mark.NONE)))
        assertEquals(LeaguePick("No league fit", Mark.NONE), CardModels.leaguePick(rows(Mark.NONE, Mark.NONE, Mark.NONE, Mark.NONE)))
        assertEquals(LeaguePick("No league fit", Mark.NONE), CardModels.leaguePick(emptyList()))
    }

    @Test fun theModelAnswersWithItsOwnLeagueRows() {
        val m = CardModel(title = "x", leagues = rows(Mark.NONE, Mark.GOOD, Mark.NONE, Mark.NONE))
        assertEquals(LeaguePick("Good for Great", Mark.GOOD), m.leaguePick)
    }
}
