package dev.trainerlens

import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Builds the overlay's views and fills them with every kind of card, so a crash while creating or binding them
 * (a view added twice, a bad layout) shows up here and not on a phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OverlaySmokeTest {
    private val ctx get() = RuntimeEnvironment.getApplication()
    private fun px(dp: Int) = Ui.dp(ctx, dp)
    private val innerW get() = Metrics.PANEL_W - 2 * Metrics.WINDOW_PAD - 2 * Metrics.CONTENT_PAD

    private fun layOut(v: View) {
        val w = px(innerW); val h = px(Metrics.INNER_H)
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        assertEquals(h, v.measuredHeight); assertEquals(w, v.measuredWidth)
    }

    private val result = CardModel(
        title = "Dragonite", badge = "reading…", tone = Tone.GREAT, pct = "89%", pctSub = "40/45", ring = 0.89f,
        stats = listOf(StatBar(13, 13), StatBar(14, 14), StatBar(10, 15)), chips = listOf("Lv 31", "CP 3259", "HP 163"),
        leagues = listOf(LeagueRow("Little", "—", Mark.NONE), LeagueRow("Great", "#12 ✓", Mark.GOOD),
            LeagueRow("Ultra", "CP too high", Mark.NONE), LeagueRow("Master", "Good IVs", Mark.GOOD)),
        status = "Excellent ★★★ · exact",
        others = listOf(Species("a", "Cutiefly", 1, 1, 1), Species("b", "Dragonair", 1, 1, 1)),
    )

    @Test fun ivCardShowsEveryKindOfCard() {
        val v = IvCardView(ctx)
        val cards = listOf(CardModels.idle(), CardModels.idle(true), CardModels.message("Loading data…", "a\nb"),
            CardModels.message("Can't read the HP line", "x\ny"), result, result.copy(badge = null, others = emptyList()), CardModels.idle())
        for (c in cards) { v.bind(c); layOut(v) }
    }

    @Test fun bagPanelShowsTheWalkthroughThenTheList() {
        val bag = BagFeature(ctx)
        layOut(bag.panel)                                                   // nothing read yet: "Go to your Item bag"
        val items = listOf(Token("342 / 350", Box(800, 150, 1000, 200)), Token("Poke Ball x84", Box(100, 600, 700, 680)),
            Token("Potion x40", Box(100, 800, 700, 880)))
        bag.onTokens(items, 2400); bag.onTokens(items, 2400)
        layOut(bag.panel)                                                   // on the Items screen
        bag.onTokens(emptyList(), 2400)
        layOut(bag.panel)
    }

    @Test fun ivFeatureAndArtViewsBuild() {
        val iv = IvFeature(ctx) { it.run() }
        iv.onItemsScreen = true
        layOut(iv.panel)
        val art = ArtView(ctx)
        for (a in Art.values()) art.set(a)
        assertTrue(Art.values().size == 3)
    }
}
