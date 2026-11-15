package dev.trainerlens

import java.util.Locale

enum class Tone { GREAT, DECENT, WEAK, NEUTRAL }
enum class Mark { GOOD, MAYBE, BAD, NONE }

/** One appraisal bar: [min]..[max] out of 15 (equal when the IV is exact). */
data class StatBar(val min: Int, val max: Int) {
    val text: String get() = if (min == max) "$min" else "$min–$max"
}

data class LeagueRow(val name: String, val value: String, val mark: Mark)

/**
 * Everything the IV card shows, as plain data. A result card always has the same structure (3 stat bars, 3 chips,
 * 4 league rows) so the view never changes size; a message card shows [message] instead. Pure Kotlin, unit-tested.
 */
data class CardModel(
    val title: String,
    val badge: String? = null,
    val tone: Tone = Tone.NEUTRAL,
    val message: String? = null,
    val pct: String = "",
    val pctSub: String = "",
    val ring: Float = 0f,
    val stats: List<StatBar> = emptyList(),
    val chips: List<String> = emptyList(),
    val leagues: List<LeagueRow> = emptyList(),
    val status: String = "",
    val others: List<Species> = emptyList(),
)

object CardModels {
    const val IDLE_TEXT = "Open a Pokémon's page.\nFor exact IVs, tap ☰ → Appraise in the game."

    fun message(title: String, body: String) = CardModel(title = title, message = body)
    fun idle() = message("Trainer Lens", IDLE_TEXT)

    fun rating(total: Int) = when {
        total == 45 -> "Perfect ★★★"
        total >= 37 -> "Excellent ★★★"
        total >= 30 -> "Good ★★"
        total >= 23 -> "Decent ★"
        else -> "Weak"
    }

    fun pct0(x: Double): String = "%.0f".format(Locale.ROOT, x)
    fun lv(x: Double): String = if (x % 1.0 == 0.0) "%.0f".format(Locale.ROOT, x) else "%.1f".format(Locale.ROOT, x)

    fun tone(r: IvResult): Tone {
        val bestRank = listOfNotNull(r.great?.best, r.ultra?.best).minOrNull() ?: Int.MAX_VALUE
        return when {
            bestRank <= 100 || r.pctMin >= 91 -> Tone.GREAT
            bestRank <= 300 || r.pctMin >= 80 -> Tone.DECENT
            else -> Tone.WEAK
        }
    }

    /** Little / Great / Ultra / Master rows. Good = top 100 of the 4,096 spreads in a league it can still enter. */
    fun leagues(r: IvResult, cp: Int): List<LeagueRow> {
        fun row(name: String, cap: Int, rk: RankRange?): LeagueRow = when {
            rk == null -> LeagueRow(name, "—", Mark.NONE)               // the species can't fit this league at all
            cp > cap -> LeagueRow(name, "CP too high", Mark.NONE)       // already powered past the cap
            else -> {
                val rank = if (rk.best == rk.worst) "#${rk.best}" else "#${rk.best}–${rk.worst}"
                when {
                    rk.worst <= 100 -> LeagueRow(name, "$rank ✓", Mark.GOOD)
                    rk.best <= 100 -> LeagueRow(name, "$rank ?", Mark.MAYBE)
                    else -> LeagueRow(name, "$rank ✗", Mark.BAD)
                }
            }
        }
        val master = if (cp > 2500 && r.candidates.all { it.total >= 42 }) LeagueRow("Master", "Good IVs", Mark.GOOD)
        else LeagueRow("Master", "—", Mark.NONE)
        return listOf(row("Little", 500, r.little), row("Great", 1500, r.great), row("Ultra", 2500, r.ultra), master)
    }

    fun from(r: IvResult, m: Mon): CardModel {
        val e = r.exact; val ap = m.appraised
        val cands = r.candidates
        val badge = when {
            m.provisional -> "reading…"
            r.guessed -> "best guess"
            r.nickname -> "nickname"
            else -> null
        }
        val pct = if (e != null) pct0(e.pct) + "%" else {
            val a = pct0(r.pctMin); val b = pct0(r.pctMax)
            (if (a == b) a else "$a–$b") + "%"
        }
        val status = when {
            ap != null && e != null -> "${rating(e.total)} · exact"
            ap != null -> "Appraisal ${ap.a}/${ap.d}/${ap.s} didn't match CP"
            else -> "Appraise in-game for exact IVs"
        } + if (r.arcUsed) " · arc ✓" else ""
        return CardModel(
            title = r.species.label,
            badge = badge,
            tone = tone(r),
            pct = pct,
            pctSub = if (e != null) "${e.total}/45" else "${cands.size} possible",
            ring = ((e?.pct ?: ((r.pctMin + r.pctMax) / 2)) / 100.0).toFloat(),
            stats = listOf(
                StatBar(cands.minOf { it.a }, cands.maxOf { it.a }),
                StatBar(cands.minOf { it.d }, cands.maxOf { it.d }),
                StatBar(cands.minOf { it.s }, cands.maxOf { it.s }),
            ),
            chips = listOf(
                if (r.levelMin == r.levelMax) "Lv ${lv(r.levelMin)}" else "Lv ${lv(r.levelMin)}–${lv(r.levelMax)}",
                "CP ${m.cp}", "HP ${m.hp}",
            ),
            leagues = leagues(r, m.cp),
            status = status,
            others = r.others.distinctBy { it.label }.take(2),
        )
    }
}
