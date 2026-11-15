package dev.trainerlens

import kotlin.math.hypot

data class SummaryRead(val cp: Int, val hp: Int, val dust: Int?, val lines: List<String>)

/** Reads CP, max HP and power-up stardust from the Pokémon summary screen (English or German labels). */
object SummaryParser {
    private val cpRx = Regex("(?:CP|WP)\\s*(\\d{2,5})", RegexOption.IGNORE_CASE)
    private val hpRx1 = Regex("(\\d{1,4})\\s*/\\s*(\\d{1,4})\\s*(?:HP|KP)", RegexOption.IGNORE_CASE)
    private val hpRx2 = Regex("(?:HP|KP)\\s*(\\d{1,4})\\s*/\\s*(\\d{1,4})", RegexOption.IGNORE_CASE)
    private val powerUpRx = Regex("power[\\s-]*up", RegexOption.IGNORE_CASE)

    fun parse(tokens: List<Token>, screenH: Int): SummaryRead? {
        val cp = tokens.firstNotNullOfOrNull { cpRx.find(it.text)?.groupValues?.get(1)?.toIntOrNull() } ?: return null
        val hp = tokens.firstNotNullOfOrNull {
            (hpRx1.find(it.text) ?: hpRx2.find(it.text))?.groupValues?.get(2)?.toIntOrNull()
        } ?: return null

        // Stardust cost candidates: plain numbers in the lower half that are a real power-up cost.
        val dustTokens = tokens
            .filter { it.box.centerY() > screenH * 0.55 }
            .mapNotNull { t -> t.text.replace(Regex("[.,\\s]"), "").toIntOrNull()?.let { t to it } }
            .filter { (_, v) -> v in Cpm.validDust && v != cp }
        // The cost sits next to the POWER UP button; your stardust *total* is printed elsewhere on the page,
        // so prefer the number closest to that button. Fallback: the largest (candy cost is always smaller).
        val button = tokens.firstOrNull { powerUpRx.containsMatchIn(it.text) }
        val dust = if (button != null) dustTokens.minByOrNull { (t, _) ->
            hypot((t.box.centerX() - button.box.centerX()).toDouble(), (t.box.centerY() - button.box.centerY()).toDouble())
        }?.second
        else dustTokens.maxOfOrNull { it.second }
        return SummaryRead(cp, hp, dust, tokens.map { it.text })
    }
}
