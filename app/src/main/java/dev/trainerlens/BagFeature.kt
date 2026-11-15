package dev.trainerlens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

internal enum class BagRowKind { HEADER, ITEM, TEXT }

/**
 * Feature 1: bag/item storage advisor.
 * Remembers every item it reads during the session (until Reset or Stop), so you can scroll the whole Items list once.
 * A reading only counts when the same item + count is seen on two frames in a row (the list isn't moving), and the
 * count shown is the one read most often – this filters out misreads while scrolling.
 *
 * The panel has a fixed size (header, fill bar, one line of advice, a scrolling list, three buttons).
 */
class BagFeature(private val ctx: Context) {
    /** item -> (count -> how many stable frames showed it) */
    private val votes = mutableMapOf<String, MutableMap<Int, Int>>()
    private var lastFrame: Map<String, Int> = emptyMap()
    private var used: Int? = null
    private var capacity: Int? = null
    private var targetFree = 30

    /** One line of the list: a section header, an item with an amount, or a paragraph. */
    private data class Row(val kind: BagRowKind, val left: String, val right: String = "", val color: Int = Ui.TEXT)

    private fun dp(v: Int) = Ui.dp(ctx, v)

    private val title = Ui.label(ctx, 17f, Ui.TEXT, bold = true).apply { text = "Bag" }
    private val fillText = Ui.label(ctx, 13f, Ui.TEXT2, gravity = Gravity.END or Gravity.CENTER_VERTICAL)
    private val fill = BarView(ctx)
    private val sub = TextView(ctx).apply {
        textSize = 12f; setTextColor(Ui.TEXT2); maxLines = 2; ellipsize = TextUtils.TruncateAt.END
    }
    private val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; addView(list) }
    private var shownKey = ""

    val panel: View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        header.addView(fillText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(26)))
        addView(fill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply { topMargin = dp(4) })
        addView(sub, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)).apply { topMargin = dp(6) })
        addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val buttons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        fun add(b: View, first: Boolean) =
            buttons.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (!first) marginStart = dp(6) })
        add(Ui.button(ctx, "Free +10") { targetFree += 10; refresh() }, true)
        add(Ui.button(ctx, "Reset") { reset() }, false)
        add(Ui.button(ctx, "Copy log") { copyDebug() }, false)
        addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)).apply { topMargin = dp(8) })
    }

    init { refresh() }

    private fun reset() { votes.clear(); lastFrame = emptyMap(); used = null; capacity = null; targetFree = 30; refresh() }

    /** Best reading per item so far. */
    private fun counts(): Map<String, Int> = votes.mapValues { (_, v) -> v.maxBy { it.value }.key }

    private var lastTokens: List<Token> = emptyList()
    private var lastScreenH = 0

    /** Copies what the OCR read on the last frame (text + position) so it can be pasted into a bug report. */
    private fun copyDebug() {
        val p = ItemParser.parse(lastTokens, lastScreenH)
        val txt = buildString {
            append("Trainer Lens ${BuildConfig.VERSION_NAME} · screen height $lastScreenH\n")
            append("Parsed: ${p.counts} · bag ${p.used}/${p.capacity}\n---\n")
            lastTokens.sortedBy { it.box.top }.forEach { append("${it.text}  @${it.box.left},${it.box.top},${it.box.right},${it.box.bottom}\n") }
        }
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Trainer Lens OCR", txt))
        Toast.makeText(ctx, String.format(Locale.ROOT, "Copied %d text lines", lastTokens.size), Toast.LENGTH_SHORT).show()
    }

    fun onTokens(tokens: List<Token>, screenH: Int) {
        lastTokens = tokens; lastScreenH = screenH
        val p = ItemParser.parse(tokens, screenH)
        if (p.counts.isEmpty() && p.capacity == null) { lastFrame = emptyMap(); return }
        if (p.used != null) { used = p.used; capacity = p.capacity }
        // only trust pairs that were identical on the previous frame
        for ((item, n) in p.counts) if (lastFrame[item] == n) {
            val v = votes.getOrPut(item) { mutableMapOf() }
            v[n] = (v[n] ?: 0) + 1
        }
        lastFrame = p.counts
        refresh()
    }

    private fun refresh() {
        val u = used; val c = capacity
        val seen = counts()
        val rows = ArrayList<Row>()
        val fillFrac: Float; val fillColor: Int; val fillLabel: String; val advice: String
        if (u == null || c == null) {
            fillFrac = 0f; fillColor = Ui.GREY; fillLabel = ""
            advice = "Open the Items screen so I can read how full the bag is (e.g. 342/350)."
            rows += Row(BagRowKind.TEXT, "Remembered this session: ${seen.size} item types.", color = Ui.TEXT2)
        } else {
            val plan = Recommender.plan(seen, u, c, targetFree)
            val free = c - u
            fillFrac = u.toFloat() / c; fillLabel = "$u / $c"
            fillColor = when { free >= targetFree -> Ui.GREEN; free > 0 -> Ui.AMBER; else -> Ui.RED }
            if (plan.needed > 0) {
                advice = "Free ${plan.needed} slots to reach $targetFree free."
                rows += Row(BagRowKind.HEADER, "Discard")
                plan.discards.forEach {
                    rows += Row(BagRowKind.ITEM, it.item, "−${it.amount}" + if (it.belowKeep) " · below keep limit" else "",
                        if (it.belowKeep) Ui.RED else Ui.AMBER)
                }
                if (plan.freed < plan.needed) {
                    rows += Row(BagRowKind.TEXT, "Scroll the whole list for a better plan (short by ${plan.needed - plan.freed}).", color = Ui.TEXT2)
                }
            } else advice = "$free free slots – no need to discard yet."

            val review = Recommender.review(seen)
            rows += Row(BagRowKind.HEADER, "Over keep limit")
            if (review.remove.isEmpty()) rows += Row(BagRowKind.TEXT, "Nothing above its keep limit.", color = Ui.TEXT2)
            else review.remove.forEach { rows += Row(BagRowKind.ITEM, it.item, "−${it.amount}", Ui.AMBER) }
            if (review.keep.isNotEmpty()) {
                rows += Row(BagRowKind.HEADER, "Keep")
                rows += Row(BagRowKind.TEXT, review.keep.joinToString(" · "), color = Ui.TEXT2)
            }
            if (seen.isEmpty()) rows += Row(BagRowKind.TEXT,
                "No items recognised yet. Scroll slowly through the Items list, or tap Copy log and send me the text.", color = Ui.TEXT2)
        }

        // Rebuilding the list would reset its scroll position, so only do it when something changed.
        val key = "$fillLabel|$advice|$rows"
        if (key == shownKey) return
        shownKey = key
        fillText.text = fillLabel
        fill.set(fillFrac, fillFrac, fillColor)
        sub.text = advice
        list.removeAllViews()
        for (r in rows) list.addView(build(r))
    }

    private fun build(r: Row): View = when (r.kind) {
        BagRowKind.HEADER -> Ui.label(ctx, 11f, Ui.TEXT3).apply { text = r.left }.also {
            it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(20)).apply { topMargin = dp(6) }
        }
        BagRowKind.ITEM -> LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(ctx, Ui.CHIP, 10f)
            setPadding(dp(10), 0, dp(10), 0)
            addView(Ui.label(ctx, 13f, Ui.TEXT).apply { text = r.left }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(Ui.label(ctx, 12f, r.color, gravity = Gravity.END or Gravity.CENTER_VERTICAL).apply { text = r.right },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)).apply { topMargin = dp(4) }
        }
        BagRowKind.TEXT -> TextView(ctx).apply {
            textSize = 12f; setTextColor(r.color); text = r.left
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
        }
    }
}
