package dev.trainerlens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Level arc ("CP arc") reader, the idea Poké Genie and GoIV use.
 *
 * On a Pokémon's page a white dot sits on a half-circle above the Pokémon. Its position along the arc is
 *     fraction = (CPM(level) − CPM(1)) / (CPM(maxLevel) − CPM(1))
 * where maxLevel is the highest level *you* can power that Pokémon to (trainer level + 10, at most 50).
 *
 * Every phone draws the arc a little differently, so nothing is hard-coded – the reader calibrates itself:
 *  1. Geometry: the dot positions seen on different Pokémon all lie on one circle centred horizontally on the screen;
 *     a robust circle fit (RANSAC) finds its centre height and radius once ≥ 4 well-spread dots are collected.
 *  2. maxLevel: whenever an appraisal pins down a Pokémon's exact level, maxLevel is solved from the formula above.
 * Until both are calibrated the arc isn't used. Afterwards it's only a soft hint: if no IV spread fits at the arc's
 * level, the arc is ignored – so it can make a range narrower but never make a result wrong.
 */
class ArcReader(ctx: Context, private val w: Int, private val h: Int) {
    private val prefs = ctx.getSharedPreferences("arc_${w}x$h", Context.MODE_PRIVATE)
    private val points = ArrayList<Pair<Float, Float>>()
    private var cy = Float.NaN
    private var r = Float.NaN
    private var maxLevel: Double? = null

    init {
        prefs.getString("points", "")!!.split(";").filter { it.contains(",") }.forEach {
            val (x, y) = it.split(","); points += x.toFloat() to y.toFloat()
        }
        prefs.getFloat("maxLevel", 0f).takeIf { it > 0f }?.let { maxLevel = it.toDouble() }
        fit()
    }

    val calibrated: Boolean get() = !cy.isNaN() && maxLevel != null
    fun status(): String = when {
        calibrated -> "arc ✓"
        cy.isNaN() -> "arc: learning (${points.size} dots)"
        else -> "arc: appraise 1 Pokémon to finish"
    }

    /** White dot candidates in the upper half of the screen: (x, y) centres. */
    fun findDots(bmp: Bitmap): List<Pair<Float, Float>> {
        val step = maxOf(1, bmp.width / 360)
        val gw = bmp.width / step
        val y0 = (bmp.height * 0.05).toInt(); val y1 = (bmp.height * 0.5).toInt()
        val gh = (y1 - y0) / step
        val white = BooleanArray(gw * gh)
        val row = IntArray(bmp.width)
        for (gy in 0 until gh) {
            bmp.getPixels(row, 0, bmp.width, 0, y0 + gy * step, bmp.width, 1)
            for (gx in 0 until gw) {
                val c = row[gx * step]
                white[gy * gw + gx] = Color.red(c) >= 240 && Color.green(c) >= 240 && Color.blue(c) >= 240
            }
        }
        // Erode: keep only pixels whose whole k-neighbourhood is white. Removes the thin arc line and text strokes,
        // leaves the core of the filled dot.
        val k = maxOf(1, ((bmp.width * 0.007) / step).roundToInt())
        val core = BooleanArray(gw * gh)
        for (gy in k until gh - k) for (gx in k until gw - k) {
            var ok = true
            loop@ for (dy in -k..k) for (dx in -k..k) if (!white[(gy + dy) * gw + gx + dx]) { ok = false; break@loop }
            core[gy * gw + gx] = ok
        }
        // connected components of the eroded mask
        val seen = BooleanArray(gw * gh)
        val out = ArrayList<Pair<Float, Float>>()
        val stack = IntArray(gw * gh)
        for (i in core.indices) {
            if (!core[i] || seen[i]) continue
            var sp = 0; stack[sp++] = i; seen[i] = true
            var n = 0; var sx = 0L; var sy = 0L
            var minX = gw; var maxX = 0; var minY = gh; var maxY = 0
            while (sp > 0) {
                val p = stack[--sp]; val px = p % gw; val py = p / gw
                n++; sx += px; sy += py
                minX = minOf(minX, px); maxX = maxOf(maxX, px); minY = minOf(minY, py); maxY = maxOf(maxY, py)
                for ((qx, qy) in arrayOf(px + 1 to py, px - 1 to py, px to py + 1, px to py - 1)) {
                    if (qx < 0 || qy < 0 || qx >= gw || qy >= gh) continue
                    val q = qy * gw + qx
                    if (core[q] && !seen[q]) { seen[q] = true; stack[sp++] = q }
                }
            }
            val bw = (maxX - minX + 1) * step; val bh = (maxY - minY + 1) * step
            val maxSize = bmp.width * 0.04
            if (bw > maxSize || bh > maxSize) continue                    // Pokémon sprite, big white areas
            if (bw.toFloat() / bh !in 0.6f..1.67f) continue              // not round
            if (n.toFloat() / ((maxX - minX + 1) * (maxY - minY + 1)) < 0.5f) continue
            out += (sx.toFloat() / n * step + step / 2f) to (y0 + sy.toFloat() / n * step + step / 2f)
        }
        return out
    }

    /** Picks the dot for this frame: the candidate on the fitted arc, or the only candidate while learning. */
    fun pick(dots: List<Pair<Float, Float>>): Pair<Float, Float>? {
        if (!cy.isNaN()) return dots.filter { onArc(it) }.minByOrNull { abs(dist(it) - r) }
        return dots.singleOrNull()
    }

    /** Remembers a dot position for the geometry fit. */
    fun learn(dot: Pair<Float, Float>) {
        if (points.any { hypot((it.first - dot.first).toDouble(), (it.second - dot.second).toDouble()) < w * 0.01 }) return
        points += dot
        while (points.size > 40) points.removeAt(0)
        fit()
        prefs.edit().putString("points", points.joinToString(";") { "${it.first},${it.second}" }).apply()
    }

    /** 0 at the left end of the arc (level 1) … 1 at the right end (max level). */
    fun fraction(dot: Pair<Float, Float>): Double? {
        if (cy.isNaN() || !onArc(dot)) return null
        val theta = atan2((cy - dot.second).toDouble(), (dot.first - w / 2f).toDouble()) // π at left, 0 at right
        return ((PI - theta) / PI).coerceIn(0.0, 1.0)
    }

    /** Level the dot points at, or null while not calibrated. */
    fun level(dot: Pair<Float, Float>): Double? {
        val f = fraction(dot) ?: return null
        val mx = maxLevel ?: return null
        val target = Cpm.at(1.0) + f * (Cpm.at(mx) - Cpm.at(1.0))
        return Cpm.levels(mx).minBy { abs(Cpm.at(it) - target) }
    }

    /** An appraisal fixed this Pokémon's exact [level]: solve maxLevel from the arc formula. */
    fun calibrateMax(dot: Pair<Float, Float>, level: Double) {
        val f = fraction(dot) ?: return
        if (f < 0.15) return // too close to the start of the arc to be precise
        val target = Cpm.at(1.0) + (Cpm.at(level) - Cpm.at(1.0)) / f
        val mx = Cpm.levels(50.0).filter { it >= level }.minByOrNull { abs(Cpm.at(it) - target) } ?: return
        maxLevel = mx
        prefs.edit().putFloat("maxLevel", mx.toFloat()).apply()
    }

    private fun dist(p: Pair<Float, Float>) = hypot((p.first - w / 2f).toDouble(), (p.second - cy).toDouble())
    private fun onArc(p: Pair<Float, Float>) = !cy.isNaN() && abs(dist(p) - r) < w * 0.015 && p.second <= cy + w * 0.02

    /** RANSAC circle fit with the centre fixed at x = w/2: unknowns centre height cy and radius r. */
    private fun fit() {
        cy = Float.NaN; r = Float.NaN
        if (points.size < 4) return
        val cx = w / 2.0
        var bestIn: List<Pair<Float, Float>> = emptyList()
        for (i in points.indices) for (j in i + 1 until points.size) {
            val (c, rr) = solve(points[i], points[j], cx) ?: continue
            if (rr < w * 0.25 || rr > w * 0.55) continue
            val inl = points.filter { abs(hypot(it.first - cx, it.second - c) - rr) < w * 0.012 }
            if (inl.size > bestIn.size) bestIn = inl
        }
        if (bestIn.size < 4) return
        // angular spread so the fit isn't from one cluster
        val xs = bestIn.map { it.first }
        if (xs.max() - xs.min() < w * 0.3) return
        // least squares on the inliers: (x-cx)² + y² = 2·y·cy + (r² − cy²)
        var syy = 0.0; var sy = 0.0; var n = 0.0; var sby = 0.0; var sb = 0.0
        for ((x, y) in bestIn) {
            val b = (x - cx) * (x - cx) + y.toDouble() * y
            syy += 2.0 * y * 2.0 * y; sy += 2.0 * y; n += 1; sby += b * 2.0 * y; sb += b
        }
        val det = syy * n - sy * sy
        if (abs(det) < 1e-6) return
        val c = (sby * n - sy * sb) / det
        val k = (syy * sb - sy * sby) / det
        val rr = k + c * c
        if (rr <= 0) return
        cy = c.toFloat(); r = Math.sqrt(rr).toFloat()
    }

    private fun solve(p: Pair<Float, Float>, q: Pair<Float, Float>, cx: Double): Pair<Double, Double>? {
        // (px-cx)² + (py-c)² = (qx-cx)² + (qy-c)²  ->  c = (bq - bp) / (2(qy - py)), b = (x-cx)² + y²
        val dy = q.second - p.second
        if (abs(dy) < 1f) return null
        val bp = (p.first - cx) * (p.first - cx) + p.second.toDouble() * p.second
        val bq = (q.first - cx) * (q.first - cx) + q.second.toDouble() * q.second
        val c = (bq - bp) / (2.0 * dy)
        return c to hypot(p.first - cx, p.second - c)
    }
}
