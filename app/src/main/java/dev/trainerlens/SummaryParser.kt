package dev.trainerlens

import kotlin.math.hypot

data class SummaryRead(val cp: Int, val hp: Int, val dust: Int?, val lines: List<String>)

/** Reads CP, max HP and power-up stardust from the Pokémon summary screen (English or German labels). */
object SummaryParser {
    // OCR often confuses digits with look-alike letters, especially when the Pokémon's animation overlaps the CP.
    private const val D = "[0-9OoDQlI|!SsBZzg]"
    private val cpRx = Regex("(?:CP|WP|cp|C\\s?P)\\s*($D{2,5})\\b")
    private val hpRx1 = Regex("($D{1,4})\\s*/\\s*($D{1,4})\\s*(?:HP|KP)", RegexOption.IGNORE_CASE)
    private val hpRx2 = Regex("(?:HP|KP)\\s*($D{1,4})\\s*/\\s*($D{1,4})", RegexOption.IGNORE_CASE)

    fun digits(s: String): Int? = s.map {
        when (it) { 'O', 'o', 'D', 'Q' -> '0'; 'l', 'I', '|', '!' -> '1'; 'S', 's' -> '5'; 'B' -> '8'; 'Z', 'z' -> '2'; 'g' -> '9'; else -> it }
    }.joinToString("").toIntOrNull()
    private val powerUpRx = Regex("power[\\s-]*up", RegexOption.IGNORE_CASE)

    fun parse(tokens: List<Token>, screenH: Int): SummaryRead? {
        // CP is printed in the top part of the screen; ignore "CP" text elsewhere (e.g. move lists)
        val cp = tokens.filter { it.box.centerY() < screenH * 0.35 }
            .firstNotNullOfOrNull { cpRx.find(it.text)?.groupValues?.get(1)?.let(::digits) }?.takeIf { it in 10..9999 }
        val hp = tokens.firstNotNullOfOrNull {
            (hpRx1.find(it.text) ?: hpRx2.find(it.text))?.groupValues?.get(2)?.let(::digits)
        }?.takeIf { it in 10..999 }
        if (cp == null || hp == null) return null

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
