package dev.trainerlens

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.TextView

/** Feature 2: IV checker. Its card lives in the overlay's "IV" tab and updates while a Pokémon page is open. */
class IvFeature(ctx: Context) {
    private val idleText = "Open a Pokémon's page in the game.\nThe IV card appears here."
    private val card = TextView(ctx).apply {
        setTextColor(Color.WHITE); textSize = 14f; setPadding(24, 16, 24, 16)
        text = idleText; setBackgroundColor(0xCC555555.toInt())
    }
    val panel: View get() = card
    private var lastKey = ""
    private var lastSeen = 0L

    fun onTokens(tokens: List<Token>, screenH: Int, now: Long) {
        val read = SummaryParser.parse(tokens, screenH)
        if (read != null && !SpeciesDb.ready) {
            SpeciesDb.ensureLoaded(now)
            lastSeen = now; lastKey = ""
            show("Loading Pokémon data… (internet needed)" + (SpeciesDb.lastError?.let { "\nLast error: $it" } ?: ""), 0xCC555555.toInt())
            return
        }
        val forms = read?.let { SpeciesDb.find(it.lines) }
        if (read == null || forms.isNullOrEmpty()) {
            if (now - lastSeen > 3000 && lastKey != "idle") { lastKey = "idle"; show(idleText, 0xCC555555.toInt()) }
            return
        }
        lastSeen = now
        val key = "${forms[0].id}-${read.cp}-${read.hp}-${read.dust}"
        if (key == lastKey) return
        lastKey = key
        val r = IvAnalyzer.analyze(forms, read.cp, read.hp, read.dust)
        if (r == null) {
            show("${forms[0].name} · CP ${read.cp} · HP ${read.hp}" + (read.dust?.let { " · ✨$it" } ?: "") +
                "\nNo IV spread fits these numbers.\nMega-evolved, buddy-boosted or misread? Wait a second and re-open.", 0xCC555555.toInt())
            return
        }
        show(format(r, read), color(r))
    }

    private fun stars(n: Int) = if (n == 4) "★★★ 100%" else "★".repeat(n) + "☆".repeat(3 - n)
    private fun lv(x: Double) = if (x % 1.0 == 0.0) "%.0f".format(x) else "%.1f".format(x)
    private fun spread(iv: Iv) = "${iv.a}/${iv.d}/${iv.s}"

    private fun format(r: IvResult, read: SummaryRead): String {
        val sb = StringBuilder()
        val e = r.exact
        val level = if (r.levelMin == r.levelMax) "Lv ${lv(r.levelMin)}" else "Lv ${lv(r.levelMin)}–${lv(r.levelMax)}"
        if (e != null) {
            sb.append("${r.species.label}  ${"%.1f".format(e.pct)}%  ${stars(e.stars)}\n")
            sb.append("Atk ${e.a} · Def ${e.d} · HP ${e.s}   $level\n")
        } else {
            val lo = r.worst; val hi = r.best
            sb.append("${r.species.label}  ${"%.0f".format(r.pctMin)}–${"%.0f".format(r.pctMax)}%  ${stars(lo.stars)}–${stars(hi.stars)}\n")
            sb.append("${r.candidates.size} possible spreads · $level\n")
            sb.append("Best ${spread(hi)} · Worst ${spread(lo)}\n")
        }
        sb.append("CP ${read.cp} · HP ${read.hp}" + (read.dust?.let { " · ✨$it" } ?: " · dust not read") + "\n")
        sb.append("Little ${fmt(r.little)} · Great ${fmt(r.great)} · Ultra ${fmt(r.ultra)}")
        if (r.otherForms.isNotEmpty()) sb.append("\nAlso fits: ${r.otherForms.joinToString { it.label }}")
        if (e == null) sb.append("\nAppraise it in-game to narrow this down")
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

    private fun show(text: String, bg: Int) { card.text = text; card.setBackgroundColor(bg) }
}
