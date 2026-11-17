package com.tvoshome.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * On-device OCR (Tesseract, small "fast" models downloaded once). Used only when the text is not
 * available as real text elements (e.g. subtitles burned into a TV channel or video).
 */
object OcrEngine {
    private var api: Any? = null
    private var loaded = ""
    @Volatile var status = ""
    @Volatile var lastUse = 0L

    private val cls: Class<*>? by lazy {
        try { Class.forName("com.googlecode.tesseract.android.TessBaseAPI") } catch (e: Throwable) { null }
    }

    /** True when the OCR library was included in this build. */
    val available: Boolean get() = cls != null

    /** tess-two (Tesseract 3) needs the old traineddata files; Tesseract4Android uses the small "fast" ones. */
    private val legacy: Boolean by lazy { cls?.methods?.none { it.name == "recycle" } ?: false }

    private fun m(name: String, vararg p: Class<*>) = cls!!.getMethod(name, *p)

    fun langs(idx: Int): List<String> =
        if (idx == 1) listOf("eng", "heb", "ara", "rus", "fra", "deu", "spa", "ita", "tur") else listOf("eng", "heb", "ara", "rus")

    private fun root(ctx: Context) = File(ctx.filesDir, "ocr")

    private fun dataUrl(lang: String): String =
        if (legacy) "https://github.com/tesseract-ocr/tessdata/raw/3.04.00/" + lang + ".traineddata"
        else "https://github.com/tesseract-ocr/tessdata_fast/raw/main/" + lang + ".traineddata"

    private fun download(url: String, dest: File): Boolean {
        val tmp = File(dest.parentFile, dest.name + ".part")
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10000
            c.readTimeout = 30000
            c.instanceFollowRedirects = true
            if (c.responseCode !in 200..299) { c.disconnect(); return false }
            c.inputStream.use { ins -> tmp.outputStream().use { out -> ins.copyTo(out) } }
            c.disconnect()
            if (tmp.length() < 50000L) { tmp.delete(); return false }
            tmp.renameTo(dest)
        } catch (e: Exception) {
            tmp.delete()
            false
        }
    }

    /** Downloads any missing language file. True when everything is on the device. */
    fun ensure(ctx: Context, list: List<String>): Boolean {
        if (!available) { status = "OCR library not included in this build"; return false }
        val dir = File(root(ctx), "tessdata")
        dir.mkdirs()
        var ok = true
        for (l in list) {
            val f = File(dir, l + ".traineddata")
            if (f.exists() && f.length() > 50000L) continue
            status = "downloading " + l
            if (!download(dataUrl(l), f)) {
                ok = false
                status = "download failed: " + l
            }
        }
        if (ok) status = "ready" + (if (legacy) " (legacy engine)" else "")
        return ok
    }

    @Synchronized
    private fun get(ctx: Context, list: List<String>): Any? {
        val key = list.joinToString("+")
        val a = api
        if (a != null && loaded == key) return a
        release()
        if (!ensure(ctx, list)) return null
        return try {
            val t = cls!!.getDeclaredConstructor().newInstance()
            val ok = m("init", String::class.java, String::class.java).invoke(t, root(ctx).absolutePath, key) as? Boolean ?: false
            if (!ok) {
                try { m(if (legacy) "end" else "recycle").invoke(t) } catch (e: Throwable) { }
                status = "init failed"
                null
            } else {
                try { m("setPageSegMode", Integer.TYPE).invoke(t, 6) } catch (e: Throwable) { }
                api = t
                loaded = key
                t
            }
        } catch (e: Throwable) {
            status = "engine error: " + e.javaClass.simpleName
            null
        }
    }

    @Synchronized
    fun release() {
        val a = api
        if (a != null) try { m(if (legacy) "end" else "recycle").invoke(a) } catch (e: Throwable) { }
        api = null
        loaded = ""
    }

    /** (text, mean confidence 0..100) or null when the engine is unavailable. */
    @Synchronized
    fun recognize(ctx: Context, list: List<String>, bmp: Bitmap): Pair<String, Int>? {
        val t = get(ctx, list) ?: return null
        lastUse = System.currentTimeMillis()
        return try {
            m("setImage", Bitmap::class.java).invoke(t, bmp)
            val txt = m("getUTF8Text").invoke(t) as? String ?: ""
            val conf = m("meanConfidence").invoke(t) as? Int ?: 0
            m("clear").invoke(t)
            Pair(txt.trim(), conf)
        } catch (e: Throwable) {
            status = "ocr error: " + e.javaClass.simpleName
            null
        }
    }
}

/** Cheap image helpers for the OCR path. */
object OcrPrep {
    private fun luma(p: Int): Int = (((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 151 + (p and 0xFF) * 28) shr 8

    fun clampRect(f: Bitmap, r: Rect): Rect {
        val x0 = r.left.coerceIn(0, f.width - 1)
        val y0 = r.top.coerceIn(0, f.height - 1)
        val x1 = r.right.coerceIn(x0 + 1, f.width)
        val y1 = r.bottom.coerceIn(y0 + 1, f.height)
        return Rect(x0, y0, x1, y1)
    }

    /** 24x6 grid of brightness samples: used to skip OCR when the picture in the box did not change. */
    fun sig(f: Bitmap, r0: Rect): IntArray {
        val r = clampRect(f, r0)
        val out = IntArray(24 * 6)
        for (gy in 0 until 6) for (gx in 0 until 24) {
            val x = r.left + (r.width() * (gx * 2 + 1)) / 48
            val y = r.top + (r.height() * (gy * 2 + 1)) / 12
            out[gy * 24 + gx] = luma(f.getPixel(x.coerceIn(0, f.width - 1), y.coerceIn(0, f.height - 1)))
        }
        return out
    }

    fun diff(a: IntArray, b: IntArray): Int {
        var s = 0
        for (i in a.indices) s += Math.abs(a[i] - b[i])
        return s / a.size
    }

    fun avgLuma(f: Bitmap, r: Rect): Int {
        val g = sig(f, r)
        var s = 0
        for (v in g) s += v
        return s / g.size
    }

    /**
     * Crops the box, scales it to a sensible size and keeps only the text colour
     * (mode 0: bright text such as subtitles, mode 1: dark text) as black on white.
     * Returns null when the box does not contain a plausible amount of "text pixels".
     */
    fun prepare(frame: Bitmap, r0: Rect, mode: Int): Bitmap? {
        val r = clampRect(frame, r0)
        val w = r.width()
        val h = r.height()
        if (w < 20 || h < 12) return null
        val crop = Bitmap.createBitmap(frame, r.left, r.top, w, h)
        val scale = if (w > 1000) 1000f / w else if (h < 60) 2f else 1f
        val sw = maxOf(8, (w * scale).toInt())
        val sh = maxOf(8, (h * scale).toInt())
        val sc = if (scale != 1f) Bitmap.createScaledBitmap(crop, sw, sh, true) else crop
        val px = IntArray(sw * sh)
        sc.getPixels(px, 0, sw, 0, 0, sw, sh)
        if (sc !== crop) sc.recycle()
        crop.recycle()
        var cnt = 0
        for (i in px.indices) {
            val l = luma(px[i])
            val t = if (mode == 0) l >= 180 else l <= 70
            if (t) { px[i] = 0xFF000000.toInt(); cnt++ } else px[i] = 0xFFFFFFFF.toInt()
        }
        val frac = cnt.toFloat() / px.size
        if (frac < 0.004f || frac > 0.35f) return null
        val pad = 8
        val out = Bitmap.createBitmap(sw + pad * 2, sh + pad * 2, Bitmap.Config.ARGB_8888)
        out.eraseColor(0xFFFFFFFF.toInt())
        out.setPixels(px, 0, sw, pad, pad, sw, sh)
        return out
    }

    /** Drops lines that are mostly noise. */
    fun clean(raw: String): String {
        val sb = StringBuilder()
        for (line in raw.split('\n')) {
            val t = line.trim()
            if (t.length < 2) continue
            var letters = 0
            for (ch in t) if (ch.isLetter()) letters++
            if (letters < 2 || letters * 10 < t.length * 5) continue
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(t)
        }
        return sb.toString()
    }
}
