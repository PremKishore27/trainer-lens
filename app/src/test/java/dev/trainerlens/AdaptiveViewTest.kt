package dev.trainerlens

import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The IV card's compact form and the bag's red / green pills, checked on the real views. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AdaptiveViewTest {
    private val ctx get() = RuntimeEnvironment.getApplication()
    private fun px(dp: Int) = Ui.dp(ctx, dp)
    private val innerW get() = Metrics.PANEL_W - 2 * Metrics.WINDOW_PAD - 2 * Metrics.CONTENT_PAD

    private fun layOut(v: View) {
        val w = px(innerW); val h = px(Metrics.INNER_H)
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        assertEquals(h, v.measuredHeight); assertEquals(w, v.measuredWidth)
    }

    /** Texts of everything that is not hidden (GONE). */
    private fun visibleTexts(v: View, out: MutableList<String> = ArrayList()): List<String> {
        if (v.visibility == View.GONE) return out
        if (v is TextView) out += v.text.toString()
        if (v is ViewGroup) for (i in 0 until v.childCount) visibleTexts(v.getChildAt(i), out)
        return out
    }

    private fun bottomIn(v: View, root: View): Int {
        var y = v.height; var c: View? = v
        while (c != null && c !== root) { y += c.top; c = c.parent as? View }
        return y
    }

    private fun allViews(v: View, out: MutableList<View> = ArrayList()): List<View> {
        out += v
        if (v is ViewGroup) for (i in 0 until v.childCount) allViews(v.getChildAt(i), out)
        return out
    }

    private val result = CardModel(
        title = "Dragonite", tone = Tone.GREAT, pct = "89%", pctSub = "40/45", ring = 0.89f,
        stats = listOf(StatBar(14, 14), StatBar(13, 13), StatBar(13, 13)), chips = listOf("Lv 39", "CP 3259", "HP 163"),
        leagues = listOf(LeagueRow("Little", "—", Mark.NONE), LeagueRow("Great", "#12 ✓", Mark.GOOD),
            LeagueRow("Ultra", "CP too high", Mark.NONE), LeagueRow("Master", "Good IVs", Mark.GOOD)),
        status = "Excellent ★★★ · exact",
    )

    @Test fun fullCardShowsEverythingAsBefore() {
        val v = IvCardView(ctx); v.bind(result); layOut(v)
        val t = visibleTexts(v)
        for (want in listOf("Dragonite", "ATK", "DEF", "HP", "14", "Lv 39", "CP 3259", "HP 163", "Little", "Great", "Ultra", "Master", "#12 ✓", "Good IVs",
            "Excellent ★★★ · exact", "Copy log")) assertTrue("$want in $t", want in t)
        assertFalse("no league pill in the full card: $t", t.any { it.startsWith("Good for") })
    }

    @Test fun compactCardKeepsOnlyTheKeyNumbers() {
        val v = IvCardView(ctx); v.bind(result); v.setCompact(true); layOut(v)
        val t = visibleTexts(v)
        for (want in listOf("Dragonite", "ATK", "DEF", "HP", "14", "13", "Good for Great · Master")) assertTrue("$want in $t", want in t)
        for (gone in listOf("Lv 39", "CP 3259", "HP 163", "Little", "Ultra", "#12 ✓", "Excellent ★★★ · exact", "Copy log"))
            assertFalse("$gone should be hidden in $t", gone in t)
    }

    @Test fun compactFormFitsInsideTheCardAndHasABiggerRing() {
        val v = IvCardView(ctx); v.bind(result); v.setCompact(true); layOut(v)
        val ring = allViews(v).filterIsInstance<RingView>().single()
        assertEquals(px(Metrics.COMPACT_RING), ring.height); assertEquals(px(Metrics.COMPACT_RING), ring.width)
        val pick = allViews(v).filterIsInstance<TextView>().single { it.text.startsWith("Good for") }
        assertTrue("league pill sticks out: ${bottomIn(pick, v)} > ${v.height}", bottomIn(pick, v) <= v.height)
        assertTrue(pick.height == px(Metrics.COMPACT_PICK))
    }

    @Test fun messagesLookTheSameInBothForms() {
        for (c in listOf(CardModels.idle(), CardModels.idle(true), CardModels.message("Can't read the HP line", "x\ny"))) {
            val full = IvCardView(ctx).apply { bind(c) }; layOut(full)
            val small = IvCardView(ctx).apply { setCompact(true); bind(c) }; layOut(small)
            assertEquals(visibleTexts(full), visibleTexts(small))
            assertTrue("Copy log" in visibleTexts(small))
        }
    }

    @Test fun switchingFormsKeepsTheCardSizeAndComesBackToTheFullCard() {
        val v = IvCardView(ctx)
        val full = run { v.bind(result); layOut(v); visibleTexts(v) }
        for (on in listOf(true, false, true, true, false)) { v.setCompact(on); layOut(v) }
        v.bind(CardModels.idle()); layOut(v); v.bind(result); layOut(v)
        assertEquals(full, visibleTexts(v))                                  // back to exactly the full card
        v.setCompact(true); v.bind(CardModels.idle()); layOut(v); v.bind(result); layOut(v)
        assertTrue("Good for Great · Master" in visibleTexts(v))              // a result after a message is compact again
    }

    // ---- bag pills

    private fun fillOf(v: View): Int? = ((v.background as? GradientDrawable)?.color)?.defaultColor

    @Test fun bagShowsDiscardsInRedAndKeepsInGreen() {
        val bag = BagFeature(ctx)
        val items = listOf(Token("342 / 350", Box(800, 150, 1000, 200)), Token("Poke Ball x84", Box(100, 600, 700, 680)),
            Token("Potion x40", Box(100, 800, 700, 880)), Token("Revive x10", Box(100, 1000, 700, 1080)),
            Token("Hyper Potion x5", Box(100, 1200, 700, 1280)))
        bag.onTokens(items, 2400); bag.onTokens(items, 2400)
        val rows = allViews(bag.panel).filterIsInstance<ViewGroup>().filter { it !== bag.panel && fillOf(it) != null }
        val red = rows.filter { fillOf(it) == Ui.RED_FILL }
        val green = rows.filter { fillOf(it) == Ui.GREEN_FILL }
        assertTrue("red pills: ${red.map { visibleTexts(it) }}", red.size >= 3)
        for (r in red) {                                            // every red pill is an item to discard: "Name" and "−N"
            val t = visibleTexts(r); assertTrue(t.toString(), t.size == 2 && t[1].startsWith("−"))
        }
        assertTrue(red.any { "Poke Ball" in visibleTexts(it) } && red.any { "Potion" in visibleTexts(it) })
        assertEquals(listOf(listOf("Hyper Potion", "×5"), listOf("Revive", "×10")), green.map { visibleTexts(it) }.sortedBy { it[0] })
        // nothing recommended for discard is green and nothing to keep is red
        assertTrue(green.none { "Poke Ball" in visibleTexts(it) || "Potion" in visibleTexts(it) })
        assertTrue(red.none { "Revive" in visibleTexts(it) || "Hyper Potion" in visibleTexts(it) })
        // the amount is coloured to match
        for (r in red) assertEquals(Ui.RED, (r as ViewGroup).let { (it.getChildAt(1) as TextView).currentTextColor })
        for (g in green) assertEquals(Ui.GREEN, (g as ViewGroup).let { (it.getChildAt(1) as TextView).currentTextColor })
    }

    @Test fun pillsAreFullyRoundedAndKeepTheRowHeight() {
        val bag = BagFeature(ctx)
        val items = listOf(Token("Poke Ball x84", Box(100, 600, 700, 680)), Token("Revive x10", Box(100, 1000, 700, 1080)))
        bag.onTokens(items, 2400); bag.onTokens(items, 2400)
        val pills = allViews(bag.panel).filter { fillOf(it) == Ui.RED_FILL || fillOf(it) == Ui.GREEN_FILL }
        assertTrue(pills.isNotEmpty())
        for (p in pills) {
            assertEquals(px(Metrics.BAG_ROW), p.layoutParams.height)
            assertEquals(Ui.dpf(ctx, Metrics.BAG_ROW / 2f), (p.background as GradientDrawable).cornerRadius, 0.5f)
        }
    }

    @Test fun unconfirmedAndPlainRowsAreNotHighlighted() {
        val bag = BagFeature(ctx)
        val items = listOf(Token("Poke Ball x84", Box(100, 600, 700, 680)))
        bag.onTokens(items, 2400)                                   // seen once: remembered but not advised
        val coloured = allViews(bag.panel).filter { fillOf(it) == Ui.RED_FILL || fillOf(it) == Ui.GREEN_FILL }
        assertTrue("a single reading must not be highlighted: ${coloured.map { visibleTexts(it) }}", coloured.isEmpty())
    }
}
