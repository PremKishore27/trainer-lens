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

    /** The window is small (compact card: ring, IV values and best league) or normal / big (the full card). Main thread. */
    fun setCompact(on: Boolean) = view.setCompact(on)

    // worker-thread state
    private val tracker = MonTracker { m -> SpeciesDb.get()?.let { Identify.analyze(m, it) } }
    private var arc: ArcReader? = null
    @Volatile private var lastSeen = 0L
    private var shown: CardModel? = null
    private var lastBars: Iv? = null
    private val appraisal = AppraisalTracker()
    /** Called (from the scan thread) when the in-game appraisal dialog opens or closes. */
    var onAppraisal: ((Boolean) -> Unit)? = null
    private var lastTokens: List<Token> = emptyList()
    private var lastScreenH = 0
    private var ocrMs = 0L
    private var analysisMs = 0L
    private val cover = CoverTracker()
    private val noHpFrames get() = cover.count
    private var hintShown = false

    /** A Pokémon page was read within the last 3 s – worth scanning fast. Otherwise (map, other screens) scan slowly. */
    fun pageRecentlySeen(now: Long) = now - lastSeen < 3_000

    /** True while a Pokémon is shown and nothing is changing – the scan loop can then slow down to save battery. */
    @Volatile var settled = false; private set

    /** The Item bag is on screen (set by the scan loop): the card then says "wrong screen" instead of staying blank. */
    @Volatile var onItemsScreen = false

    fun onFrame(tokens: List<Token>, bmp: Bitmap, screenH: Int, now: Long, ocrTimeMs: Long) {
        val t0 = System.nanoTime()
        lastTokens = tokens; lastScreenH = screenH; ocrMs = ocrTimeMs
        val read = SummaryParser.parse(tokens, screenH)
        Diag.frame("t=${now % 100000}ms tokens=${tokens.size} cp=${SummaryParser.cp(tokens, screenH)} hp=${SummaryParser.hp(tokens)} " +
            "power=${SummaryParser.hasPowerUp(tokens)} read=${read != null} shown=${tracker.current?.let { "${it.cp}/${it.hp}" }} onItems=$onItemsScreen")
        // The Item bag is open and nothing of a Pokémon page (no CP, HP or POWER UP) is on screen: say so right away.
        // The checks keep this away from an appraisal, which has HP but no CP.
        if (read == null && onItemsScreen && SummaryParser.hp(tokens) == null && !SummaryParser.hasPowerUp(tokens) &&
            SummaryParser.cp(tokens, screenH) == null) {
            tracker.reset(); settled = false; cover.reset()
            if (appraisal.reset()) onAppraisal?.invoke(false)
            show(CardModels.idle(onItems = true))
            return
        }
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
        // The appraisal dialog hides the CP but not the "This X was caught on …" line, which names the Pokémon. Use it
        // for the Pokémon we couldn't identify (renamed, or a regional form), and keep that Pokémon alive meanwhile.
        if (read == null && hpNow != null && tracker.knowsHp(hpNow)) {
            lastSeen = now
            val caught = SpeciesDb.get()?.let { SpeciesDb.readCaught(tokens, it) }.orEmpty()
            if (caught.isNotEmpty() && tracker.observeCaught(hpNow, Clues(caught = caught))) changed = true
        }
        val dialogLike = read == null && hpNow != null && tracker.knowsHp(hpNow)
        if (read == null && hpNow != null && tracker.hpOnly(hpNow)) changed = true
        // A Pokémon page (also the appraisal screen, which has no POWER UP button) whose CP is readable but whose HP line
        // isn't: the window is probably covering it.
        val covered = if (SummaryParser.looksLikePokemonPage(tokens)) cover.update(SummaryParser.cp(tokens, screenH), hpNow)
        else { cover.reset(); false }
        val bars = AppraisalReader.read(tokens, bmp)
        if (appraisal.update(dialogLike || bars != null, read != null && bars == null)) onAppraisal?.invoke(appraisal.open)
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

        if (covered) {
            lastSeen = now; settled = true; hintShown = true // nothing will change until the window moves: scan calmly
            show(CardModels.message("Can't read the HP line",
                "This window covers the Pokémon's name,\nHP or type lines. Move it, drag its\nbottom edge to shrink it, or tap –."))
            analysisMs = (System.nanoTime() - t0) / 1_000_000
            return
        }
        if (hintShown) { hintShown = false; shown = null } // the page is readable again: show the Pokémon, not the hint

        if (now - lastSeen > 5_000) { // no Pokémon page for a while
            tracker.reset(); settled = false
            if (appraisal.reset()) onAppraisal?.invoke(false)
            show(CardModels.idle(onItemsScreen))
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
                "Can't tell which Pokémon this is.\nOpen ☰ → Appraise in the game:\nits \"caught on\" line names it."
            else "No IV spread fits these numbers.\nMega, buddy-boosted or a misread.\nAppraise it in the game to name it."
            show(CardModels.message("CP ${m.cp} · HP ${m.hp}", why))
            return
        }
        // An appraisal fixed the exact level: use it to calibrate the level arc's end point
        val d = m.dot
        if (m.appraised != null && r.exact != null && r.levelMin == r.levelMax && d != null) arc?.calibrateMax(d, r.levelMin)
        show(CardModels.from(r, m))
    }

    /** A frame threw an exception: say so on the card instead of silently showing nothing. Called on the worker thread. */
    fun reportError(e: Exception) {
        val msg = "${e.javaClass.simpleName}: ${(e.message ?: "").take(50)} @ ${e.stackTrace.firstOrNull()?.let { "${it.fileName}:${it.lineNumber}" }}"
        Diag.error = msg
        show(CardModels.message("Something went wrong", "${msg.take(70)}\nTap Copy log and send it to me."))
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
                append("Overlay: ${Diag.line}\n")
                Diag.error?.let { append("Last error: $it\n") }
                append("Recent frames (newest last):\n${Diag.recent()}\n")
                append("Read: cp=${SummaryParser.cp(lastTokens, lastScreenH)} hp=${SummaryParser.hp(lastTokens)} powerUp=${SummaryParser.hasPowerUp(lastTokens)} noHpFrames=$noHpFrames\n")
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
