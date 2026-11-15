package dev.trainerlens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.widget.TextView

/**
 * Feature 2: IV checker (the overlay's "IV" tab).
 *
 * Like Poké Genie it combines two sources:
 *  1. Pokémon page: species + CP + HP + power-up stardust → every IV spread / level that fits (a range).
 *  2. In-game appraisal: the three bars are measured from the screenshot → exact IVs, then cross-checked with CP/HP.
 * Readings only count when they are the same on two frames in a row, so animations overlapping the CP don't cause errors.
 */
class IvFeature(private val ctx: Context) {
    private val idleText = "Open a Pokémon's page.\nFor exact IVs, tap ☰ → Appraise in the game."
    private val card = TextView(ctx).apply {
        setTextColor(Color.WHITE); textSize = 15f; setPadding(24, 16, 24, 16)
        text = idleText; setBackgroundColor(GREY)
    }
    val panel: View get() = card

    /** The Pokémon currently on screen (kept while the appraisal covers part of the page). */
    private data class Mon(val forms: List<Species>, val cp: Int, val hp: Int, val dust: Int?, val nickname: Boolean)
    private var mon: Mon? = null
    private var monSeen = 0L
    private var pendingMon: Mon? = null
    private var pendingIv: Iv? = null
    private var appraised: Iv? = null
    private var shownKey = ""
    private var arc: ArcReader? = null
    /** Level-arc dot of the current Pokémon (screen position), if found. */
    private var dot: Pair<Float, Float>? = null

    fun onFrame(tokens: List<Token>, bmp: Bitmap, screenH: Int, now: Long) {
        val read = SummaryParser.parse(tokens, screenH)
        if (read != null && !SpeciesDb.ready) {
            SpeciesDb.ensureLoaded(now)
            show("Loading Pokémon data… (internet needed)" + (SpeciesDb.lastError?.let { "\nLast error: $it" } ?: ""), GREY, "loading")
            return
        }
        val guess = read?.let { SpeciesDb.guess(tokens, screenH) }
        if (read != null && guess != null) {
            val m = Mon(guess.candidates, read.cp, read.hp, read.dust, guess.nickname)
            if (m == pendingMon) { // stable on two frames
                if (mon?.let { it.forms[0].dex != m.forms[0].dex || it.cp != m.cp } != false) { appraised = null; dot = null } // new Pokémon
                mon = m; monSeen = now
                val a = arc ?: ArcReader(ctx, bmp.width, bmp.height).also { arc = it }
                a.pick(a.findDots(bmp))?.let { d -> dot = d; a.learn(d) }
            }
            pendingMon = m
        }

        val bars = AppraisalReader.read(tokens, bmp)
        if (bars != null) {
            if (bars == pendingIv) { appraised = bars; monSeen = now }
            pendingIv = bars
        }

        val m = mon
        val ap = appraised
        if (m == null && ap != null) { // appraisal seen but the Pokémon page wasn't read: IVs only
            show("${"%.0f".format(ap.pct)}%  ${ap.a}/${ap.d}/${ap.s}  ${rating(ap.total)}\n✔ from appraisal (open the Pokémon page for level & PvP)", GREY, "ap-$ap")
            return
        }
        if (m == null || now - monSeen > 15_000) { // nothing (recent) on screen
            if (now - monSeen > 15_000) { mon = null; appraised = null }
            show(idleText, GREY, "idle")
            return
        }
        render(m)
    }

    private fun render(m: Mon) {
        val d = dot
        val arcLevel = d?.let { arc?.level(it) }
        val key = "${m.forms.map { it.id + it.form }}-${m.cp}-${m.hp}-${m.dust}-$appraised-$arcLevel-${arc?.status()}"
        if (key == shownKey) return
        val ap = appraised
        val r = IvAnalyzer.analyze(m.forms, m.cp, m.hp, m.dust, ap, arcLevel)
        if (r == null) {
            show("${m.forms[0].name} · CP ${m.cp} · HP ${m.hp}\nNo IV spread fits these numbers.\n" +
                "Mega / buddy-boosted, or a misread – hold still for a second.", GREY, key)
            return
        }
        // An appraisal fixed the exact level: use it to calibrate the level arc's end point
        if (ap != null && r.exact != null && r.levelMin == r.levelMax && d != null) arc?.calibrateMax(d, r.levelMin)
        show(format(r, m, ap), color(r), key)
    }

    private fun rating(total: Int) = when {
        total == 45 -> "Perfect ★★★"
        total >= 37 -> "Excellent ★★★"
        total >= 30 -> "Good ★★"
        total >= 23 -> "Decent ★"
        else -> "Weak"
    }

    private fun lv(x: Double) = if (x % 1.0 == 0.0) "%.0f".format(x) else "%.1f".format(x)

    private fun format(r: IvResult, m: Mon, ap: Iv?): String {
        val sb = StringBuilder(r.species.label)
        if (m.nickname) sb.append("  (nickname – found via candy)")
        sb.append('\n')
        val e = r.exact
        val level = if (r.levelMin == r.levelMax) "Lv ${lv(r.levelMin)}" else "Lv ${lv(r.levelMin)}–${lv(r.levelMax)}"
        if (e != null) {
            sb.append("${"%.0f".format(e.pct)}%  ${e.a}/${e.d}/${e.s}  ${rating(e.total)}\n")
        } else {
            sb.append("${"%.0f".format(r.pctMin)}–${"%.0f".format(r.pctMax)}%  (${r.candidates.size} possible)\n")
        }
        sb.append("$level · CP ${m.cp} · HP ${m.hp}\n")
        sb.append(pvpVerdict(r, m.cp))
        when {
            ap != null && e != null -> sb.append("\n✔ from appraisal")
            ap != null -> sb.append("\nAppraisal ${ap.a}/${ap.d}/${ap.s} didn't match CP – re-open the Pokémon")
            else -> sb.append("\nAppraise in-game for the exact value")
        }
        if (r.otherForms.isNotEmpty()) sb.append("\nAlso fits: ${r.otherForms.joinToString { it.label }}")
        arc?.let { sb.append("\n").append(if (r.arcUsed) "arc ✓ used" else it.status()) }
        return sb.toString()
    }

    /** One line: is it worth keeping for PvP? Good = top 100 of 4,096 spreads in a league it can still enter. */
    private fun pvpVerdict(r: IvResult, cp: Int): String {
        val leagues = listOf(Triple("Great", 1500, r.great), Triple("Ultra", 2500, r.ultra), Triple("Little", 500, r.little))
        val good = leagues.filter { (_, cap, rk) -> rk != null && cp <= cap && rk.best <= 100 }
        if (good.isNotEmpty()) {
            val (name, _, rk) = good.minBy { it.third!!.best }
            val sure = rk!!.worst <= 100
            return "PvP: ${if (sure) "✅ Good" else "🤔 Maybe good"} for $name League (#${rk.best}${if (rk.worst != rk.best) "–${rk.worst}" else ""})"
        }
        val master = r.candidates.all { it.total >= 42 } && cp > 2500
        if (master) return "PvP: ✅ Good IVs for Master League"
        // great spread, but it has already been powered past the league's CP cap (levels can't go down)
        val tooHigh = leagues.filter { (_, cap, rk) -> rk != null && cp > cap && rk.best <= 100 }.minByOrNull { it.third!!.best }
        if (tooHigh != null) return "PvP: ❌ CP too high for ${tooHigh.first} League (IVs would be #${tooHigh.third!!.best})"
        return "PvP: ❌ Not good for PvP"
    }

    private fun color(r: IvResult): Int {
        val bestRank = listOfNotNull(r.great?.best, r.ultra?.best).minOrNull() ?: Int.MAX_VALUE
        return when {
            bestRank <= 100 || r.pctMin >= 91 -> 0xCC1B7F3B.toInt()  // great
            bestRank <= 300 || r.pctMin >= 80 -> 0xCC8A6D00.toInt() // decent
            else -> 0xCC8B1E1E.toInt()                               // weak
        }
    }

    private fun show(text: String, bg: Int, key: String) {
        if (key == shownKey) return
        shownKey = key; card.text = text; card.setBackgroundColor(bg)
    }

    private companion object { val GREY = 0xCC555555.toInt() }
}
