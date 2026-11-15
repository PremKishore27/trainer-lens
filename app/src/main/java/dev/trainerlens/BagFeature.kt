package dev.trainerlens

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Feature 1: bag/item storage advisor. Accumulates counts while the Items list is scrolled. */
class BagFeature(ctx: Context) {
    private val seen = mutableMapOf<String, Int>()
    private var used: Int? = null
    private var capacity: Int? = null
    private var targetFree = 30
    private val output = TextView(ctx).apply { setTextColor(Color.WHITE); text = "Open the Items screen and scroll the list." }

    val panel: View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(ctx).apply { text = "Bag"; setTextColor(Color.LTGRAY) })
        addView(output)
        addView(TextView(ctx).apply { text = "Suggestions only – check before discarding."; setTextColor(Color.GRAY); textSize = 11f })
        addView(Button(ctx).apply { text = "Free more (+10)"; setOnClickListener { targetFree += 10; refresh() } })
        addView(Button(ctx).apply { text = "Reset"; setOnClickListener { seen.clear(); used = null; capacity = null; refresh() } })
    }

    fun onTokens(tokens: List<Token>) {
        val p = ItemParser.parse(tokens)
        if (p.counts.isEmpty() && p.capacity == null) return
        seen.putAll(p.counts)
        if (p.used != null) { used = p.used; capacity = p.capacity }
        refresh()
    }

    private fun refresh() {
        val u = used; val c = capacity
        if (u == null || c == null) {
            output.text = "Open the Items screen so I can read the bag fill (e.g. 342/350).\nItems seen: ${seen.size}"
            return
        }
        val plan = Recommender.plan(seen, u, c, targetFree)
        val sb = StringBuilder("Bag $u/$c  •  items seen: ${seen.size}\n")
        if (plan.needed > 0) {
            sb.append("To free ${plan.needed} slots:\n")
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
