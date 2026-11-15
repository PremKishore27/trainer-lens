package dev.trainerlens

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Feature 1: bag/item storage advisor.
 * Remembers every item it reads during the session (until Reset or Stop), so you can scroll the whole Items list once.
 * A reading only counts when the same item + count is seen on two frames in a row (the list isn't moving), and the
 * count shown is the one read most often – this filters out misreads while scrolling.
 */
class BagFeature(ctx: Context) {
    /** item -> (count -> how many stable frames showed it) */
    private val votes = mutableMapOf<String, MutableMap<Int, Int>>()
    private var lastFrame: Map<String, Int> = emptyMap()
    private var used: Int? = null
    private var capacity: Int? = null
    private var targetFree = 30
    private val output = TextView(ctx).apply { setTextColor(Color.WHITE); text = "Open the Items screen and scroll the list slowly." }

    val panel: View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        addView(output)
        addView(TextView(ctx).apply { text = "Suggestions only – check before discarding."; setTextColor(Color.GRAY); textSize = 11f })
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(ctx).apply { text = "Free more +10"; setOnClickListener { targetFree += 10; refresh() } })
            addView(Button(ctx).apply { text = "Reset"; setOnClickListener { reset() } })
        })
    }

    private fun reset() { votes.clear(); lastFrame = emptyMap(); used = null; capacity = null; targetFree = 30; refresh() }

    /** Best reading per item so far. */
    private fun counts(): Map<String, Int> = votes.mapValues { (_, v) -> v.maxBy { it.value }.key }

    fun onTokens(tokens: List<Token>, screenH: Int) {
        val p = ItemParser.parse(tokens, screenH)
        if (p.counts.isEmpty() && p.capacity == null) { lastFrame = emptyMap(); return }
        if (p.used != null) { used = p.used; capacity = p.capacity }
        // only trust pairs that were identical on the previous frame
        for ((item, n) in p.counts) if (lastFrame[item] == n) {
            val v = votes.getOrPut(item) { mutableMapOf() }
            v[n] = (v[n] ?: 0) + 1
        }
        lastFrame = p.counts
        refresh()
    }

    private fun refresh() {
        val u = used; val c = capacity
        val seen = counts()
        if (u == null || c == null) {
            output.text = "Open the Items screen so I can read the bag fill (e.g. 342/350).\nRemembered this session: ${seen.size} item types"
            return
        }
        val plan = Recommender.plan(seen, u, c, targetFree)
        val sb = StringBuilder("Bag $u/$c  •  remembered: ${seen.size} item types\n")
        if (plan.needed > 0) {
            sb.append("To get $targetFree free slots, free ${plan.needed}:\n")
            plan.discards.forEach { sb.append("• Discard ${it.amount} × ${it.item}\n") }
            if (plan.freed < plan.needed) sb.append("Scroll the whole list for a better plan (short by ${plan.needed - plan.freed}).\n")
            sb.append("\n")
        } else {
            sb.append("${c - u} free slots – no need to discard yet.\n\n")
        }
        val review = Recommender.review(seen)
        if (review.remove.isEmpty()) sb.append("Can remove: nothing above its keep limit.\n")
        else {
            sb.append("Can remove (over keep limit):\n")
            review.remove.forEach { sb.append("• ${it.amount} × ${it.item}\n") }
        }
        if (review.keep.isNotEmpty()) sb.append("Keep: ${review.keep.joinToString(", ")}")
        output.text = sb.toString().trimEnd()
    }
}
