package net.darkrelay.deaddropfinance.scan

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Cleans a receipt photo before text recognition, on plain pixel arrays (no Android, so it's tested on the JVM):
 * grayscale, crop to the receipt (the bright paper against a darker background), straighten (the angle where the text
 * rows line up best), and stretch the contrast. Kept outside the data and domain packages, which never use floating
 * point (money is cents there); this is pixels.
 */
object ImageCleanup {

    /** An 8-bit grayscale image, row by row. */
    class Gray(val width: Int, val height: Int, val px: IntArray) {
        operator fun get(x: Int, y: Int) = px[y * width + x]
    }

    data class Result(val image: Gray, val angleDegrees: Double, val cropped: Boolean)

    fun fromArgb(width: Int, height: Int, argb: IntArray): Gray = Gray(width, height, IntArray(argb.size) { i ->
        val c = argb[i]
        ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
    })

    fun toArgb(g: Gray): IntArray = IntArray(g.px.size) { i -> val v = g.px[i]; (0xFF shl 24) or (v shl 16) or (v shl 8) or v }

    fun clean(g: Gray): Result {
        val cropped = crop(g)
        val angle = skewAngle(cropped ?: g)
        // the text slopes by [angle]; sampling the photo rotated by that much lays the lines flat
        val straight = if (abs(angle) >= 0.25) rotate(cropped ?: g, angle) else (cropped ?: g)
        return Result(contrast(straight), angle, cropped != null)
    }

    /** Otsu's threshold: the gray level that best splits dark from light. */
    fun otsu(g: Gray): Int {
        val hist = IntArray(256)
        for (v in g.px) hist[v]++
        val total = g.px.size.toLong()
        var sum = 0L
        for (i in 0..255) sum += i.toLong() * hist[i]
        var sumB = 0L; var wB = 0L; var best = 0.0; var t = 127
        for (i in 0..255) {
            wB += hist[i]
            if (wB == 0L) continue
            val wF = total - wB
            if (wF == 0L) break
            sumB += i.toLong() * hist[i]
            val mB = sumB.toDouble() / wB
            val mF = (sum - sumB).toDouble() / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > best) { best = between; t = i }
        }
        return t
    }

    /**
     * The receipt's box: the longest run of rows, then columns, that are mostly bright. Null when that box is most of
     * the photo already (or too small to be a receipt), so nothing is cut.
     */
    fun crop(g: Gray): Gray? {
        val t = otsu(g)
        fun bright(x: Int, y: Int) = g[x, y] > t
        val rowFrac = IntArray(g.height) { y -> var n = 0; for (x in 0 until g.width) if (bright(x, y)) n++; n * 100 / g.width }
        val rows = longestRun(rowFrac, 35) ?: return null
        val colFrac = IntArray(g.width) { x -> var n = 0; for (y in rows.first..rows.last) if (bright(x, y)) n++; n * 100 / rows.count() }
        val cols = longestRun(colFrac, 50) ?: return null
        val w = cols.count(); val h = rows.count()
        if (w * h < g.width * g.height / 6) return null
        if (w * h > g.width * g.height * 9 / 10) return null
        val m = 4
        val x0 = maxOf(0, cols.first - m); val x1 = minOf(g.width - 1, cols.last + m)
        val y0 = maxOf(0, rows.first - m); val y1 = minOf(g.height - 1, rows.last + m)
        val nw = x1 - x0 + 1; val nh = y1 - y0 + 1
        return Gray(nw, nh, IntArray(nw * nh) { i -> g[x0 + i % nw, y0 + i / nw] })
    }

    private fun longestRun(frac: IntArray, min: Int): IntRange? {
        var best: IntRange? = null; var start = -1
        for (i in 0..frac.size) {
            val on = i < frac.size && frac[i] >= min
            if (on && start < 0) start = i
            if (!on && start >= 0) { if (best == null || i - start > best.count()) best = start until i; start = -1 }
        }
        return best?.takeIf { it.count() >= 8 }
    }

    /**
     * The tilt of the text, in degrees (-10 to 10): the angle at which the dark pixels' rows are most uneven, which is
     * when text lines run straight across. Worked out on a small copy.
     */
    fun skewAngle(g: Gray): Double {
        val scale = maxOf(1, maxOf(g.width, g.height) / 700)
        val t = otsu(g)
        val pts = ArrayList<Int>()
        var y = 0
        while (y < g.height) {
            var x = 0
            while (x < g.width) { if (g[x, y] <= t) { pts += x / scale; pts += y / scale }; x += scale }
            y += scale
        }
        if (pts.size < 40) return 0.0
        val h = g.height / scale + g.width / scale + 2
        var bestA = 0.0; var bestScore = -1.0
        var a = -10.0
        while (a <= 10.0001) {
            val r = Math.toRadians(a)
            val s = sin(r); val c = cos(r)
            val bins = LongArray(2 * h + 1)
            var i = 0
            while (i < pts.size) {
                val yy = (pts[i + 1] * c - pts[i] * s).roundToInt() + h
                if (yy in bins.indices) bins[yy]++
                i += 2
            }
            var score = 0.0
            for (b in bins) score += b.toDouble() * b
            if (score > bestScore + 1e-9) { bestScore = score; bestA = a }
            a += 0.5
        }
        return bestA
    }

    /**
     * Each output pixel samples the photo at its position turned by [degrees] about the center (bilinear), so text
     * sloping down to the right by [degrees] comes out level. Keeps the size; the corners fill white.
     */
    fun rotate(g: Gray, degrees: Double): Gray {
        val r = Math.toRadians(degrees)
        val s = sin(r); val c = cos(r)
        val cx = g.width / 2.0; val cy = g.height / 2.0
        val out = IntArray(g.px.size)
        for (y in 0 until g.height) for (x in 0 until g.width) {
            val dx = x - cx; val dy = y - cy
            val fx = dx * c - dy * s + cx
            val fy = dx * s + dy * c + cy
            val x0 = kotlin.math.floor(fx).toInt(); val y0 = kotlin.math.floor(fy).toInt()
            out[y * g.width + x] = if (x0 < 0 || y0 < 0 || x0 + 1 >= g.width || y0 + 1 >= g.height) 255 else {
                val ax = fx - x0; val ay = fy - y0
                val top = g[x0, y0] * (1 - ax) + g[x0 + 1, y0] * ax
                val bottom = g[x0, y0 + 1] * (1 - ax) + g[x0 + 1, y0 + 1] * ax
                (top * (1 - ay) + bottom * ay).roundToInt()
            }
        }
        return Gray(g.width, g.height, out)
    }

    /** Stretches the gray levels so the darkest 2% go black and the lightest 2% go white. */
    fun contrast(g: Gray): Gray {
        val hist = IntArray(256)
        for (v in g.px) hist[v]++
        val cut = g.px.size / 50
        var lo = 0; var acc = 0
        while (lo < 255 && acc + hist[lo] <= cut) { acc += hist[lo]; lo++ }
        var hi = 255; acc = 0
        while (hi > 0 && acc + hist[hi] <= cut) { acc += hist[hi]; hi-- }
        if (hi - lo < 16) return g
        return Gray(g.width, g.height, IntArray(g.px.size) { i -> ((g.px[i] - lo) * 255 / (hi - lo)).coerceIn(0, 255) })
    }

    /** A smaller copy whose longer side is at most [max] pixels (for storing and showing). */
    fun scaleDown(g: Gray, max: Int): Gray {
        val longSide = maxOf(g.width, g.height)
        if (longSide <= max) return g
        val w = g.width * max / longSide; val h = g.height * max / longSide
        return Gray(w, h, IntArray(w * h) { i -> g[(i % w) * g.width / w, (i / w) * g.height / h] })
    }
}
