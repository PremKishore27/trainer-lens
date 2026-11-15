package dev.trainerlens

import java.text.Normalizer

enum class Rarity(val label: String) { COMMON("Common"), UNCOMMON("Uncommon"), RARE("Rare") }

/** keep = how many to keep. Int.MAX_VALUE = never discard. */
data class ItemRule(val name: String, val keep: Int, val rarity: Rarity)

object Rules {
    const val NEVER = Int.MAX_VALUE
    private val C = Rarity.COMMON
    private val U = Rarity.UNCOMMON
    private val R = Rarity.RARE

    // Edit freely. Order = discard order: most common / least useful first, rare items are never suggested.
    val DEFAULT_RULES = listOf(
        // Common – easy to get from PokéStops, suggested first
        ItemRule("Potion", 0, C),
        ItemRule("Super Potion", 0, C),
        ItemRule("Nanab Berry", 20, C),
        ItemRule("Revive", 30, C),
        ItemRule("Poke Ball", 50, C),
        ItemRule("Hyper Potion", 20, C),
        ItemRule("Razz Berry", 50, C),
        ItemRule("Great Ball", 50, C),
        ItemRule("Pinap Berry", 30, C),
        // Uncommon – useful, only the surplus is suggested
        ItemRule("Max Potion", 50, U),
        ItemRule("Max Revive", 50, U),
        ItemRule("Ultra Ball", 150, U),
        ItemRule("Silver Pinap Berry", 30, U),
        // Rare – never suggested
        ItemRule("Golden Razz Berry", NEVER, R),
        ItemRule("Rare Candy", NEVER, R),
        ItemRule("Rare Candy XL", NEVER, R),
        ItemRule("Master Ball", NEVER, R),
        ItemRule("Lucky Egg", NEVER, R),
        ItemRule("Star Piece", NEVER, R),
        ItemRule("Gift", NEVER, R), // send to friends instead
        ItemRule("Incense", NEVER, R),
        ItemRule("Daily Adventure Incense", NEVER, R),
        ItemRule("Lure Module", NEVER, R),
        ItemRule("Glacial Lure Module", NEVER, R),
        ItemRule("Mossy Lure Module", NEVER, R),
        ItemRule("Magnetic Lure Module", NEVER, R),
        ItemRule("Rainy Lure Module", NEVER, R),
        ItemRule("Golden Lure Module", NEVER, R),
        ItemRule("Raid Pass", NEVER, R),
        ItemRule("Premium Battle Pass", NEVER, R),
        ItemRule("Remote Raid Pass", NEVER, R),
        ItemRule("Egg Incubator", NEVER, R),
        ItemRule("Super Incubator", NEVER, R),
        ItemRule("Camera", NEVER, R),
        ItemRule("Poffin", NEVER, R),
        ItemRule("Sun Stone", NEVER, R),
        ItemRule("King's Rock", NEVER, R),
        ItemRule("Metal Coat", NEVER, R),
        ItemRule("Dragon Scale", NEVER, R),
        ItemRule("Up-Grade", NEVER, R),
        ItemRule("Sinnoh Stone", NEVER, R),
        ItemRule("Unova Stone", NEVER, R),
        ItemRule("Fast TM", NEVER, R),
        ItemRule("Charged TM", NEVER, R),
        ItemRule("Elite Fast TM", NEVER, R),
        ItemRule("Elite Charged TM", NEVER, R),
        ItemRule("Rocket Radar", NEVER, R),
        ItemRule("Super Rocket Radar", NEVER, R),
        ItemRule("Mysterious Component", NEVER, R),
        ItemRule("Magmarizer", NEVER, R),
        ItemRule("Electirizer", NEVER, R),
        ItemRule("Reaper Cloth", NEVER, R),
        ItemRule("Protector", NEVER, R),
        ItemRule("Dubious Disc", NEVER, R),
        ItemRule("Deep Sea Tooth", NEVER, R),
        ItemRule("Deep Sea Scale", NEVER, R),
        ItemRule("Razor Claw", NEVER, R),
        ItemRule("Razor Fang", NEVER, R),
        ItemRule("Sachet", NEVER, R),
        ItemRule("Whipped Dream", NEVER, R),
    )

    val byName: Map<String, ItemRule> = DEFAULT_RULES.associateBy { it.name }

    fun norm(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]"), "")
}
