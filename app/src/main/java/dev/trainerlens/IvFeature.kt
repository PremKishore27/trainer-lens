package dev.trainerlens

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/** Feature 2: IV checker. Shows a small, non-touchable card whenever a Pokémon summary screen is visible. */
class IvFeature(private val ctx: Context, private val wm: WindowManager) {
    private val card = TextView(ctx).apply {
        setTextColor(Color.WHITE); textSize = 14f; setPadding(24, 16, 24, 16); visibility = View.GONE
    }
    private var lastKey = ""
    private var lastSeen = 0L

    init {
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 16; y = 180 }
        wm.addView(card, lp)
    }

    fun onTokens(tokens: List<Token>, screenH: Int, now: Long) {
        val read = SummaryParser.parse(tokens, screenH)
        if (read != null && !SpeciesDb.ready) {
            SpeciesDb.ensureLoaded(now)
            lastSeen = now; lastKey = ""
            show("Loading Pokémon data… (internet needed)" + (SpeciesDb.lastError?.let { "\nLast error: $it" } ?: ""), 0xCC555555.toInt())
            return
        }
        val sp = read?.let { SpeciesDb.find(it.lines) }
        if (read == null || sp == null) {
            if (now - lastSeen > 3000) card.visibility = View.GONE
            return
        }
        lastSeen = now
        val key = "${sp.id}-${read.cp}-${read.hp}-${read.dust}"
        if (key == lastKey) return
        lastKey = key
        val r = IvAnalyzer.analyze(sp, read.cp, read.hp, read.dust)
        if (r == null) { show("${sp.name}: no IV match (shadow/purified/mega or misread?)", 0xCC555555.toInt()); return }
        show(format(r), color(r))
    }

    private fun format(r: IvResult): String {
        val sb = StringBuilder()
        val e = r.exact
        if (e != null) sb.append("${r.species.name}  ${"%.1f".format(e.pct)}%  (${e.a}/${e.d}/${e.s})\n")
        else sb.append("${r.species.name}  ${"%.0f".format(r.pctMin)}–${"%.0f".format(r.pctMax)}%  (${r.candidates.size} spreads)\n")
        sb.append("Great: ${fmt(r.great)}   Ultra: ${fmt(r.ultra)}\n")
        sb.append("Estimate – shadow/purified/mega/L40+ may be off")
        return sb.toString()
    }

    private fun fmt(x: RankRange?): String = when {
        x == null -> "n/a"
        x.best == x.worst -> "#${x.best} (${"%.1f".format(x.pctOfBest)}%)"
        else -> "#${x.best}–#${x.worst}"
    }

    private fun color(r: IvResult): Int {
        val bestRank = listOfNotNull(r.great?.best, r.ultra?.best).minOrNull() ?: Int.MAX_VALUE
        return when {
            bestRank <= 25 || r.pctMax >= 98 -> 0xCC1B7F3B.toInt()  // great
            bestRank <= 150 || r.pctMax >= 90 -> 0xCC8A6D00.toInt() // decent
            else -> 0xCC8B1E1E.toInt()                               // weak
        }
    }

    private fun show(text: String, bg: Int) { card.text = text; card.setBackgroundColor(bg); card.visibility = View.VISIBLE }

    fun detach() { runCatching { wm.removeView(card) } }
}
