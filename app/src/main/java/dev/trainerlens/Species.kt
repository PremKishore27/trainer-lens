package dev.trainerlens

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

data class Species(val id: String, val name: String, val atk: Int, val def: Int, val hp: Int)

data class Iv(val a: Int, val d: Int, val s: Int) {
    val pct: Double get() = (a + d + s) * 100.0 / 45.0
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

    private var byNorm: Map<String, Species> = emptyMap()
    private var longestFirst: List<Species> = emptyList()

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
                val list = LinkedHashMap<String, Species>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    // base forms only; Alolan/Galarian/etc. are separate entries we deliberately skip
                    if (!o.optString("form", "Normal").equals("Normal", true)) continue
                    val name = o.getString("pokemon_name")
                    list.putIfAbsent(Rules.norm(name), Species(
                        Rules.norm(name), name,
                        o.getInt("base_attack"), o.getInt("base_defense"), o.getInt("base_stamina")))
                }
                byNorm = list
                longestFirst = list.values.sortedByDescending { it.name.length }
                all = list.values.toList()
                lastError = null
            } catch (e: Exception) {
                lastError = e.message ?: "network error"
            } finally { loading = false }
        }.start()
    }

    /** Finds a species name among the OCR lines (exact line first, then a line containing the name). */
    fun find(lines: List<String>): Species? {
        for (l in lines) byNorm[Rules.norm(l)]?.let { return it }
        for (l in lines) {
            val n = Rules.norm(l)
            longestFirst.firstOrNull { it.name.length >= 5 && n.contains(Rules.norm(it.name)) }?.let { return it }
        }
        return null
    }
}
