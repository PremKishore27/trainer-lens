package dev.trainerlens

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

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

/** What's known about the Pokémon on screen, used to pick the species. */
data class SpeciesGuess(
    /** Candidates, most likely first. */
    val candidates: List<Species>,
    /** true when the name on screen didn't match (nickname) and the candy family was used instead. */
    val nickname: Boolean,
)

/**
 * Pokémon data from the community-maintained pogoapi.net database: base stats, types and evolution families.
 * Fetched at runtime and kept in memory only, so new Pokémon appear without an app update.
 */
object SpeciesDb {
    private const val BASE = "https://pogoapi.net/api/v1/"
    @Volatile private var data: Data? = null
    @Volatile private var loading = false
    @Volatile private var lastAttempt = 0L
    @Volatile var lastError: String? = null; private set

    val ready: Boolean get() = data != null

    val TYPES = setOf(
        "normal", "fire", "water", "grass", "electric", "ice", "fighting", "poison", "ground",
        "flying", "psychic", "bug", "rock", "ghost", "dragon", "dark", "steel", "fairy",
    )

    class Data(
        /** normalised name -> forms with distinct stats, Normal first */
        val byName: Map<String, List<Species>>,
        /** dex number -> every form of every Pokémon in the same evolution family */
        val family: Map<Int, List<Species>>,
    ) {
        val namesLongestFirst: List<String> = byName.keys.sortedByDescending { it.length }
    }

    /** Starts a background fetch if nothing is loaded yet (at most one try per 15 s). */
    fun ensureLoaded(now: Long) {
        if (ready || loading || now - lastAttempt < 15_000) return
        loading = true; lastAttempt = now
        Thread {
            try {
                data = build(fetch("pokemon_stats.json"), fetch("pokemon_types.json"), fetch("pokemon_evolutions.json"))
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

    /** Builds the lookup tables from the three pogoapi.net JSON files (also used by the unit tests). */
    fun build(statsJson: String, typesJson: String, evolutionsJson: String): Data {
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

        // evolution families by union-find over dex numbers
        val parent = HashMap<Int, Int>()
        fun root(x: Int): Int { var r = x; while (parent[r] != null && parent[r] != r) r = parent[r]!!; return r }
        fun union(a: Int, b: Int) { val ra = root(a); val rb = root(b); if (ra != rb) parent[maxOf(ra, rb)] = minOf(ra, rb) }
        JSONArray(evolutionsJson).let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val from = o.getInt("pokemon_id")
                val evos = o.optJSONArray("evolutions") ?: continue
                for (j in 0 until evos.length()) union(from, evos.getJSONObject(j).getInt("pokemon_id"))
            }
        }
        val members = byDex.keys.groupBy { root(it) }
        val family = HashMap<Int, List<Species>>()
        for ((_, dexes) in members) {
            val all = dexes.sorted().flatMap { byDex[it]!! }
            dexes.forEach { family[it] = all }
        }
        return Data(byName, family)
    }

    private val candyRx = Regex("^\\d*(.+?)(?:candyxl|candy|bonbonxl|bonbon|xlbonbon)$")

    /**
     * Works out which Pokémon is on screen from the OCR lines:
     *  - the name line (exact match, then a line that contains a name),
     *  - the "<SPECIES> CANDY" line: candy is shared by the whole evolution family, so it still works for nicknamed
     *    Pokémon and tells apart a nickname that happens to be another Pokémon's name,
     *  - the type line ("GRASS / DRAGON"), which separates regional forms such as Alolan Exeggutor.
     */
    fun guess(tokens: List<Token>, screenH: Int, d: Data? = data): SpeciesGuess? {
        d ?: return null
        val norms = tokens.map { Rules.norm(it.text) }
        val named = LinkedHashSet<Species>()
        for (n in norms) d.byName[n]?.let { named += it }
        if (named.isEmpty()) for (n in norms) {
            if (n.endsWith("candy") || n.endsWith("candyxl")) continue
            d.namesLongestFirst.firstOrNull { it.length >= 5 && n.contains(it) && n.length <= it.length + 6 }?.let { named += d.byName[it]!! }
        }
        val family = norms.firstNotNullOfOrNull { n ->
            candyRx.matchEntire(n)?.groupValues?.get(1)?.let { d.byName[it] }?.first()?.let { d.family[it.dex] }
        }
        val seenTypes = typesOnScreen(tokens, screenH)

        var nickname = false
        var cands: List<Species> = when {
            family == null -> named.toList()
            named.any { it in family } -> named.filter { it in family } + family.filter { it !in named }
            else -> { nickname = true; family }
        }
        if (cands.isEmpty()) return null
        if (seenTypes.isNotEmpty()) {
            val exact = cands.filter { it.types == seenTypes }
            val partial = cands.filter { it.types.containsAll(seenTypes) }
            cands = exact.ifEmpty { partial }.ifEmpty { cands }
        }
        return SpeciesGuess(cands, nickname)
    }

    /**
     * Type words on the page, e.g. "GRASS / DRAGON" -> {grass, dragon}. Only lines made of nothing but types count,
     * and only above the POWER UP button – the move list further down can contain a move called "Psychic".
     */
    fun typesOnScreen(tokens: List<Token>, screenH: Int): Set<String> {
        val powerUp = tokens.firstOrNull { Regex("power[\\s-]*up", RegexOption.IGNORE_CASE).containsMatchIn(it.text) }
        val limit = powerUp?.box?.top ?: (screenH * 0.62).toInt()
        val out = HashSet<String>()
        for (t in tokens) {
            if (screenH > 0 && t.box.centerY() >= limit) continue
            val words = t.text.lowercase().split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
            if (words.isNotEmpty() && words.size <= 2 && words.all { it in TYPES }) out += words
        }
        return if (out.size <= 2) out else emptySet()
    }
}
