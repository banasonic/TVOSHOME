package com.tvoshome.app

import android.content.Context
import android.graphics.BitmapFactory

/** Result of a match, in coordinates of the (scaled) region of interest. */
class LogoMatch(val score: Float, val tpl: Int, val x: Int, val y: Int, val w: Int, val h: Int)

/**
 * Finds one of the 12 known channel logos inside the top-right region of a frame.
 *
 * The logos are white shapes, so each frame region is turned into a "bright and brighter than its
 * surroundings" map; every logo template is then compared at several sizes using a small set of
 * sample points (points that must be bright + points that must stay dark). The scan is only run
 * for a few frames after a channel change, never continuously.
 */
object LogoDetector {
    const val RW = 400                 // width the region of interest is scaled to
    private const val COUNT = 12
    private const val SAMPLES = 250
    private const val MIN_SAMPLES = 60

    /** Cover rectangle growth beyond the matched logo (left, top, right, bottom as fractions of the logo size). */
    private val EXT = arrayOf(
        floatArrayOf(0f, 0f, 0f, 0f),      // 1  ONE
        floatArrayOf(0f, 0f, 0f, 0f),      // 2  ONE 2
        floatArrayOf(0f, 0f, 0f, 0f),      // 3  5 GOLD
        floatArrayOf(0f, 0f, 0f, 0f),      // 4
        floatArrayOf(0f, 0f, 0f, 0f),      // 5  5 LIVE
        floatArrayOf(0f, 0f, 0f, 0.12f),   // 6  5 + red "live" plate below
        floatArrayOf(0f, 0f, 0f, 0.12f),   // 7  5 + red "live" plate below
        floatArrayOf(0f, 0f, 0f, 0f),      // 8  5 STARS
        floatArrayOf(0.75f, 0.1f, 0f, 0.1f), // 9  Sport 1 + red plate on the left
        floatArrayOf(0.75f, 0.1f, 0f, 0.1f), // 10 Sport 2 + red plate on the left
        floatArrayOf(0f, 0f, 0f, 0f),      // 11 Sport 3
        floatArrayOf(0f, 0f, 0f, 0f)       // 12 Sport 4
    )

    private class Tpl(val w: Int, val h: Int, val a: FloatArray)
    private class SS(val th: Int, val on: IntArray, val off: IntArray, val quick: IntArray)

    private var tpls: Array<Tpl?>? = null
    private val cache = HashMap<Int, SS?>()
    private var scales: IntArray? = null

    @Synchronized
    private fun load(ctx: Context): Array<Tpl?> {
        tpls?.let { return it }
        val out = arrayOfNulls<Tpl>(COUNT)
        for (i in 0 until COUNT) {
            try {
                val name = "logo_tpl/t" + (if (i + 1 < 10) "0" else "") + (i + 1) + ".png"
                val bmp = ctx.assets.open(name).use { BitmapFactory.decodeStream(it) } ?: continue
                val w = bmp.width
                val h = bmp.height
                val px = IntArray(w * h)
                bmp.getPixels(px, 0, w, 0, 0, w, h)
                bmp.recycle()
                val a = FloatArray(w * h)
                for (k in px.indices) a[k] = ((px[k] shr 16) and 0xFF) / 255f
                out[i] = Tpl(w, h, a)
            } catch (e: Exception) {
            }
        }
        tpls = out
        return out
    }

    private fun scaleList(): IntArray {
        scales?.let { return it }
        val l = ArrayList<Int>()
        var tw = 30
        while (tw <= (RW * 0.6f).toInt()) {
            l.add(tw)
            tw = (tw * 1.12f).toInt() + 1
        }
        val r = l.toIntArray()
        scales = r
        return r
    }

    private fun pick(src: ArrayList<Int>): IntArray {
        if (src.size <= SAMPLES) return src.toIntArray()
        val out = IntArray(SAMPLES)
        for (i in 0 until SAMPLES) out[i] = src[(i.toLong() * (src.size - 1) / (SAMPLES - 1)).toInt()]
        return out
    }

    private fun sampleSet(t: Tpl, ti: Int, tw: Int): SS? {
        val key = ti * 10000 + tw
        if (cache.containsKey(key)) return cache[key]
        val th = maxOf(6, Math.round(tw.toFloat() * t.h / t.w))
        val on = ArrayList<Int>()
        val off = ArrayList<Int>()
        for (y in 0 until th) {
            val sy0 = y * t.h / th
            val sy1 = maxOf(sy0 + 1, (y + 1) * t.h / th)
            for (x in 0 until tw) {
                val sx0 = x * t.w / tw
                val sx1 = maxOf(sx0 + 1, (x + 1) * t.w / tw)
                var sum = 0f
                var n = 0
                for (yy in sy0 until minOf(sy1, t.h)) for (xx in sx0 until minOf(sx1, t.w)) {
                    sum += t.a[yy * t.w + xx]
                    n++
                }
                val v = if (n > 0) sum / n else 0f
                if (v > 0.5f) on.add(y * RW + x) else if (v < 0.12f) off.add(y * RW + x)
            }
        }
        val res: SS? = if (on.size < MIN_SAMPLES || off.size < MIN_SAMPLES) null else {
            val o = pick(on)
            val q = IntArray(32)
            for (i in 0 until 32) q[i] = o[(i.toLong() * (o.size - 1) / 31).toInt()]
            SS(th, o, pick(off), q)
        }
        cache[key] = res
        return res
    }

    /** "Bright and brighter than the neighbourhood" map (0..1), lightly blurred. */
    fun features(px: IntArray, w: Int, h: Int): FloatArray {
        val g = IntArray(w * h)
        for (i in 0 until w * h) {
            val p = px[i]
            g[i] = (((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 151 + (p and 0xFF) * 28) shr 8
        }
        val iw = w + 1
        val integ = IntArray(iw * (h + 1))
        for (y in 0 until h) {
            var row = 0
            for (x in 0 until w) {
                row += g[y * w + x]
                integ[(y + 1) * iw + x + 1] = integ[y * iw + x + 1] + row
            }
        }
        val r = 6
        val f = FloatArray(w * h)
        for (y in 0 until h) {
            val y0 = maxOf(0, y - r)
            val y1 = minOf(h, y + r + 1)
            for (x in 0 until w) {
                val x0 = maxOf(0, x - r)
                val x1 = minOf(w, x + r + 1)
                val s = integ[y1 * iw + x1] - integ[y0 * iw + x1] - integ[y1 * iw + x0] + integ[y0 * iw + x0]
                val mean = s.toFloat() / ((x1 - x0) * (y1 - y0))
                val v = g[y * w + x]
                val c = v - mean
                f[y * w + x] = if (v > 135 && c > 0f) (if (c > 40f) 1f else c / 40f) else 0f
            }
        }
        return blur3(blur3(f, w, h), w, h)
    }

    private fun blur3(s: FloatArray, w: Int, h: Int): FloatArray {
        val o = FloatArray(w * h)
        for (y in 0 until h) {
            val ya = maxOf(0, y - 1)
            val yb = minOf(h - 1, y + 1)
            for (x in 0 until w) {
                val xa = maxOf(0, x - 1)
                val xb = minOf(w - 1, x + 1)
                o[y * w + x] = (s[ya * w + xa] + s[ya * w + x] + s[ya * w + xb] +
                    s[y * w + xa] + s[y * w + x] + s[y * w + xb] +
                    s[yb * w + xa] + s[yb * w + x] + s[yb * w + xb]) / 9f
            }
        }
        return o
    }

    /** Best match (any logo, any size) in the feature map, or null. */
    fun detect(ctx: Context, f: FloatArray, w: Int, h: Int): LogoMatch? {
        val ts = load(ctx)
        val sc = scaleList()
        var best: LogoMatch? = null
        for (ti in 0 until COUNT) {
            val t = ts[ti] ?: continue
            for (tw in sc) {
                if (tw >= w - 2) continue
                val s = sampleSet(t, ti, tw) ?: continue
                if (s.th >= h - 2) continue
                val on = s.on
                val off = s.off
                val q = s.quick
                var y = 0
                while (y <= h - s.th - 1) {
                    var x = 0
                    while (x <= w - tw - 1) {
                        val base = y * w + x
                        var e = 0f
                        for (k in q.indices) e += f[base + q[k]]
                        if (e >= 0.3f * q.size) {
                            var a = 0f
                            for (k in on.indices) a += f[base + on[k]]
                            var b = 0f
                            for (k in off.indices) b += f[base + off[k]]
                            val score = a / on.size - 0.8f * b / off.size
                            if (best == null || score > best.score) best = LogoMatch(score, ti, x, y, tw, s.th)
                        }
                        x += 2
                    }
                    y += 2
                }
            }
        }
        return best
    }

    /** Cover rectangle (left, top, right, bottom) for a match, in the matched logo's own units. */
    fun extend(m: LogoMatch): FloatArray {
        val e = EXT[m.tpl]
        return floatArrayOf(
            m.x - e[0] * m.w,
            m.y - e[1] * m.h,
            m.x + m.w + e[2] * m.w,
            m.y + m.h + e[3] * m.h
        )
    }
}
