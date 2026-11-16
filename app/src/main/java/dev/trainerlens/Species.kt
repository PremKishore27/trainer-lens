package dev.trainerlens

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln

/**
 * One Pokémon form with its own base stats. [form] is "Normal" for the default form, otherwise e.g. "Alola",
 * "Galarian", "Hisuian", "Therian". [dex] is the Pokédex number, [types] lower-case type names.
 */
data class Species(
    val id: String, val name: String, val atk: Int, val def: Int, val hp: Int,
    val form: String = "Normal", val dex: Int = 0, val types: Set<String> = emptySet(),
) {
    val isNormal: Boolean get() = form.equals("Normal", true)
    val label: String get() = if (isNormal) name else "$name (${formLabel(form)})"

    private fun formLabel(f: String) = when (f.lowercase()) {
        "alola" -> "Alolan"; "galarian" -> "Galarian"; "hisuian" -> "Hisuian"; "paldea" -> "Paldean"
        else -> f.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
    }
}

data class Iv(val a: Int, val d: Int, val s: Int) {
    val total: Int get() = a + d + s
    val pct: Double get() = total * 100.0 / 45.0
    /** Appraisal stars as the game shows them: 0-3, and 4 for a perfect 15/15/15 ("hundo"). */
    val stars: Int get() = when {
        total == 45 -> 4
        total >= 37 -> 3
        total >= 30 -> 2
        total >= 23 -> 1
        else -> 0
    }
}

/**
 * Everything one screenshot of a Pokémon page says about *which* Pokémon it is. Nothing here needs the name,
 * because the name line shows the nickname:
 *  - [names]: a line that is a Pokémon name (the player didn't rename it, or renamed it to another species' name)
 *  - [family]: the evolution family from the "<SPECIES> CANDY" label
 *  - [types]: the type line, which separates regional forms
 *  - [heightM] / [weightKg]: the size shown under the Pokémon, compared with each species' average
 *  - [evolveCost]: the candy number on the EVOLVE button, which identifies the stage inside a family
 */
data class Clues(
    val names: List<Species> = emptyList(),
    val family: List<Species>? = null,
    val types: Set<String> = emptySet(),
    val heightM: Double? = null,
    val weightKg: Double? = null,
    val evolveCost: Int? = null,
    /** An EVOLVE button was on the page (final evolutions have none). */
    val evolveButton: Boolean = false,
    /** Species named in "This Dragonite was caught on …" at the bottom of the page – the most reliable clue. */
    val caught: List<Species> = emptyList(),
)

data class SpeciesGuess(
    /** Candidates, most likely first. */
    val candidates: List<Species>,
    /** true when the name on screen didn't match (nickname) and the candy family was used instead. */
    val nickname: Boolean,
)

/**
 * Pokémon data from the community-maintained pogoapi.net database: base stats, types, sizes and evolution
 * families. Fetched at runtime and kept in memory only, so new Pokémon appear without an app update.
 */
object SpeciesDb {
    private const val BASE = "https://pogoapi.net/api/v1/"
    @Volatile private var data: Data? = null
    @Volatile private var loading = false
    @Volatile private var lastAttempt = 0L
    @Volatile var lastError: String? = null; private set

    val ready: Boolean get() = data != null
    fun get(): Data? = data

    val TYPES = setOf(
        "normal", "fire", "water", "grass", "electric", "ice", "fighting", "poison", "ground",
        "flying", "psychic", "bug", "rock", "ghost", "dragon", "dark", "steel", "fairy",
    )

    class Data(
        /** normalised name -> forms with distinct stats, Normal first */
        val byName: Map<String, List<Species>>,
        /** dex number -> every form of every Pokémon in the same evolution family */
        val family: Map<Int, List<Species>>,
        /** (dex, lower-case form) -> average (height m, weight kg) */
        val size: Map<Pair<Int, String>, Pair<Double, Double>>,
        /** dex -> candy costs of its evolutions (what the EVOLVE button can show) */
        val evolveCosts: Map<Int, Set<Int>>,
    ) {
        val namesLongestFirst: List<String> = byName.keys.sortedByDescending { it.length }
        val all: List<Species> = byName.values.flatten()
        val allEvolveCosts: Set<Int> = evolveCosts.values.flatten().toSet()
    }

    /** Starts a background fetch if nothing is loaded yet (at most one try per 15 s). */
    fun ensureLoaded(now: Long) {
        if (ready || loading || now - lastAttempt < 15_000) return
        loading = true; lastAttempt = now
        Thread {
            try {
                val sizes = runCatching { fetch("pokemon_height_weight_scale.json") }.getOrNull() // optional extra clue
                data = build(fetch("pokemon_stats.json"), fetch("pokemon_types.json"), fetch("pokemon_evolutions.json"), sizes)
                lastError = null
            } catch (e: Exception) {
                lastError = e.message ?: "network error"
            } finally { loading = false }
        }.start()
    }

    private fun fetch(file: String): String {
        val c = URL(BASE + file).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 15000
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    /** Builds the lookup tables from the pogoapi.net JSON files (also used by the unit tests). */
    fun build(statsJson: String, typesJson: String, evolutionsJson: String, sizesJson: String? = null): Data {
        // types per (dex, form); forms without their own entry use the Normal form's types
        val types = HashMap<Pair<Int, String>, Set<String>>()
        JSONArray(typesJson).let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val t = o.getJSONArray("type").let { ta -> (0 until ta.length()).map { ta.getString(it).lowercase() }.toSet() }
                types[o.getInt("pokemon_id") to o.optString("form", "Normal").lowercase()] = t
            }
        }
        val byName = LinkedHashMap<String, MutableList<Species>>()
        val byDex = HashMap<Int, MutableList<Species>>()
        JSONArray(statsJson).let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val name = o.getString("pokemon_name")
                val dex = o.getInt("pokemon_id")
                val form = o.optString("form", "Normal")
                val key = Rules.norm(name)
                val t = types[dex to form.lowercase()] ?: types[dex to "normal"] ?: emptySet()
                val sp = Species(key, name, o.getInt("base_attack"), o.getInt("base_defense"), o.getInt("base_stamina"), form, dex, t)
                val list = byName.getOrPut(key) { mutableListOf() }
                // Costumes etc. share stats and types with another form; keep one entry per stat line + types
                val same = list.indexOfFirst { it.atk == sp.atk && it.def == sp.def && it.hp == sp.hp && it.types == sp.types }
                if (same < 0) list += sp else if (sp.isNormal) list[same] = sp
            }
        }
        byName.values.forEach { l -> l.sortBy { if (it.isNormal) 0 else 1 } }
        byName.values.flatten().forEach { byDex.getOrPut(it.dex) { mutableListOf() }.add(it) }

        // evolution families by union-find over dex numbers, plus the candy cost of each evolution
        val parent = HashMap<Int, Int>()
        fun root(x: Int): Int { var r = x; while (parent[r] != null && parent[r] != r) r = parent[r]!!; return r }
        fun union(a: Int, b: Int) { val ra = root(a); val rb = root(b); if (ra != rb) parent[maxOf(ra, rb)] = minOf(ra, rb) }
        val evolveCosts = HashMap<Int, MutableSet<Int>>()
        JSONArray(evolutionsJson).let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val from = o.getInt("pokemon_id")
                val evos = o.optJSONArray("evolutions") ?: continue
                for (j in 0 until evos.length()) {
                    val e = evos.getJSONObject(j)
                    union(from, e.getInt("pokemon_id"))
                    val cost = e.optInt("candy_required", 0)
                    if (cost > 0) evolveCosts.getOrPut(from) { mutableSetOf() }.add(cost)
                }
            }
        }
        val members = byDex.keys.groupBy { root(it) }
        val family = HashMap<Int, List<Species>>()
        for ((_, dexes) in members) {
            val all = dexes.sorted().flatMap { byDex[it]!! }
            dexes.forEach { family[it] = all }
        }
        val size = HashMap<Pair<Int, String>, Pair<Double, Double>>()
        sizesJson?.let { js ->
            val arr = JSONArray(js)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                size[o.getInt("pokemon_id") to o.optString("form", "Normal").lowercase()] =
                    o.getDouble("pokedex_height") to o.getDouble("pokedex_weight")
            }
        }
        return Data(byName, family, size, evolveCosts)
    }

    // ---------------------------------------------------------------------------------------------------------
    // Reading the clues from one frame
    // ---------------------------------------------------------------------------------------------------------

    private val candyRx = Regex("candy|bonbon", RegexOption.IGNORE_CASE)
    private val evolveRx = Regex("evolve|entwickel", RegexOption.IGNORE_CASE)
    private val powerUpRx = Regex("power[\\s-]*up", RegexOption.IGNORE_CASE)
    private val kgRx = Regex("(\\d{1,4}[.,]\\d{1,2})\\s*kg", RegexOption.IGNORE_CASE)
    private val mRx = Regex("(\\d{1,2}[.,]\\d{1,2})\\s*m(?![a-zA-Z])", RegexOption.IGNORE_CASE)
    // "This Dragonite was caught on 13/11/2026", "… was hatched / traded / received …", German "Dieses Dragoran wurde …"
    private val caughtRx = Regex("\\b(?:this|dieses|diese|dieser)\\s+(.+?)\\s+(?:was|wurde)\\b", RegexOption.IGNORE_CASE)

    /** Reads every identity clue from the OCR tokens of one frame. */
    fun readClues(tokens: List<Token>, screenH: Int, d: Data? = data): Clues {
        d ?: return Clues()
        val (family, consumed) = candyFamily(tokens, d)

        // A line that is exactly a Pokémon name is the species (or a nickname equal to a species name).
        // Tokens that were used as part of the candy label ("DRATINI" next to "CANDY") are not names.
        val names = LinkedHashSet<Species>()
        for ((i, t) in tokens.withIndex()) if (i !in consumed) d.byName[Rules.norm(t.text)]?.let { names += it }
        if (names.isEmpty()) for ((i, t) in tokens.withIndex()) {
            if (i in consumed) continue
            val n = Rules.norm(t.text)
            d.namesLongestFirst.firstOrNull { it.length >= 5 && n.contains(it) && n.length <= it.length + 6 }?.let { names += d.byName[it]!! }
        }

        val height = tokens.firstNotNullOfOrNull { mRx.find(it.text)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() }
        val weight = tokens.firstNotNullOfOrNull { kgRx.find(it.text)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() }
        val caught = readCaught(tokens, d)
        return Clues(names.toList(), family, typesOnScreen(tokens, screenH), height, weight, evolveCost(tokens, screenH, d),
            tokens.any { evolveRx.containsMatchIn(it.text) }, caught)
    }

    private val caughtStartRx = Regex("^\\s*(?:this|dieses|diese|dieser)\\s+(.+?)\\s*$", RegexOption.IGNORE_CASE)
    private val wasRx = Regex("^(?:was|wurde)\\b", RegexOption.IGNORE_CASE)
    /** Words the game puts in front of a species in the "caught" line; regional ones also pick the form. */
    private val regionalWords = mapOf("alolan" to "alol", "alola" to "alol", "galarian" to "galar", "galar" to "galar",
        "hisuian" to "hisui", "hisui" to "hisui", "paldean" to "palde", "paldea" to "palde")
    private val prefixWords = setOf("mega", "shadow", "purified", "primal", "gigantamax", "dynamax")

    /**
     * The species named in "This Dragonite was caught on …". Also works when the line wrapped before "was", when a
     * form word is in front ("Alolan Exeggutor", "Mega Charizard X") and when the OCR got one letter wrong.
     */
    fun readCaught(tokens: List<Token>, d: Data): List<Species> {
        for (t in tokens) {
            var phrase = caughtRx.find(t.text)?.groupValues?.get(1)
            if (phrase == null) {
                val start = caughtStartRx.find(t.text)?.groupValues?.get(1)?.takeIf { it.split(Regex("\\s+")).size <= 5 } ?: continue
                val h = t.box.height().coerceAtLeast(1)
                val wrapped = tokens.any { o -> o !== t && o.box.top >= t.box.top && o.box.top - t.box.bottom <= h * 2 && wasRx.containsMatchIn(o.text.trim()) }
                if (!wrapped) continue
                phrase = start
            }
            resolveCaught(phrase, d).takeIf { it.isNotEmpty() }?.let { return it }
        }
        return emptyList()
    }

    private fun resolveCaught(phrase: String, d: Data): List<Species> {
        val words = phrase.trim().split(Regex("\\s+")).toMutableList()
        var region: String? = null
        while (words.size > 1) {
            val w = words.first().lowercase().filter { it.isLetter() }
            val r = regionalWords[w]
            if (r != null) region = r else if (w !in prefixWords) break
            words.removeAt(0)
        }
        val n = Rules.norm(words.joinToString(""))
        var found = lookup(n, d)
        if (found.isEmpty()) { // "Charizard X": a known name inside the phrase
            d.namesLongestFirst.firstOrNull { it.length >= 5 && n.contains(it) && n.length <= it.length + 8 }?.let { found = d.byName[it]!! }
        }
        if (region != null) found.filter { it.form.lowercase().startsWith(region) }.takeIf { it.isNotEmpty() }?.let { found = it }
        return found
    }

    /** Candy-cost number on the EVOLVE button, e.g. 25 (Dratini) or 100 (Dragonair). */
    private fun evolveCost(tokens: List<Token>, screenH: Int, d: Data): Int? {
        val ev = tokens.firstOrNull { evolveRx.containsMatchIn(it.text) } ?: return null
        Regex("\\d{2,3}").findAll(ev.text).map { it.value.toInt() }.firstOrNull { it in d.allEvolveCosts }?.let { return it }
        val reach = if (screenH > 0) screenH * 0.12 else Double.MAX_VALUE
        return tokens.filter { it !== ev }
            .mapNotNull { t -> t.text.trim().toIntOrNull()?.takeIf { it in d.allEvolveCosts }?.let { t to it } }
            .filter { (t, _) -> dist(t.box, ev.box) <= reach }
            .minByOrNull { (t, _) -> dist(t.box, ev.box) }?.second
    }

    private fun dist(a: Box, b: Box) = hypot((a.centerX() - b.centerX()).toDouble(), (a.centerY() - b.centerY()).toDouble())

    /**
     * Finds the evolution family from a "<SPECIES> CANDY" label. Tolerates a count in front ("245 DRATINI CANDY"),
     * a "CANDY XL" label, one wrong letter, and OCR splitting the label into "DRATINI" and "CANDY" lines.
     * Returns the family plus the indices of every token that belonged to the label.
     */
    private fun candyFamily(tokens: List<Token>, d: Data): Pair<List<Species>?, Set<Int>> {
        val consumed = HashSet<Int>()
        var family: List<Species>? = null
        for ((i, t) in tokens.withIndex()) {
            val m = candyRx.find(t.text) ?: continue
            consumed += i
            if (family != null) continue
            val words = t.text.substring(0, m.range.first).split(Regex("\\s+"))
                .map { w -> w.filter { it.isLetter() || it in "'.-♀♂" || it.isDigit() } }
                .filter { w -> w.any { it.isLetter() } && !w.equals("xl", true) }
            var found: List<Species> = emptyList()
            for (k in minOf(3, words.size) downTo 1) {
                found = lookup(Rules.norm(words.takeLast(k).joinToString("")), d)
                if (found.isNotEmpty()) break
            }
            if (found.isEmpty() && words.isEmpty()) {
                // a lone "CANDY": the species name is the nearest line to its left or just above it
                val h = t.box.height().coerceAtLeast(1)
                val neighbours = tokens.withIndex().filter { (j, o) ->
                    if (j == i || candyRx.containsMatchIn(o.text)) return@filter false
                    val dy = o.box.centerY() - t.box.centerY(); val gap = t.box.left - o.box.right
                    val sameRow = abs(dy) <= h && gap >= -h && gap <= h * 8
                    val above = dy < 0 && -dy <= h * 3 && abs(o.box.centerX() - t.box.centerX()) <= h * 6
                    sameRow || above
                }.sortedBy { (_, o) -> dist(o.box, t.box) }
                for ((j, o) in neighbours) {
                    val n = Rules.norm(o.text.filter { !it.isDigit() })
                    lookup(n, d).takeIf { it.isNotEmpty() }?.let { found = it; consumed += j }
                    if (found.isNotEmpty()) break
                }
            }
            if (found.isNotEmpty()) family = found.map { it.dex }.distinct().flatMap { d.family[it].orEmpty() }.distinct()
        }
        return family to consumed
    }

    /** Exact name, or – for names of 5+ letters – every name that is one letter off (OCR slips). */
    private fun lookup(n: String, d: Data): List<Species> {
        if (n.isEmpty()) return emptyList()
        d.byName[n]?.let { return it }
        if (n.length < 5) return emptyList()
        val out = ArrayList<Species>()
        for ((k, v) in d.byName) if (abs(k.length - n.length) <= 1 && lev(k, n) == 1) out += v
        return out
    }

    private fun lev(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1); cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }

    /**
     * Type words on the page, e.g. "GRASS / DRAGON" -> {grass, dragon}. Only lines made of nothing but types count,
     * and only above the POWER UP button – the move list further down can contain a move called "Psychic".
     */
    fun typesOnScreen(tokens: List<Token>, screenH: Int): Set<String> {
        val powerUp = tokens.firstOrNull { powerUpRx.containsMatchIn(it.text) }
        val limit = powerUp?.box?.top ?: (screenH * 0.62).toInt()
        val out = HashSet<String>()
        for (t in tokens) {
            if (screenH > 0 && t.box.centerY() >= limit) continue
            // "DRAGON / FLY": a type word cut off at the edge still counts if it's the start of exactly one type
            val words = t.text.lowercase().split(Regex("[^a-z]+")).filter { it.isNotEmpty() }.map { w ->
                if (w in TYPES) w else TYPES.filter { w.length >= 3 && it.startsWith(w) }.singleOrNull()
            }
            if (words.isNotEmpty() && words.size <= 2 && words.all { it != null }) out += words.filterNotNull()
        }
        return if (out.size <= 2) out else emptySet()
    }

    // ---------------------------------------------------------------------------------------------------------
    // Turning clues into candidates
    // ---------------------------------------------------------------------------------------------------------

    /** Soft evidence that [sp] is the Pokémon on screen: evolve cost and size. Higher = more likely. */
    fun score(sp: Species, c: Clues, d: Data): Double {
        var s = 0.0
        val evolvable = d.evolveCosts[sp.dex]?.isNotEmpty() == true
        if (c.evolveCost != null) s += if (d.evolveCosts[sp.dex]?.contains(c.evolveCost) == true) 10.0 else -10.0
        else if (c.evolveButton && !evolvable) s -= 2.0   // a button was there, so it can still evolve
        else if (!c.evolveButton && evolvable) s -= 1.0   // no button: probably a final evolution (soft: OCR can miss it)
        val sz = d.size[sp.dex to sp.form.lowercase()] ?: d.size[sp.dex to "normal"]
        if (sz != null) {
            c.heightM?.let { if (it > 0 && sz.first > 0) s -= abs(ln(it / sz.first)) }
            c.weightKg?.let { if (it > 0 && sz.second > 0) s -= abs(ln(it / sz.second)) / 3 }
        }
        return s
    }

    /** More than 8× off the species' average weight – no XXS/XXL Pokémon is that far off. */
    private fun wrongSize(sp: Species, c: Clues, d: Data): Boolean {
        val w = c.weightKg ?: return false
        val avg = (d.size[sp.dex to sp.form.lowercase()] ?: d.size[sp.dex to "normal"])?.second ?: return false
        return w > 0 && avg > 0 && abs(ln(w / avg)) > ln(8.0)
    }

    /** The likely species, most likely first, from name / candy family / types, ordered by size and evolve cost. */
    fun candidates(c: Clues, d: Data? = data): SpeciesGuess? {
        d ?: return null
        // "This Dragonite was caught on …" names the species outright; types pick the form
        if (c.caught.isNotEmpty()) {
            val forms = c.caught.distinct() // all forms of the named species, or just the regional one named in the line
            val byType = if (c.types.isEmpty()) forms else forms.filter { it.types == c.types }.ifEmpty { forms.filter { it.types.containsAll(c.types) } }
            val nick = c.names.none { n -> forms.any { it.dex == n.dex } }
            return SpeciesGuess(byType.ifEmpty { forms }, nick)
        }
        val fam = c.family
        var nickname = false
        var cands: List<Species> = when {
            fam == null -> c.names
            c.names.any { it in fam } -> c.names.filter { it in fam } + fam.filter { it !in c.names }
            else -> { nickname = true; fam }
        }
        if (cands.isEmpty()) return null
        if (c.types.isNotEmpty()) {
            val exact = cands.filter { it.types == c.types }
            val partial = cands.filter { it.types.containsAll(c.types) }
            // A name whose types don't match the page is a nickname that happens to be a Pokémon ("CutieFly" on a
            // Dragon/Flying Pokémon): don't trust it. With no candy family left, let the widened search decide.
            cands = exact.ifEmpty { partial }.ifEmpty { if (fam == null) return null else fam }
        }
        if (fam == null) {
            // same for a name that is wildly the wrong size (Cutiefly 0.2 kg vs. 314 kg on screen)
            cands = cands.filter { sp -> !wrongSize(sp, c, d) }
            if (cands.isEmpty()) return null
        }
        val named = c.names.toSet()
        cands = cands.sortedWith(compareByDescending<Species> { it in named }.thenByDescending { score(it, c, d) })
        return SpeciesGuess(cands, nickname)
    }

    /**
     * Last resort when the name and candy gave nothing that fits: every Pokémon with the types on screen (or all of
     * them), best size / evolve-cost evidence first. Needs at least one real clue, or the answer would be a coin flip.
     */
    fun widened(c: Clues, d: Data? = data): List<Species>? {
        d ?: return null
        if (c.types.isEmpty() && c.heightM == null && c.weightKg == null && c.evolveCost == null) return null
        val pool = if (c.types.isEmpty()) d.all else d.all.filter { it.types == c.types }.ifEmpty { d.all.filter { it.types.containsAll(c.types) } }
        return pool.sortedByDescending { score(it, c, d) }
    }

    /** One call for tests and simple uses: read the clues from [tokens] and rank the candidates. */
    fun guess(tokens: List<Token>, screenH: Int, d: Data? = data): SpeciesGuess? = candidates(readClues(tokens, screenH, d), d)
}
