package dev.trainerlens

data class SummaryRead(val cp: Int, val hp: Int, val dust: Int?, val lines: List<String>)

/** Reads CP, max HP and power-up stardust from the Pokémon summary screen (English or German labels). */
object SummaryParser {
    private val cpRx = Regex("(?:CP|WP)\\s*(\\d{2,5})", RegexOption.IGNORE_CASE)
    private val hpRx1 = Regex("(\\d{1,4})\\s*/\\s*(\\d{1,4})\\s*(?:HP|KP)", RegexOption.IGNORE_CASE)
    private val hpRx2 = Regex("(?:HP|KP)\\s*(\\d{1,4})\\s*/\\s*(\\d{1,4})", RegexOption.IGNORE_CASE)

    fun parse(tokens: List<Token>, screenH: Int): SummaryRead? {
        val cp = tokens.firstNotNullOfOrNull { cpRx.find(it.text)?.groupValues?.get(1)?.toIntOrNull() } ?: return null
        val hp = tokens.firstNotNullOfOrNull {
            (hpRx1.find(it.text) ?: hpRx2.find(it.text))?.groupValues?.get(2)?.toIntOrNull()
        } ?: return null
        val dust = tokens
            .filter { it.box.centerY() > screenH * 0.55 }
            .mapNotNull { it.text.replace(Regex("[.,\\s]"), "").toIntOrNull() }
            .filter { it in Cpm.validDust && it != cp }
            .maxOrNull() // candy cost is always smaller than the dust cost
        return SummaryRead(cp, hp, dust, tokens.map { it.text })
    }
}
