package dev.trainerlens

import android.content.Context
import android.view.Gravity
import android.view.View
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The IV card. Every part has a fixed size (see [Metrics]), so the card, and the window around it, never changes
 * size whatever the Pokémon: long names are cut with "…", empty parts keep their space.
 *
 * Two forms of the same card (see [CardMode]): the full card below, and, when the window is made small, a compact one
 * with only the name, the % ring (bigger), the three IV values and one line saying which league it is good for.
 *
 *   name ............................ badge
 *   ( ring )   ATK ▬▬▬▬▬▬  13
 *   ( 89 % )   DEF ▬▬▬▬▬▬  14
 *              HP  ▬▬▬▬▬▬  13
 *   [ Lv 31 ] [ CP 3259 ] [ HP 163 ]
 *   Little —        Great #12 ✓
 *   Ultra  —        Master Good IVs
 *   status  (or: Not it? [A] [B]) ...... Copy log
 */
class IvCardView(ctx: Context) : LinearLayout(ctx) {
    var onPick: ((Species) -> Unit)? = null
    var onCopy: (() -> Unit)? = null

    private val match = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    private fun dp(v: Int) = Ui.dp(context, v)
    private fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    private val title = Ui.label(ctx, 15f, Ui.TEXT, bold = true)
    private val badge = Ui.label(ctx, 10f, Ui.TEXT, gravity = Gravity.CENTER).apply {
        background = Ui.rounded(ctx, Ui.CHIP, 9f)
        setPadding(Ui.dp(ctx, 7), Ui.dp(ctx, 2), Ui.dp(ctx, 7), Ui.dp(ctx, 2))
    }
    private val ring = RingView(ctx)
    private val statNames = listOf("ATK", "DEF", "HP")
    private val statLabels = statNames.map { Ui.label(ctx, 10f, Ui.TEXT2).apply { text = it } }
    private val statBars = List(3) { BarView(ctx) }
    private val statValues = List(3) { Ui.label(ctx, 11f, Ui.TEXT, gravity = Gravity.END or Gravity.CENTER_VERTICAL) }
    private val chips = List(3) { Ui.label(ctx, 11f, Ui.TEXT, gravity = Gravity.CENTER) }
    private val leagueNames = List(4) { Ui.label(ctx, 10f, Ui.TEXT2) }
    private val leagueValues = List(4) { Ui.label(ctx, 11f, Ui.TEXT, gravity = Gravity.END or Gravity.CENTER_VERTICAL) }
    private val result = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val message = TextView(ctx).apply {
        setTextColor(Ui.TEXT2); textSize = 12f; gravity = Gravity.CENTER
        setLineSpacing(0f, 1.1f)
    }
    private val art = ArtView(ctx)
    /** A message card: an optional drawing above the text, centred in the card's body. */
    private val messageBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
    private val notIt = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val status = Ui.label(ctx, 10f, Ui.TEXT3)

    // parts the compact form resizes or hides
    private val topRow = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val barRows = List(3) { LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL } }
    private val chipRow = LinearLayout(ctx).apply { orientation = HORIZONTAL }
    private val leagueRows = List(2) { LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL } }
    private val pick = Ui.label(ctx, 13f, Ui.TEXT, bold = true, gravity = Gravity.CENTER)
    private val footer = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private var compact = false
    private var model: CardModel? = null

    init {
        orientation = LinearLayout.VERTICAL

        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(title, lp(0, match, 1f))
        header.addView(badge, lp(wrap, wrap).apply { marginStart = dp(6) })
        addView(header, lp(match, dp(Metrics.IV_HEADER)))

        // ring + the three stat bars
        val top = topRow
        top.addView(ring, lp(dp(Metrics.RING), dp(Metrics.RING)))
        val bars = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        for (i in 0 until 3) {
            val row = barRows[i]
            row.addView(statLabels[i], lp(dp(28), match))
            row.addView(statBars[i], lp(0, dp(5), 1f))
            row.addView(statValues[i], lp(dp(36), match))
            bars.addView(row, lp(match, dp(Metrics.BAR_ROW)))
        }
        top.addView(bars, lp(0, match, 1f).apply { marginStart = dp(10) })
        result.addView(top, lp(match, dp(Metrics.RING)))

        // Lv / CP / HP
        chips.forEachIndexed { i, c ->
            c.background = Ui.rounded(ctx, Ui.CHIP, 9f)
            chipRow.addView(c, lp(0, match, 1f).apply { if (i > 0) marginStart = dp(5) })
        }
        result.addView(chipRow, lp(match, dp(Metrics.CHIP_ROW)).apply { topMargin = dp(Metrics.IV_GAP) })

        // 2 x 2 league grid
        for (r in 0 until 2) {
            val row = leagueRows[r]
            for (c in 0 until 2) {
                val i = r * 2 + c
                val cell = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                cell.addView(leagueNames[i], lp(dp(38), match))
                cell.addView(leagueValues[i], lp(0, match, 1f))
                row.addView(cell, lp(0, match, 1f).apply { if (c > 0) marginStart = dp(12) })
            }
            result.addView(row, lp(match, dp(Metrics.LEAGUE_ROW)).apply { if (r == 0) topMargin = dp(Metrics.IV_GAP) })
        }
        // compact form only: which league it is good for
        pick.visibility = View.GONE
        result.addView(pick, lp(match, dp(Metrics.COMPACT_PICK)).apply { topMargin = dp(Metrics.COMPACT_GAP) })

        // the body is a fixed-size frame holding either the result or a message
        val body = FrameLayout(ctx)
        body.addView(result, FrameLayout.LayoutParams(match, match))
        messageBox.addView(art, lp(dp(Metrics.ART), dp(Metrics.ART)).apply { bottomMargin = dp(Metrics.ART_GAP) })
        messageBox.addView(message, lp(match, wrap))
        body.addView(messageBox, FrameLayout.LayoutParams(match, match))
        addView(body, lp(match, 0, 1f).apply { topMargin = dp(Metrics.IV_GAP) })

        // footer: the status line, or the "Not it?" buttons when the species is uncertain; "Copy log" on the right
        val left = FrameLayout(ctx)
        left.addView(status, FrameLayout.LayoutParams(match, match))
        left.addView(notIt, FrameLayout.LayoutParams(match, match))
        footer.addView(left, lp(0, match, 1f))
        footer.addView(Ui.label(ctx, 10f, Ui.TEXT2).apply {
            text = "Copy log"; isClickable = true
            setPadding(dp(8), 0, 0, 0)
            setOnClickListener { onCopy?.invoke() }
        }, lp(wrap, match))
        addView(footer, lp(match, dp(Metrics.IV_FOOTER)).apply { topMargin = dp(Metrics.IV_GAP) })
    }

    private fun toneColor(t: Tone) = when (t) {
        Tone.GREAT -> Ui.GREEN
        Tone.DECENT -> Ui.AMBER
        Tone.WEAK -> Ui.RED
        Tone.NEUTRAL -> Ui.GREY
    }

    private fun markColor(m: Mark) = when (m) {
        Mark.GOOD -> Ui.GREEN
        Mark.MAYBE -> Ui.AMBER
        Mark.BAD -> Ui.RED
        Mark.NONE -> Ui.TEXT3
    }

    /** The window got small (true) or normal / big (false). Message cards look the same in both forms. */
    fun setCompact(on: Boolean) {
        if (compact == on) return
        compact = on
        applyMode()
    }

    private fun setSp(v: TextView, sp: Float) = v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    private fun resize(v: View, hDp: Int) { (v.layoutParams as ViewGroup.LayoutParams).height = dp(hDp) }

    /** Shows the compact or the full form of a result card by hiding / resizing parts; the card's own size never changes. */
    private fun applyMode() {
        val c = compact && model != null && model?.message == null
        resize(topRow, if (c) Metrics.COMPACT_RING else Metrics.RING)
        resize(ring, if (c) Metrics.COMPACT_RING else Metrics.RING)
        (ring.layoutParams as ViewGroup.LayoutParams).width = dp(if (c) Metrics.COMPACT_RING else Metrics.RING)
        ring.bigScale = if (c) 1.45f else 1f
        ring.smallScale = if (c) 1.1f else 1f
        for (i in 0 until 3) {
            resize(barRows[i], if (c) Metrics.COMPACT_BAR_ROW else Metrics.BAR_ROW)
            resize(statBars[i], if (c) 8 else 5)
            (statLabels[i].layoutParams as ViewGroup.LayoutParams).width = dp(if (c) 34 else 28)
            (statValues[i].layoutParams as ViewGroup.LayoutParams).width = dp(if (c) 48 else 36)
            setSp(statLabels[i], if (c) 12f else 10f)
            setSp(statValues[i], if (c) 15f else 11f)
        }
        val detail = if (c) View.GONE else View.VISIBLE
        chipRow.visibility = detail
        leagueRows.forEach { it.visibility = detail }
        footer.visibility = detail
        pick.visibility = if (c) View.VISIBLE else View.GONE
        requestLayout()
    }

    private fun pickFill(m: Mark) = when (m) {
        Mark.GOOD -> Ui.GREEN_FILL
        Mark.MAYBE -> Ui.AMBER_FILL
        Mark.BAD -> Ui.RED_FILL
        Mark.NONE -> Ui.CHIP
    }

    fun bind(m: CardModel) {
        model = m
        title.text = m.title
        badge.text = m.badge ?: ""
        badge.visibility = if (m.badge == null) View.GONE else View.VISIBLE
        status.text = m.status

        val msg = m.message
        if (msg != null) {
            result.visibility = View.GONE
            messageBox.visibility = View.VISIBLE
            art.visibility = if (m.art == Art.NONE) View.GONE else View.VISIBLE
            art.set(m.art)
            message.text = msg
        } else {
            messageBox.visibility = View.GONE
            result.visibility = View.VISIBLE
            val color = toneColor(m.tone)
            ring.set(m.ring, color, m.pct, m.pctSub)
            for (i in 0 until 3) {
                val s = m.stats.getOrNull(i)
                statBars[i].set((s?.min ?: 0) / 15f, (s?.max ?: 0) / 15f, color)
                statValues[i].text = s?.text ?: "–"
                chips[i].text = m.chips.getOrNull(i) ?: ""
            }
            for (i in 0 until 4) {
                val l = m.leagues.getOrNull(i)
                leagueNames[i].text = l?.name ?: ""
                leagueValues[i].text = l?.value ?: ""
                leagueValues[i].setTextColor(markColor(l?.mark ?: Mark.NONE))
            }
            val p = m.leaguePick
            pick.text = p.text
            pick.setTextColor(if (p.mark == Mark.NONE) Ui.TEXT2 else markColor(p.mark))
            pick.background = Ui.rounded(context, pickFill(p.mark), 15f)
        }
        applyMode()

        // The "Not it?" buttons take the status line's place, so the footer never changes height.
        notIt.removeAllViews()
        if (m.others.isEmpty()) {
            notIt.visibility = View.GONE
            status.visibility = View.VISIBLE
        } else {
            status.visibility = View.INVISIBLE
            notIt.visibility = View.VISIBLE
            notIt.addView(Ui.label(context, 10f, Ui.TEXT3).apply { text = "Not it?" }, lp(wrap, match).apply { marginEnd = dp(5) })
            m.others.take(2).forEach { sp ->
                val b = Ui.label(context, 10f, Ui.TEXT, gravity = Gravity.CENTER).apply {
                    text = sp.label
                    background = Ui.rounded(context, Ui.CHIP, 9f)
                    setPadding(dp(5), 0, dp(5), 0)
                    isClickable = true
                    setOnClickListener { onPick?.invoke(sp) }
                }
                notIt.addView(b, lp(0, match, 1f).apply { marginEnd = dp(4) })
            }
        }
    }
}
