package dev.trainerlens

/**
 * One Pokémon page as seen over several frames. Identity is (CP, HP) only; everything else – candy family, types,
 * name, size, stardust – is *collected* across frames, so a clue that is missing in one frame (or read wrongly once)
 * doesn't change the answer.
 */
class Mon(val cp: Int, val hp: Int) {
    var frames = 0; private set
    var appraised: Iv? = null
    /** Species the player picked from the "not it?" buttons. */
    var forced: Species? = null
    var dot: Pair<Float, Float>? = null
    var dotTried = false
    var arcLevel: Double? = null

    private val nameVotes = HashMap<Species, Int>()
    private val families = HashMap<Int, List<Species>>()
    private val familyVotes = HashMap<Int, Int>()
    private val typeVotes = HashMap<Set<String>, Int>()
    private val dustVotes = HashMap<Int, Int>()
    private var heightM: Double? = null
    private var weightKg: Double? = null
    private var evolveCost: Int? = null
    private var evolveSeen = false

    fun add(c: Clues, dust: Int?) {
        frames++
        c.names.forEach { nameVotes.merge(it, 1, Int::plus) }
        c.family?.firstOrNull()?.let { families[it.dex] = c.family; familyVotes.merge(it.dex, 1, Int::plus) }
        if (c.types.isNotEmpty()) typeVotes.merge(c.types, 1, Int::plus)
        dust?.let { dustVotes.merge(it, 1, Int::plus) }
        c.heightM?.let { heightM = it }; c.weightKg?.let { weightKg = it }; c.evolveCost?.let { evolveCost = it }
        if (c.evolveButton) evolveSeen = true // seen on any frame counts: OCR missing it is likelier than a phantom button
    }

    /** The most-seen reading of each clue so far. */
    fun clues(): Clues = Clues(
        names = nameVotes.keys.toList(),
        family = familyVotes.maxByOrNull { it.value }?.key?.let { families[it] },
        types = typeVotes.maxByOrNull { it.value }?.key ?: emptySet(),
        heightM = heightM, weightKg = weightKg, evolveCost = evolveCost, evolveButton = evolveSeen,
    )

    val dust: Int? get() = dustVotes.maxByOrNull { it.value }?.key
    /** Seen on only one frame so far: shown, but may still change. */
    val provisional: Boolean get() = frames < 2

    /** Changes whenever something that affects the answer changes. */
    fun signature(): String {
        val c = clues()
        return "${c.names.map { it.id + it.form }.sorted()}|${c.family?.firstOrNull()?.dex}|${c.types.sorted()}|" +
            "${c.heightM}|${c.weightKg}|${c.evolveCost}|${c.evolveButton}|$dust|$appraised|${forced?.let { it.id + it.form }}|$arcLevel|$provisional"
    }
}

/**
 * Follows the Pokémon on screen while the player swipes between them.
 *  - A page with new CP/HP is accepted at once, from the first frame, if some species fits it (provisional).
 *  - A new reading that fits nothing is ignored (probably an animation misread) unless it is seen twice.
 *  - The analysis is re-run only when the collected clues change.
 */
class MonTracker(private val analyze: (Mon) -> IvResult?) {
    var current: Mon? = null; private set
    var result: IvResult? = null; private set
    private var sig = ""
    private var bad: Mon? = null
    private var pendingBars: Iv? = null

    /** @return true when what should be shown has changed */
    fun observe(cp: Int, hp: Int, dust: Int?, clues: Clues): Boolean {
        val cur = current
        if (cur != null && cur.cp == cp && cur.hp == hp) { cur.add(clues, dust); return refresh(cur) }
        val cand = Mon(cp, hp).also { it.add(clues, dust) }
        val r = analyze(cand)
        if (r != null) { current = cand; result = r; sig = cand.signature(); bad = null; pendingBars = null; return true }
        val b = bad
        if (b != null && b.cp == cp && b.hp == hp) {
            b.add(clues, dust)
            if (b.frames >= 2) { current = b; result = analyze(b); sig = b.signature(); bad = null; pendingBars = null; return true }
        } else bad = cand
        return false
    }

    /** Appraisal bars count once they're read identically on two frames. */
    fun observeBars(bars: Iv?): Boolean {
        val cur = current ?: return false
        if (bars == null) return false
        val stable = bars == pendingBars
        pendingBars = bars
        if (!stable || cur.appraised == bars) return false
        cur.appraised = bars
        return refresh(cur)
    }

    /** Re-runs the analysis if something changed (also after the level arc found a dot). */
    fun refresh(m: Mon? = current): Boolean {
        m ?: return false
        val s = m.signature()
        if (s == sig) return false
        sig = s; result = analyze(m)
        return true
    }

    /** The player tapped a species; null if it doesn't fit (the previous choice is kept). */
    fun choose(sp: Species): Boolean {
        val cur = current ?: return false
        val old = cur.forced
        cur.forced = sp
        if (analyze(cur) == null) { cur.forced = old; return false }
        sig = ""; return refresh(cur)
    }

    fun reset() { current = null; result = null; sig = ""; bad = null; pendingBars = null }
}

/** The species decision for one Pokémon, shared by the app and the tests. */
object Identify {
    fun analyze(m: Mon, d: SpeciesDb.Data): IvResult? {
        val clues = m.clues()
        val forced = m.forced
        val guess = if (forced != null) SpeciesGuess(listOf(forced), false) else SpeciesDb.candidates(clues, d)
        var r = guess?.let { IvAnalyzer.analyze(it.candidates, m.cp, m.hp, m.dust, m.appraised, m.arcLevel) }?.copy(nickname = guess.nickname)
        if (r == null && forced == null) {
            // the name / candy gave nothing that fits – search by types, size and evolve cost instead
            r = SpeciesDb.widened(clues, d)?.let { IvAnalyzer.analyze(it, m.cp, m.hp, m.dust, m.appraised, m.arcLevel) }?.copy(guessed = true)
        }
        return r
    }
}
