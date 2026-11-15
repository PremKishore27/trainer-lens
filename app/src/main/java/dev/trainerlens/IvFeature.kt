package dev.trainerlens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import java.util.Locale
import java.util.concurrent.Executor

/**
 * Feature 2: IV checker (the overlay's "IV" tab).
 *
 * All heavy work (pixel scans, the IV solver, rank tables) runs on [worker]; the UI is only touched through [main].
 *  1. Pokémon page: CP + HP + stardust + the clues that identify the species → every IV spread / level that fits.
 *  2. In-game appraisal: the three bars are measured from the screenshot → exact IVs.
 * A page is shown from its first frame (marked "reading…") and confirmed on the second, so swiping between Pokémon
 * is fast. What is shown is a [CardModel] (plain data) drawn by [IvCardView], which has a fixed size.
 */
class IvFeature(private val ctx: Context, private val worker: Executor) {
    private val main = Handler(Looper.getMainLooper())
    private val view = IvCardView(ctx).apply {
        bind(CardModels.idle())
        onPick = { sp -> worker.execute { if (tracker.choose(sp)) render() } }
        onCopy = { copyDebug() }
    }
    val panel: View = view

    // worker-thread state
    private val tracker = MonTracker { m -> SpeciesDb.get()?.let { Identify.analyze(m, it) } }
    private var arc: ArcReader? = null
    @Volatile private var lastSeen = 0L
    private var shown: CardModel? = null
    private var lastBars: Iv? = null
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
            show(CardModels.message("Loading data…",
                "Pokémon data comes from the internet.\nCheck your connection." + (SpeciesDb.lastError?.let { "\n\nLast error: $it" } ?: "")))
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
        // The CP can be unreadable for a while (appraisal dialog, animation). If the HP that *is* visible belongs to a
        // different Pokémon than the one we're showing, the card is stale: drop it instead of showing the old Pokémon
        // (and never attach this Pokémon's appraisal to the previous one).
        val hpNow = if (read == null) SummaryParser.hp(tokens) else read.hp
        if (read == null && hpNow != null && tracker.hpOnly(hpNow)) changed = true
        val bars = AppraisalReader.read(tokens, bmp)
        if (bars != null && tracker.current != null && (read != null || hpNow == tracker.current?.hp)) {
            if (tracker.observeBars(bars)) { lastSeen = now; changed = true }
        }
        if (tracker.current == null && hpNow != null) {
            lastSeen = now
            // appraisal visible but no CP yet: the IV % alone is still useful
            val stableBars = bars != null && bars == lastBars
            lastBars = bars
            if (stableBars) {
                show(CardModels.message("From appraisal",
                    "${CardModels.pct0(bars!!.pct)}%  ${bars.a}/${bars.d}/${bars.s}\n${CardModels.rating(bars.total)}\n\n" +
                        "CP not readable yet – close the appraisal for level and PvP."))
            } else show(CardModels.message("Reading…", "HP $hpNow · CP not readable yet"))
            analysisMs = (System.nanoTime() - t0) / 1_000_000
            return
        }

        if (now - lastSeen > 5_000) { // no Pokémon page for a while
            tracker.reset(); settled = false
            show(CardModels.idle())
            return
        }
        val m = tracker.current
        settled = m != null && m.frames >= 3 && !changed
        if (changed || shown == null) render()
        analysisMs = (System.nanoTime() - t0) / 1_000_000
    }

    private fun render() {
        val m = tracker.current ?: return
        val r = tracker.result
        if (r == null) {
            val c = m.clues()
            val why = if (SpeciesDb.candidates(c) == null && SpeciesDb.widened(c) == null)
                "Can't tell which Pokémon this is.\nIt's renamed and no candy or type line was read.\nTap Copy log and send me the text."
            else "No IV spread fits these numbers.\nMega or buddy-boosted, or a misread – hold still for a second."
            show(CardModels.message("CP ${m.cp} · HP ${m.hp}", why))
            return
        }
        // An appraisal fixed the exact level: use it to calibrate the level arc's end point
        val d = m.dot
        if (m.appraised != null && r.exact != null && r.levelMin == r.levelMax && d != null) arc?.calibrateMax(d, r.levelMin)
        show(CardModels.from(r, m))
    }

    /** Updates the card on the main thread; skips the work if nothing changed. */
    private fun show(model: CardModel) {
        if (model == shown) return
        shown = model
        main.post { view.bind(model) }
    }

    /** Copies what the OCR read on the last frame (text + position) and what the app made of it. */
    private fun copyDebug() {
        worker.execute {
            val d = SpeciesDb.get()
            val clues = d?.let { SpeciesDb.readClues(lastTokens, lastScreenH, it) }
            val txt = buildString {
                append("Trainer Lens ${BuildConfig.VERSION_NAME} · IV · screen height $lastScreenH · OCR ${ocrMs} ms · analysis ${analysisMs} ms\n")
                append("Clues: names=${clues?.names?.map { it.label }} family=${clues?.family?.map { it.name }?.distinct()} types=${clues?.types} " +
                    "size=${clues?.heightM}m/${clues?.weightKg}kg evolve=${clues?.evolveCost} caught=${clues?.caught?.map { it.name }?.distinct()}\n")
                tracker.current?.let { append("Pokémon: CP ${it.cp} HP ${it.hp} dust ${it.dust} frames ${it.frames} appraised ${it.appraised}\n") }
                tracker.result?.let { append("Result: ${it.species.label} others=${it.others.map { o -> o.label }} guessed=${it.guessed} ${it.pctMin}-${it.pctMax}%\n") }
                append("---\n")
                lastTokens.sortedBy { it.box.top }.forEach { append("${it.text}  @${it.box.left},${it.box.top},${it.box.right},${it.box.bottom}\n") }
            }
            val n = lastTokens.size
            main.post {
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Trainer Lens IV", txt))
                Toast.makeText(ctx, String.format(Locale.ROOT, "Copied %d text lines", n), Toast.LENGTH_SHORT).show()
            }
        }
    }
}
