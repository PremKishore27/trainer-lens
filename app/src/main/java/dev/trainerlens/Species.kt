package dev.trainerlens

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/** [form] is "Normal" for the default form, otherwise e.g. "Alola", "Galarian", "Hisuian", "Therian". */
data class Species(val id: String, val name: String, val atk: Int, val def: Int, val hp: Int, val form: String = "Normal") {
    val label: String get() = if (form.equals("Normal", true)) name else "$name (${form.replace('_', ' ')})"
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
 * Base stats come from the community-maintained pogoapi.net database, fetched at runtime and kept
 * in memory only (nothing is written to disk), so new Pokémon appear without an app update.
 */
object SpeciesDb {
    private const val URL_STATS = "https://pogoapi.net/api/v1/pokemon_stats.json"
    @Volatile var all: List<Species> = emptyList(); private set
    @Volatile private var loading = false
    @Volatile private var lastAttempt = 0L
    @Volatile var lastError: String? = null; private set

    /** normalised name -> forms with distinct stats, Normal first. */
    private var byNorm: Map<String, List<Species>> = emptyMap()
    private var longestFirst: List<String> = emptyList()

    val ready: Boolean get() = all.isNotEmpty()

    /** Starts a background fetch if nothing is loaded yet (at most one try per 15 s). */
    fun ensureLoaded(now: Long) {
        if (ready || loading || now - lastAttempt < 15_000) return
        loading = true; lastAttempt = now
        Thread {
            try {
                val c = URL(URL_STATS).openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 15000
                val json = c.inputStream.bufferedReader().use { it.readText() }
                val arr = JSONArray(json)
                val groups = LinkedHashMap<String, MutableList<Species>>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val name = o.getString("pokemon_name")
                    val key = Rules.norm(name)
                    val sp = Species(key, name, o.getInt("base_attack"), o.getInt("base_defense"),
                        o.getInt("base_stamina"), o.optString("form", "Normal"))
                    val list = groups.getOrPut(key) { mutableListOf() }
                    // Costumes etc. share stats with another form; keep one entry per stat line
                    val same = list.indexOfFirst { it.atk == sp.atk && it.def == sp.def && it.hp == sp.hp }
                    if (same < 0) list += sp
                    else if (sp.form.equals("Normal", true)) list[same] = sp
                }
                groups.values.forEach { l -> l.sortBy { if (it.form.equals("Normal", true)) 0 else 1 } }
                byNorm = groups
                longestFirst = groups.keys.sortedByDescending { it.length }
                all = groups.values.flatten()
                lastError = null
            } catch (e: Exception) {
                lastError = e.message ?: "network error"
            } finally { loading = false }
        }.start()
    }

    /** Finds a species name among the OCR lines (exact line first, then a line containing the name). */
    fun find(lines: List<String>): List<Species>? {
        for (l in lines) byNorm[Rules.norm(l)]?.let { return it }
        for (l in lines) {
            val n = Rules.norm(l)
            longestFirst.firstOrNull { it.length >= 5 && n.contains(it) }?.let { return byNorm[it] }
        }
        return null
    }
}
