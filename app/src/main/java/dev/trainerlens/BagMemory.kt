package dev.trainerlens

/**
 * What the bag advisor has learned about the item list so far, as a pure class (no Android) so it can be tested.
 *
 * Every frame gives item -> count. A reading is *confirmed* when it was seen on two frames in a row (the list wasn't
 * moving) or on two separate frames. A reading seen only once is kept too, so a quickly scrolled item still shows up in
 * "remembered", but it is marked unconfirmed and kept out of the discard advice until it is seen again.
 */
class BagMemory {
    private val stable = HashMap<String, HashMap<Int, Int>>()
    private val sightings = HashMap<String, HashMap<Int, Int>>()
    private val latest = HashMap<String, Int>()
    private var last: Map<String, Int> = emptyMap()

    fun observe(frame: Map<String, Int>) {
        for ((item, n) in frame) {
            sightings.getOrPut(item) { HashMap() }.merge(n, 1, Int::plus)
            latest[item] = n
            if (last[item] == n) stable.getOrPut(item) { HashMap() }.merge(n, 1, Int::plus)
        }
        last = frame
    }

    private fun score(item: String, n: Int) = 3 * (stable[item]?.get(n) ?: 0) + (sightings[item]?.get(n) ?: 0)

    /** The best reading of every item seen so far: stable readings count triple, ties go to the newest reading. */
    fun counts(): Map<String, Int> = sightings.mapValues { (item, v) ->
        v.keys.maxWith(compareBy<Int>({ score(item, it) }, { if (it == latest[item]) 1 else 0 }))
    }

    private fun isConfirmed(item: String, n: Int) = (stable[item]?.get(n) ?: 0) > 0 || (sightings[item]?.get(n) ?: 0) >= 2

    /** Items whose best reading was seen on two frames: safe to base advice on. */
    fun confirmed(): Map<String, Int> = counts().filter { (item, n) -> isConfirmed(item, n) }

    /** Items seen only once so far. */
    fun unconfirmed(): Map<String, Int> = counts().filter { (item, n) -> !isConfirmed(item, n) }

    val size: Int get() = sightings.size

    fun clear() { stable.clear(); sightings.clear(); latest.clear(); last = emptyMap() }
}
