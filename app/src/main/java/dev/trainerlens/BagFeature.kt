package dev.trainerlens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/** DISCARD = a red pill (recommended to throw out), KEEP = a green pill (recommended to keep); the rest is plain. */
internal enum class BagRowKind { HEADER, ITEM, DISCARD, KEEP, TEXT, ART }

/**
 * Feature 1: bag/item storage advisor.
 * Remembers every item it reads during the session (until Reset or Stop), so you can scroll the whole Items list once.
 * See [BagMemory] for how readings are confirmed: items seen only once are remembered but kept out of the advice.
 *
 * The panel has a fixed size (header, fill bar, one line of advice, a scrolling list, three buttons; see [Metrics]).
 */
class BagFeature(private val ctx: Context) {
    private val memory = BagMemory()
    private var used: Int? = null
    private var capacity: Int? = null
    private var targetFree = 30
    @Volatile private var lastItemsSeen = -1_000_000L

    /** One line of the list: a section header, an item with an amount, or a paragraph. */
    private data class Row(val kind: BagRowKind, val left: String, val right: String = "", val color: Int = Ui.TEXT, val art: Art = Art.NONE)
    private var shownOnItems = false

    private fun dp(v: Int) = Ui.dp(ctx, v)

    private val title = Ui.label(ctx, 15f, Ui.TEXT, bold = true).apply { text = "Bag" }
    private val fillText = Ui.label(ctx, 12f, Ui.TEXT2, gravity = Gravity.END or Gravity.CENTER_VERTICAL)
    private val fill = BarView(ctx)
    private val advice = Ui.label(ctx, 12f, Ui.TEXT2)
    private val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; addView(list) }
    private var shownKey = ""

    val panel: View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, match))
        header.addView(fillText, LinearLayout.LayoutParams(0, match, 1f).apply { marginStart = dp(8) })
        addView(header, LinearLayout.LayoutParams(match, dp(Metrics.BAG_HEADER)))
        addView(fill, LinearLayout.LayoutParams(match, dp(Metrics.BAG_BAR)).apply { topMargin = dp(Metrics.BAG_GAP) })
        addView(advice, LinearLayout.LayoutParams(match, dp(Metrics.BAG_ADVICE)).apply { topMargin = dp(Metrics.BAG_GAP) })
        addView(scroll, LinearLayout.LayoutParams(match, 0, 1f).apply { topMargin = dp(Metrics.BAG_GAP) })
        val buttons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        fun add(b: View, first: Boolean) =
            buttons.addView(b, LinearLayout.LayoutParams(0, match, 1f).apply { if (!first) marginStart = dp(5) })
        add(Ui.button(ctx, "Free +10", 4) { targetFree += 10; refresh() }, true)
        add(Ui.button(ctx, "Reset", 4) { reset() }, false)
        add(Ui.button(ctx, "Copy log", 4) { copyDebug() }, false)
        addView(buttons, LinearLayout.LayoutParams(match, dp(Metrics.BAG_BUTTONS)).apply { topMargin = dp(Metrics.BAG_GAP) })
    }

    init { refresh() }

    /** The Items screen was on screen within the last 3 s: worth scanning fast so scrolled-past items aren't missed. */
    fun recentlySeen(now: Long) = now - lastItemsSeen < 3_000

    private fun reset() { memory.clear(); used = null; capacity = null; targetFree = 30; shownKey = ""; refresh() }

    private var lastTokens: List<Token> = emptyList()
    private var lastScreenH = 0

    /** Copies what the OCR read on the last frame (text + position) so it can be pasted into a bug report. */
    private fun copyDebug() {
        val p = ItemParser.parse(lastTokens, lastScreenH)
        val txt = buildString {
            append("Trainer Lens ${BuildConfig.VERSION_NAME} · screen height $lastScreenH\n")
            append("Overlay: ${Diag.line}\n")
            Diag.error?.let { append("Last error: $it\n") }
            append("Remembered: ${memory.counts()} · unconfirmed ${memory.unconfirmed().keys}\n")
            append("Parsed: ${p.counts} · bag ${p.used}/${p.capacity}\n---\n")
            lastTokens.sortedBy { it.box.top }.forEach { append("${it.text}  @${it.box.left},${it.box.top},${it.box.right},${it.box.bottom}\n") }
        }
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Trainer Lens OCR", txt))
        Toast.makeText(ctx, String.format(Locale.ROOT, "Copied %d text lines", lastTokens.size), Toast.LENGTH_SHORT).show()
    }

    fun onTokens(tokens: List<Token>, screenH: Int) {
        lastTokens = tokens; lastScreenH = screenH
        val p = ItemParser.parse(tokens, screenH)
        if (p.counts.isEmpty() && p.capacity == null) {
            memory.observe(emptyMap())
            if (recentlySeen(SystemClock.elapsedRealtime()) != shownOnItems) refresh() // the player left the Items screen
            return
        }
        lastItemsSeen = SystemClock.elapsedRealtime()
        if (p.used != null) { used = p.used; capacity = p.capacity }
        memory.observe(p.counts)
        refresh()
    }

    private fun refresh() {
        val u = used; val c = capacity
        val confirmed = memory.confirmed()
        val unconfirmed = memory.unconfirmed()
        val rows = ArrayList<Row>()
        val fillFrac: Float; val fillColor: Int; val fillLabel: String; val adviceText: String
        val types = "${memory.size} types"
        val onItems = recentlySeen(SystemClock.elapsedRealtime())
        shownOnItems = onItems
        val hint = Hints.bag(onItems, memory.size)
        if (hint == BagHint.NOT_ON_ITEMS) rows += Row(BagRowKind.TEXT, Hints.BAG_NOT_ON_ITEMS, color = Ui.TEXT3)
        if (u == null || c == null) {
            fillFrac = 0f; fillColor = Ui.GREY; fillLabel = types
            adviceText = when {
                hint != BagHint.NONE -> Hints.BAG_GO_ADVICE
                confirmed.isEmpty() -> "Reading your bag…"
                else -> "Bag size not read – showing what is over your limit."
            }
            addReview(rows, confirmed) // the over-limit list needs no bag size, so it shows right away
        } else {
            // The advice only uses readings seen twice, so one misread while scrolling can't tell you to discard 40 Potions.
            val plan = Recommender.plan(confirmed, u, c, targetFree)
            val free = c - u
            fillFrac = u.toFloat() / c; fillLabel = "$u / $c · $types"
            fillColor = when { free >= targetFree -> Ui.GREEN; free > 0 -> Ui.AMBER; else -> Ui.RED }
            if (plan.needed > 0) {
                adviceText = "Free ${plan.needed} slots to reach $targetFree free."
                rows += Row(BagRowKind.HEADER, "Discard")
                plan.discards.forEach {
                    rows += Row(BagRowKind.DISCARD, it.item, "−${it.amount}" + if (it.belowKeep) " · below keep limit" else "", Ui.RED)
                }
                if (plan.freed < plan.needed) {
                    rows += Row(BagRowKind.TEXT, "Scroll the whole list for a better plan (short by ${plan.needed - plan.freed}).", color = Ui.TEXT2)
                }
            } else adviceText = "$free free slots – nothing to discard."

            addReview(rows, confirmed)
        }
        if (unconfirmed.isNotEmpty()) {
            rows += Row(BagRowKind.HEADER, "Seen once – scroll slower to confirm")
            rows += Row(BagRowKind.TEXT, unconfirmed.entries.joinToString(" · ") { "${it.key} ×${it.value}" }, color = Ui.TEXT2)
        }
        if (hint == BagHint.GO_TO_BAG) {
            rows += Row(BagRowKind.ART, "", art = Art.BAG)
            rows += Row(BagRowKind.TEXT, Hints.BAG_GO_TEXT, color = Ui.TEXT2)
        } else if (memory.size == 0) rows += Row(BagRowKind.TEXT,
            "No items recognised yet. Scroll slowly through the Items list, or tap Copy log and send me the text.", color = Ui.TEXT2)

        // Rebuilding the list would reset its scroll position, so only do it when something changed.
        val key = "$fillLabel|$adviceText|$rows"
        if (key == shownKey) return
        shownKey = key
        fillText.text = fillLabel
        fill.set(fillFrac, fillFrac, fillColor)
        advice.text = adviceText
        list.removeAllViews()
        for (r in rows) list.addView(build(r))
    }

    /** "Over keep limit" and "Keep" sections for the confirmed items. */
    private fun addReview(rows: MutableList<Row>, confirmed: Map<String, Int>) {
        if (confirmed.isEmpty()) return
        val review = Recommender.review(confirmed)
        rows += Row(BagRowKind.HEADER, "Over keep limit")
        if (review.remove.isEmpty()) rows += Row(BagRowKind.TEXT, "Nothing above its keep limit.", color = Ui.TEXT2)
        else review.remove.forEach { rows += Row(BagRowKind.DISCARD, it.item, "−${it.amount}", Ui.RED) }
        if (review.keep.isNotEmpty()) {
            rows += Row(BagRowKind.HEADER, "Keep")
            review.keep.forEach { k ->   // "Name ×12" -> a green pill with the name and the amount
                val i = k.lastIndexOf(" ×")
                rows += if (i > 0) Row(BagRowKind.KEEP, k.substring(0, i), k.substring(i + 1), Ui.GREEN) else Row(BagRowKind.KEEP, k, "", Ui.GREEN)
            }
        }
    }

    private fun build(r: Row): View = when (r.kind) {
        BagRowKind.HEADER -> Ui.label(ctx, 10f, Ui.TEXT3).apply { text = r.left }.also {
            it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(18)).apply { topMargin = dp(4) }
        }
        BagRowKind.ITEM, BagRowKind.DISCARD, BagRowKind.KEEP -> LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            // whole-row pill: red for what to discard, green for what to keep, the plain chip for anything else
            background = when (r.kind) {
                BagRowKind.DISCARD -> Ui.rounded(ctx, Ui.RED_FILL, Metrics.BAG_ROW / 2f, Ui.RED_EDGE)
                BagRowKind.KEEP -> Ui.rounded(ctx, Ui.GREEN_FILL, Metrics.BAG_ROW / 2f, Ui.GREEN_EDGE)
                else -> Ui.rounded(ctx, Ui.CHIP, 9f)
            }
            setPadding(dp(12), 0, dp(12), 0)
            addView(Ui.label(ctx, 12f, Ui.TEXT).apply { text = r.left }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(Ui.label(ctx, 11f, r.color, gravity = Gravity.END or Gravity.CENTER_VERTICAL).apply { text = r.right },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(Metrics.BAG_ROW)).apply { topMargin = dp(4) }
        }
        BagRowKind.ART -> ArtView(ctx).apply {
            set(r.art)
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { topMargin = dp(6); gravity = Gravity.CENTER_HORIZONTAL }
        }
        BagRowKind.TEXT -> TextView(ctx).apply {
            textSize = 11f; setTextColor(r.color); text = r.left
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
        }
    }
}
