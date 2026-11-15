package dev.trainerlens

data class Discard(val item: String, val amount: Int)
data class Plan(val discards: List<Discard>, val freed: Int, val needed: Int)

object Recommender {
    /**
     * @param counts item name (as in Rules) -> count currently seen
     * @param used / capacity: bag fill, e.g. 342/350
     * @param targetFree how many free slots the player wants afterwards
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
        for (r in rules) {
            if (freed >= needed) break
            if (r.keep == Rules.NEVER) continue
            val have = counts[r.name] ?: continue
            val surplus = have - r.keep
            if (surplus <= 0) continue
            val take = minOf(surplus, needed - freed)
            out += Discard(r.name, take)
            freed += take
        }
        return Plan(out, freed, needed)
    }
}
