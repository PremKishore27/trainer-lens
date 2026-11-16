package dev.trainerlens

import kotlin.math.hypot

data class SummaryRead(val cp: Int, val hp: Int, val dust: Int?)

/** Reads CP, max HP and power-up stardust from the Pokémon summary screen (English or German labels). */
object SummaryParser {
    // OCR often confuses digits with look-alike letters, especially when the Pokémon's animation overlaps the CP.
    private const val D = "[0-9OoDQlI|!SsBZzg]"
    private val cpRx = Regex("(?:CP|WP|cp|C\\s?P)\\s*($D{2,5})\\b")
    // The small "CP" letters are often read as a single "c" / "C" / "©" ("c3259" – seen on a real phone)
    private val cpShortRx = Regex("^[cC©(]\\s?($D{3,5})$")
    private val hpRx1 = Regex("($D{1,4})\\s*/\\s*($D{1,4})\\s*(?:HP|KP)", RegexOption.IGNORE_CASE)
    private val hpRx2 = Regex("(?:HP|KP)\\s*($D{1,4})\\s*/\\s*($D{1,4})", RegexOption.IGNORE_CASE)

    fun digits(s: String): Int? = s.map {
        when (it) { 'O', 'o', 'D', 'Q' -> '0'; 'l', 'I', '|', '!' -> '1'; 'S', 's' -> '5'; 'B' -> '8'; 'Z', 'z' -> '2'; 'g' -> '9'; else -> it }
    }.joinToString("").toIntOrNull()
    private val powerUpRx = Regex("power[\\s-]*up", RegexOption.IGNORE_CASE)

    /** Max HP alone ("163 /163 HP") – still readable on the appraisal screen when the CP isn't. */
    fun hp(tokens: List<Token>): Int? = tokens.firstNotNullOfOrNull {
        (hpRx1.find(it.text) ?: hpRx2.find(it.text))?.groupValues?.get(2)?.let(::digits)
    }?.takeIf { it in 10..999 }

    /** The CP number, printed in the top part of the screen (ignores "CP" text elsewhere, e.g. move lists). */
    fun cp(tokens: List<Token>, screenH: Int): Int? {
        val top = tokens.filter { it.box.centerY() < screenH * 0.35 }
        return top.firstNotNullOfOrNull { cpRx.find(it.text)?.groupValues?.get(1)?.let(::digits)?.takeIf { v -> v in 10..9999 } }
            ?: top.filter { it.box.centerY() < screenH * 0.15 }
                .firstNotNullOfOrNull { cpShortRx.matchEntire(it.text.trim())?.groupValues?.get(1)?.let(::digits)?.takeIf { v -> v in 10..9999 } }
    }

    /** A POWER UP button is on screen: this is a Pokémon page (the list and map screens don't have one). */
    fun hasPowerUp(tokens: List<Token>): Boolean = tokens.any { powerUpRx.containsMatchIn(it.text) }

    private val pageWordRx = Regex("^(?:weight|height|dust|stardust|gewicht|größe|sternenstaub)$", RegexOption.IGNORE_CASE)

    /**
     * This is a Pokémon page, with or without the POWER UP button (the appraisal screen hides it): the page's
     * WEIGHT / HEIGHT / DUST labels or a POWER UP button are on screen. The Pokémon list shows CP numbers too, but no such labels.
     */
    fun looksLikePokemonPage(tokens: List<Token>): Boolean =
        hasPowerUp(tokens) || tokens.any { pageWordRx.matches(it.text.trim()) }

    fun parse(tokens: List<Token>, screenH: Int): SummaryRead? {
        val cp = cp(tokens, screenH)
        val hp = hp(tokens)
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
        return SummaryRead(cp, hp, dust)
    }
}
