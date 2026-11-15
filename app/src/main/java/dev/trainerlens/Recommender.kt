package dev.trainerlens

data class Discard(val item: String, val amount: Int, val rarity: Rarity, val belowKeep: Boolean = false)
data class Plan(val discards: List<Discard>, val freed: Int, val needed: Int)

/** Per-item advice for everything seen so far, regardless of how full the bag is. */
data class Review(val remove: List<Discard>, val keep: List<String>)

object Recommender {
    fun review(counts: Map<String, Int>, rules: List<ItemRule> = Rules.DEFAULT_RULES): Review {
        val remove = mutableListOf<Discard>()
        val keep = mutableListOf<String>()
        for (r in rules) {
            val have = counts[r.name] ?: continue
            val surplus = if (r.keep == Rules.NEVER) 0 else have - r.keep
            if (surplus > 0) remove += Discard(r.name, surplus, r.rarity) else keep += "${r.name} ×$have"
        }
        return Review(remove, keep)
    }

    /**
     * What to discard to get [targetFree] empty slots. Works from the most common item to the least common:
     *  1. surplus above each keep limit (common items first, then uncommon);
     *  2. if that's not enough, common items down to half their keep limit (marked belowKeep).
     * Rare items are never suggested.
     */
    fun plan(
        counts: Map<String, Int>,
        used: Int,
        capacity: Int,
        targetFree: Int,
        rules: List<ItemRule> = Rules.DEFAULT_RULES,
    ): Plan {
        val needed = (targetFree - (capacity - used)).coerceAtLeast(0)
        val out = mutableListOf<Discard>()
        var freed = 0
        val left = counts.toMutableMap()
        for (r in rules) {
            if (freed >= needed) break
            if (r.keep == Rules.NEVER) continue
            val have = left[r.name] ?: continue
            val take = minOf(have - r.keep, needed - freed)
            if (take <= 0) continue
            out += Discard(r.name, take, r.rarity); freed += take; left[r.name] = have - take
        }
        for (r in rules) {
            if (freed >= needed) break
            if (r.rarity != Rarity.COMMON) continue
            val have = left[r.name] ?: continue
            val take = minOf(have - r.keep / 2, needed - freed)
            if (take <= 0) continue
            out += Discard(r.name, take, r.rarity, belowKeep = true); freed += take
        }
        return Plan(out, freed, needed)
    }
}
