package dev.trainerlens

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The IV card. Every part has a fixed size, so the card (and the window around it) never changes size whatever the
 * Pokémon: long names are cut with "…", empty parts keep their space.
 *
 *   name ............................ badge
 *   ( ring )   ATK ▬▬▬▬▬▬  13
 *   ( 89 % )   DEF ▬▬▬▬▬▬  14
 *              HP  ▬▬▬▬▬▬  13
 *   [ Lv 31 ] [ CP 3259 ] [ HP 163 ]
 *   Little —        Great #12 ✓
 *   Ultra  —        Master Good IVs
 *   Not it? [ A ] [ B ] [ C ]
 *   status ....................... Copy log
 */
class IvCardView(ctx: Context) : LinearLayout(ctx) {
    var onPick: ((Species) -> Unit)? = null
    var onCopy: (() -> Unit)? = null

    private fun dp(v: Int) = Ui.dp(context, v)
    private fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    private val title = Ui.label(ctx, 17f, Ui.TEXT, bold = true)
    private val badge = Ui.label(ctx, 11f, Ui.TEXT, gravity = Gravity.CENTER).apply {
        background = Ui.rounded(ctx, Ui.CHIP, 10f)
        setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 3), Ui.dp(ctx, 8), Ui.dp(ctx, 3))
    }
    private val ring = RingView(ctx)
    private val statNames = listOf("ATK", "DEF", "HP")
    private val statBars = List(3) { BarView(ctx) }
    private val statValues = List(3) { Ui.label(ctx, 12f, Ui.TEXT, gravity = Gravity.END or Gravity.CENTER_VERTICAL) }
    private val chips = List(3) { Ui.label(ctx, 12f, Ui.TEXT, gravity = Gravity.CENTER) }
    private val leagueNames = List(4) { Ui.label(ctx, 11f, Ui.TEXT2) }
    private val leagueValues = List(4) { Ui.label(ctx, 12f, Ui.TEXT, gravity = Gravity.END or Gravity.CENTER_VERTICAL) }
    private val result = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val message = TextView(ctx).apply {
        setTextColor(Ui.TEXT2); textSize = 13f; gravity = Gravity.CENTER
        setLineSpacing(0f, 1.15f)
    }
    private val notIt = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val status = Ui.label(ctx, 11f, Ui.TEXT3)

    init {
        orientation = LinearLayout.VERTICAL

        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(title, lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        header.addView(badge, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        addView(header, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(26)))

        // ring + the three stat bars
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(ring, lp(dp(76), dp(76)))
        val bars = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        for (i in 0 until 3) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(Ui.label(ctx, 11f, Ui.TEXT2).apply { text = statNames[i] }, lp(dp(30), ViewGroup.LayoutParams.MATCH_PARENT))
            row.addView(statBars[i], lp(0, dp(6), 1f))
            row.addView(statValues[i], lp(dp(38), ViewGroup.LayoutParams.MATCH_PARENT))
            bars.addView(row, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(22)))
        }
        top.addView(bars, lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = dp(10) })
        result.addView(top, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(76)))

        // Lv / CP / HP
        val chipRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        chips.forEachIndexed { i, c ->
            c.background = Ui.rounded(ctx, Ui.CHIP, 10f)
            chipRow.addView(c, lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (i > 0) marginStart = dp(6) })
        }
        result.addView(chipRow, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(28)).apply { topMargin = dp(8) })

        // 2 x 2 league grid
        for (r in 0 until 2) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            for (c in 0 until 2) {
                val i = r * 2 + c
                val cell = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                cell.addView(leagueNames[i], lp(dp(42), ViewGroup.LayoutParams.MATCH_PARENT))
                cell.addView(leagueValues[i], lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
                row.addView(cell, lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (c > 0) marginStart = dp(14) })
            }
            result.addView(row, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(22)).apply { topMargin = if (r == 0) dp(8) else 0 })
        }

        // the body is a fixed-size frame holding either the result or a message
        val body = FrameLayout(ctx)
        body.addView(result, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        body.addView(message, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(body, lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(6) })

        addView(notIt, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(28)).apply { topMargin = dp(6) })

        val footer = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        footer.addView(status, lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        footer.addView(Ui.label(ctx, 11f, Ui.TEXT2).apply {
            text = "Copy log"; isClickable = true
            setPadding(dp(8), 0, 0, 0)
            setOnClickListener { onCopy?.invoke() }
        }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(footer, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(18)).apply { topMargin = dp(4) })
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

    fun bind(m: CardModel) {
        title.text = m.title
        badge.text = m.badge ?: ""
        badge.visibility = if (m.badge == null) View.GONE else View.VISIBLE
        status.text = m.status

        val msg = m.message
        if (msg != null) {
            result.visibility = View.GONE
            message.visibility = View.VISIBLE
            message.text = msg
        } else {
            message.visibility = View.GONE
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
        }

        // "Not it?" keeps its height even when empty, so the card never jumps
        notIt.removeAllViews()
        if (m.others.isEmpty()) {
            notIt.visibility = View.INVISIBLE
        } else {
            notIt.visibility = View.VISIBLE
            notIt.addView(Ui.label(ctx(), 11f, Ui.TEXT3).apply { text = "Not it?" }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { marginEnd = dp(6) })
            m.others.forEach { sp ->
                val b = Ui.label(ctx(), 11f, Ui.TEXT, gravity = Gravity.CENTER).apply {
                    text = sp.label
                    background = Ui.rounded(ctx(), Ui.CHIP, 10f)
                    setPadding(dp(6), 0, dp(6), 0)
                    isClickable = true
                    setOnClickListener { onPick?.invoke(sp) }
                }
                notIt.addView(b, lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginEnd = dp(4) })
            }
        }
    }

    private fun ctx(): Context = context
}
