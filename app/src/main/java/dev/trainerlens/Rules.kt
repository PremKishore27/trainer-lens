package dev.trainerlens

import java.text.Normalizer

/** keep = how many to keep. Int.MAX_VALUE = never discard. Order in DEFAULT_RULES = discard-first order. */
data class ItemRule(val name: String, val keep: Int)

object Rules {
    const val NEVER = Int.MAX_VALUE

    // Edit freely. Earlier entries are suggested for discarding first.
    val DEFAULT_RULES = listOf(
        ItemRule("Potion", 0),
        ItemRule("Super Potion", 0),
        ItemRule("Poke Ball", 50),
        ItemRule("Great Ball", 50),
        ItemRule("Nanab Berry", 20),
        ItemRule("Razz Berry", 50),
        ItemRule("Pinap Berry", 30),
        ItemRule("Silver Pinap Berry", 30),
        ItemRule("Hyper Potion", 20),
        ItemRule("Revive", 30),
        ItemRule("Ultra Ball", 150),
        ItemRule("Max Potion", 50),
        ItemRule("Max Revive", 50),
        // Protected: never suggested
        ItemRule("Golden Razz Berry", NEVER),
        ItemRule("Rare Candy", NEVER),
        ItemRule("Rare Candy XL", NEVER),
        ItemRule("Master Ball", NEVER),
        ItemRule("Lucky Egg", NEVER),
        ItemRule("Star Piece", NEVER),
        ItemRule("Gift", NEVER), // send to friends instead
        ItemRule("Incense", NEVER),
        ItemRule("Daily Adventure Incense", NEVER),
        ItemRule("Lure Module", NEVER),
        ItemRule("Glacial Lure Module", NEVER),
        ItemRule("Mossy Lure Module", NEVER),
        ItemRule("Magnetic Lure Module", NEVER),
        ItemRule("Rainy Lure Module", NEVER),
        ItemRule("Golden Lure Module", NEVER),
        ItemRule("Raid Pass", NEVER),
        ItemRule("Premium Battle Pass", NEVER),
        ItemRule("Remote Raid Pass", NEVER),
        ItemRule("Egg Incubator", NEVER),
        ItemRule("Super Incubator", NEVER),
        ItemRule("Camera", NEVER),
        ItemRule("Poffin", NEVER),
        ItemRule("Sun Stone", NEVER),
        ItemRule("King's Rock", NEVER),
        ItemRule("Metal Coat", NEVER),
        ItemRule("Dragon Scale", NEVER),
        ItemRule("Up-Grade", NEVER),
        ItemRule("Sinnoh Stone", NEVER),
        ItemRule("Unova Stone", NEVER),
        ItemRule("Fast TM", NEVER),
        ItemRule("Charged TM", NEVER),
        ItemRule("Elite Fast TM", NEVER),
        ItemRule("Elite Charged TM", NEVER),
    )

    fun norm(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]"), "")
}
