package dev.trainerlens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executor

/**
 * Feature 2: IV checker (the overlay's "IV" tab).
 *
 * All heavy work (pixel scans, the IV solver, rank tables) runs on [worker]; the UI is only touched through [main].
 *  1. Pokémon page: CP + HP + stardust + the clues that identify the species → every IV spread / level that fits.
 *  2. In-game appraisal: the three bars are measured from the screenshot → exact IVs.
 * A page is shown from its first frame (marked ⏳) and confirmed on the second, so swiping between Pokémon is fast.
 */
class IvFeature(private val ctx: Context, private val worker: Executor) {
    private val main = Handler(Looper.getMainLooper())
    private val idleText = "Open a Pokémon's page.\nFor exact IVs, tap ☰ → Appraise in the game."
    private val card = TextView(ctx).apply {
        setTextColor(Color.WHITE); textSize = 15f; setPadding(24, 16, 24, 16)
        text = idleText; setBackgroundColor(GREY)
    }
    private val chips = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE }
    val panel: View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        addView(card)
        addView(chips)
        addView(Button(ctx).apply { text = "📋 Copy screen text (for bug reports)"; textSize = 11f; setOnClickListener { copyDebug() } })
    }

    // worker-thread state
    private val tracker = MonTracker { m -> SpeciesDb.get()?.let { Identify.analyze(m, it) } }
    private var arc: ArcReader? = null
    @Volatile private var lastSeen = 0L
    private var shown = ""
    private var lastTokens: List<Token> = emptyList()
    private var lastScreenH = 0
    private var ocrMs = 0L
    private var analysisMs = 0L

    /** A Pokémon page was read within the last 3 s – worth scanning fast. Otherwise (map, other screens) scan slowly. */
    fun pageRecentlySeen(now: Long) = now - lastSeen < 3_000

    /** True while a Pokémon is shown and nothing is changing – the scan loop can then slow down to save battery. */
    @Volatile var settled = false; private set

    fun onFrame(tokens: List<Token>, bmp: Bitmap, screenH: Int, now: Long, ocrTimeMs: Long) {
        val t0 = System.nanoTime()
        lastTokens = tokens; lastScreenH = screenH; ocrMs = ocrTimeMs
        val read = SummaryParser.parse(tokens, screenH)
        if (read != null && !SpeciesDb.ready) {
            SpeciesDb.ensureLoaded(now)
            show("Loading Pokémon data… (internet needed)" + (SpeciesDb.lastError?.let { "\nLast error: $it" } ?: ""), GREY, "loading", emptyList())
            return
        }
        var changed = false
        if (read != null) {
            lastSeen = now
            val clues = SpeciesDb.readClues(tokens, screenH)
            changed = tracker.observe(read.cp, read.hp, read.dust, clues)
            val m = tracker.current
            // level arc: look for the white dot once per Pokémon, on its second frame
            if (m != null && m.frames >= 2 && !m.dotTried) {
                m.dotTried = true
                val a = arc ?: ArcReader(ctx, bmp.width, bmp.height).also { arc = it }
                a.pick(a.findDots(bmp))?.let { d ->
                    m.dot = d; a.learn(d); m.arcLevel = a.level(d)
                    changed = tracker.refresh(m) || changed
                }
            }
        }
        AppraisalReader.read(tokens, bmp)?.let { if (tracker.observeBars(it)) { lastSeen = now; changed = true } }

        if (now - lastSeen > 5_000) { // no Pokémon page for a while
            tracker.reset(); settled = false
            show(idleText, GREY, "idle", emptyList())
            return
        }
        val m = tracker.current
        settled = m != null && m.frames >= 3 && !changed
        if (changed || shown.isEmpty()) render()
        analysisMs = (System.nanoTime() - t0) / 1_000_000
    }

    private fun render() {
        val m = tracker.current ?: return
        val r = tracker.result
        if (r == null) {
            val c = m.clues()
            val why = if (SpeciesDb.candidates(c) == null && SpeciesDb.widened(c) == null)
                "Can't tell which Pokémon this is (renamed, and no candy / type line read).\nTap 📋 below and send me the text."
            else "No IV spread fits these numbers.\nMega / buddy-boosted, or a misread – hold still for a second."
            show("CP ${m.cp} · HP ${m.hp}\n$why", GREY, m.signature(), emptyList())
            return
        }
        // An appraisal fixed the exact level: use it to calibrate the level arc's end point
        val d = m.dot
        if (m.appraised != null && r.exact != null && r.levelMin == r.levelMax && d != null) arc?.calibrateMax(d, r.levelMin)
        show(format(r, m), color(r), m.signature(), r.others.distinctBy { it.label }.take(3))
    }

    private fun rating(total: Int) = when {
        total == 45 -> "Perfect ★★★"
        total >= 37 -> "Excellent ★★★"
        total >= 30 -> "Good ★★"
        total >= 23 -> "Decent ★"
        else -> "Weak"
    }

    private fun lv(x: Double) = if (x % 1.0 == 0.0) "%.0f".format(x) else "%.1f".format(x)

    private fun format(r: IvResult, m: Mon): String {
        val ap = m.appraised
        val sb = StringBuilder(r.species.label)
        if (r.guessed) sb.append("  (best guess)") else if (r.nickname) sb.append("  (nickname)")
        if (m.provisional) sb.append("  ⏳")
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

    /** Updates the card (and the "not it?" buttons) on the main thread; skips work if nothing changed. */
    private fun show(text: String, bg: Int, key: String, others: List<Species>) {
        val k = "$key|$text"
        if (k == shown) return
        shown = k
        main.post {
            card.text = text; card.setBackgroundColor(bg)
            chips.removeAllViews()
            if (others.isEmpty()) { chips.visibility = View.GONE; return@post }
            chips.addView(TextView(ctx).apply { this.text = "Not it? "; setTextColor(Color.LTGRAY); textSize = 12f; setPadding(8, 16, 8, 0) })
            others.forEach { sp ->
                chips.addView(Button(ctx).apply {
                    this.text = sp.label; textSize = 11f; isAllCaps = false
                    setOnClickListener { worker.execute { if (tracker.choose(sp)) render() } }
                })
            }
            chips.visibility = View.VISIBLE
        }
    }

    /** Copies what the OCR read on the last frame (text + position) and what the app made of it. */
    private fun copyDebug() {
        worker.execute {
            val d = SpeciesDb.get()
            val clues = d?.let { SpeciesDb.readClues(lastTokens, lastScreenH, it) }
            val txt = buildString {
                append("Trainer Lens ${BuildConfig.VERSION_NAME} · IV · screen height $lastScreenH · OCR ${ocrMs} ms · analysis ${analysisMs} ms\n")
                append("Clues: names=${clues?.names?.map { it.label }} family=${clues?.family?.map { it.name }?.distinct()} types=${clues?.types} " +
                    "size=${clues?.heightM}m/${clues?.weightKg}kg evolve=${clues?.evolveCost}\n")
                tracker.current?.let { append("Pokémon: CP ${it.cp} HP ${it.hp} dust ${it.dust} frames ${it.frames} appraised ${it.appraised}\n") }
                tracker.result?.let { append("Result: ${it.species.label} others=${it.others.map { o -> o.label }} guessed=${it.guessed} ${it.pctMin}-${it.pctMax}%\n") }
                append("---\n")
                lastTokens.sortedBy { it.box.top }.forEach { append("${it.text}  @${it.box.left},${it.box.top},${it.box.right},${it.box.bottom}\n") }
            }
            main.post {
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Trainer Lens IV", txt))
                card.text = "Copied ${lastTokens.size} text lines – paste them in your message.\n\n" + card.text
            }
        }
    }

    private companion object { val GREY = 0xCC555555.toInt() }
}
