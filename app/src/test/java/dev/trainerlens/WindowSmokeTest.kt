package dev.trainerlens

import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import android.widget.TextView

/**
 * The overlay window at the smallest, normal and largest size, and the launcher screen on the oldest and newest
 * supported Android. A crash while building them, or a part cut off at a small size, shows up here and not on a phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34])
class WindowSmokeTest {
    private val ctx get() = RuntimeEnvironment.getApplication()
    private fun px(dp: Int) = Ui.dp(ctx, dp)

    @Test fun windowIsLaidOutFullyAtEveryScale() {
        for (s in listOf(OverlayScale.MIN, 0.9f, OverlayScale.DEFAULT, OverlayScale.MAX)) {
            val w = OverlayWindow(ctx, px(Metrics.PANEL_W), px(52), {}, {}, {}, {})
            w.showTab(View(ctx), bagActive = false); w.showTab(View(ctx), bagActive = true) // switching tabs twice must not crash
            w.setOpen(true); w.applyScale(s)
            val (fw, fh) = w.footprint(true, s)
            w.root.measure(View.MeasureSpec.makeMeasureSpec(fw, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(fh, View.MeasureSpec.EXACTLY))
            w.root.layout(0, 0, fw, fh)
            // the panel keeps its full design size however small the window is, so nothing is cut off before scaling
            assertEquals("scale $s", w.panelHeightPx, w.panel.measuredHeight)
            assertEquals("scale $s", w.panelWidthPx, w.panel.measuredWidth)
            assertTrue("scale $s: grip bottom ${w.grip.bottom} vs ${w.panelHeightPx}", Math.abs(w.grip.bottom - w.panelHeightPx) <= 1)
            assertTrue("grip too thin: ${w.grip.height}px", w.grip.height >= px(24))
            assertTrue("content overlaps the grip", w.contentFrame.bottom <= w.grip.top)
            assertTrue(w.contentFrame.height >= px(Metrics.CONTENT_H) - 2)
        }
    }

    @Test fun bubbleStateIsJustTheBubble() {
        val w = OverlayWindow(ctx, px(Metrics.PANEL_W), px(52), {}, {}, {}, {})
        w.setOpen(false)
        assertEquals(px(52) to px(52), w.footprint(false, 1.3f))
        assertEquals(View.VISIBLE, w.bubble.visibility); assertEquals(View.GONE, w.panel.visibility)
    }

    @Test fun setupScreenLaunches() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        for (id in listOf(R.id.start, R.id.step1, R.id.step2, R.id.step3, R.id.step4, R.id.badge1, R.id.howto, R.id.note, R.id.version, R.id.linkGithub, R.id.linkIssue))
            assertNotNull("view $id", a.findViewById<View>(id))
        assertTrue(a.findViewById<TextView>(R.id.version).text.startsWith("Version"))
        assertTrue(a.findViewById<TextView>(R.id.howto).text.contains("bottom edge"))
        assertEquals("1", a.findViewById<TextView>(R.id.badge1).text.toString().let { if (it == "✓") "1" else it })
    }
}
