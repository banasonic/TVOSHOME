package com.tvoshome.app

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

/** Free translation (Google's public web endpoint, no key) with a small cache and a kept-alive connection. */
object Translator {
    private val cache = object : LinkedHashMap<String, String>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 80
    }
    private var lastReq = 0L
    @Volatile var lastDetected = ""
    private const val UA = "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

    fun arabicRatio(t: String): Float {
        var ar = 0
        var all = 0
        for (ch in t) if (ch.isLetter()) { all++; if (ch.code in 0x0600..0x06FF) ar++ }
        return if (all == 0) 0f else ar.toFloat() / all
    }

    /** GET that leaves the connection open for reuse (a new TLS handshake on every request costs hundreds of ms). */
    private fun fetch(url: String): String? {
        return try {
            val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 6000
            c.readTimeout = 8000
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Connection", "keep-alive")
            if (c.responseCode !in 200..299) {
                try { c.errorStream?.close() } catch (e: Exception) { }
                return null
            }
            c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) { null }
    }

    /** Keeps the connection warm while the feature is on (called now and then). */
    fun ping() {
        fetch("https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=ar&dt=t&q=ok")
    }

    /** Arabic text, "" if the text is already Arabic, null on failure. */
    fun toArabic(text: String, sl: String? = null): String? {
        synchronized(cache) { val c = cache[text]; if (c != null) return c }
        if (arabicRatio(text) > 0.6f) return ""
        val gap = 100L - (SystemClock.elapsedRealtime() - lastReq)
        if (gap > 0) try { Thread.sleep(gap) } catch (e: Exception) { }
        lastReq = SystemClock.elapsedRealtime()
        val body = fetch("https://translate.googleapis.com/translate_a/single?client=gtx&sl=" + (sl ?: "auto") + "&tl=ar&dt=t&q=" +
            URLEncoder.encode(text, "UTF-8"))
        if (body == null) return alt(text)
        return try {
            val arr = JSONArray(body)
            val segs = arr.getJSONArray(0)
            val sb = StringBuilder()
            for (i in 0 until segs.length()) {
                val sg = segs.optJSONArray(i) ?: continue
                sb.append(sg.optString(0, ""))
            }
            lastDetected = arr.optString(2, "")
            val out = if (arr.optString(2, "") == "ar") "" else sb.toString().trim()
            synchronized(cache) { cache[text] = out }
            out
        } catch (e: Exception) { alt(text) }
    }

    /** Second free endpoint, used only when the first one fails. */
    private fun alt(text: String): String? {
        val body = fetch("https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=auto&tl=ar&q=" +
            URLEncoder.encode(text, "UTF-8")) ?: return null
        return try {
            val a = JSONArray(body)
            val e0 = a.opt(0)
            var t = ""
            var src = ""
            if (e0 is JSONArray) { t = e0.optString(0, ""); src = e0.optString(1, "") } else if (e0 is String) t = e0
            val out = if (src == "ar") "" else t.trim()
            if (out.isNotEmpty() || src == "ar") synchronized(cache) { cache[text] = out }
            if (out.isEmpty() && src != "ar") null else out
        } catch (e: Exception) { null }
    }
}

/** Reads the text of real text elements that overlap a screen region (cheap: prunes everything else). */
object RegionText {
    fun read(svc: AccessibilityService, region: Rect): String {
        val items = ArrayList<TextHit>()
        var count = 0
        val ra = region.width().toLong() * region.height()
        for (root in FreqScan.roots(svc)) {
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            stack.add(root)
            while (!stack.isEmpty()) {
                val n = stack.removeLast()
                if (count >= 1200) { FreqScan.free(n); continue }
                count++
                val b = Rect()
                n.getBoundsInScreen(b)
                val has = b.width() > 0 && b.height() > 0
                if (has && !Rect.intersects(b, region)) { FreqScan.free(n); continue }
                val t = n.text ?: n.contentDescription
                if (t != null && t.length >= 2 && has) {
                    val ir = Rect(b)
                    if (ir.intersect(region)) {
                        val ia = ir.width().toLong() * ir.height()
                        val na = b.width().toLong() * b.height()
                        // the element must be (at least half) inside the box: a big container only partly inside would drag in text from outside
                        if (na > 0 && ia * 2 >= na && ra > 0) items.add(TextHit(t.toString().trim(), b))
                    }
                }
                for (i in 0 until n.childCount) {
                    val c = n.getChild(i)
                    if (c != null) stack.add(c)
                }
                FreqScan.free(n)
            }
        }
        if (items.isEmpty()) return ""
        items.sortWith(compareBy({ it.r.top / 8 }, { it.r.left }))
        val seen = HashSet<String>()
        val sb = StringBuilder()
        for (it in items) {
            if (it.t.isEmpty() || !seen.add(it.t)) continue
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(it.t)
            if (sb.length > 600) break
        }
        return sb.toString()
    }
}

class MovieData(val poster: Bitmap?, val titleAr: String, val meta: String, val synopsisAr: String)

/** Recognises a film title (iTunes Search, free, no key) and builds the Arabic info card data. */
object MovieLookup {
    private val GENRES = arrayOf(
        "sci-fi" to "خيال علمي", "science fiction" to "خيال علمي", "action" to "أكشن", "adventure" to "مغامرة",
        "animation" to "رسوم متحركة", "anime" to "أنمي", "comedy" to "كوميديا", "crime" to "جريمة",
        "documentary" to "وثائقي", "drama" to "دراما", "family" to "عائلي", "kids" to "أطفال", "fantasy" to "فانتازيا",
        "horror" to "رعب", "mystery" to "غموض", "romance" to "رومانسي", "thriller" to "إثارة", "suspense" to "إثارة",
        "war" to "حرب", "western" to "ويسترن", "music" to "موسيقى", "sport" to "رياضة", "biography" to "سيرة ذاتية"
    )

    fun looksLikeTitle(t: String): Boolean {
        if (t.contains('\n') || t.length < 4 || t.length > 45) return false
        val last = t.last()
        if (last in ".,!?…:;") return false
        for (ch in t) if (ch.code > 0x24F && ch != '’' && ch != '–') return false   // Latin script only
        val words = t.split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty() || words.size > 6) return false
        if (words.size == 1) return t.length >= 6 && t[0].isUpperCase()
        var big = 0
        var up = 0
        for (w in words) if (w.length > 3) { big++; if (w[0].isUpperCase()) up++ }
        return big == 0 || up * 10 >= big * 6
    }

    private fun key(s: String): String {
        var k = NationalTeams.key(s)
        if (k.startsWith("the") && k.length > 6) k = k.substring(3)
        return k
    }

    private fun genreAr(g: String): String {
        val l = g.lowercase()
        for (p in GENRES) if (l.contains(p.first)) return p.second
        return ""
    }

    private fun poster(url: String): Bitmap? {
        return try {
            val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 10000
            val bytes = c.inputStream.use { it.readBytes() }
            c.disconnect()
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            var ss = 1
            while (o.outWidth / (ss * 2) >= 360) ss *= 2
            val o2 = BitmapFactory.Options()
            o2.inSampleSize = ss
            o2.inPreferredConfig = Bitmap.Config.RGB_565
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o2)
        } catch (e: Exception) { null }
    }

    private fun cut(t: String, max: Int): String {
        if (t.length <= max) return t
        val i = t.lastIndexOf('.', max)
        return if (i > max / 2) t.substring(0, i + 1) else t.substring(0, max)
    }

    /** [orig] = text on screen, [ar] = its Arabic translation (used as the Arabic title). */
    fun find(orig: String, ar: String): MovieData? {
        val r = MatchLookup.http("https://itunes.apple.com/search?media=movie&entity=movie&limit=3&country=us&term=" +
            URLEncoder.encode(orig, "UTF-8"), 200000) ?: return null
        try {
            val arr = JSONObject(r).optJSONArray("results") ?: return null
            val ok = key(orig)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val name = o.optString("trackName", "")
                val tk = key(name)
                val match = tk == ok || (tk.startsWith(ok) && ok.length * 10 >= tk.length * 8)
                if (!match) continue
                val art = o.optString("artworkUrl100", "").replace("100x100bb", "600x600bb")
                val year = o.optString("releaseDate", "").take(4)
                val genre = genreAr(o.optString("primaryGenreName", ""))
                val dir = o.optString("artistName", "")
                val desc = cut(o.optString("longDescription", o.optString("shortDescription", "")), 260)
                val synAr = if (desc.isEmpty()) "" else (Translator.toArabic(desc) ?: "")
                val parts = ArrayList<String>()
                if (year.isNotEmpty()) parts.add(year)
                if (genre.isNotEmpty()) parts.add(genre)
                if (dir.isNotEmpty()) parts.add("إخراج: " + dir)
                return MovieData(if (art.isNotEmpty()) poster(art) else null, if (ar.isNotEmpty()) ar else name, parts.joinToString(" · "), synAr)
            }
        } catch (e: Exception) { }
        return null
    }
}

/** The translated strip. The font size follows the setting (not the box): the strip grows taller if the text needs it. */
class TransView(context: Context) : View(context) {
    private var text = ""
    private var scale = 1f
    private var color = Color.WHITE
    private var bg = 0
    private var opaque = false
    private val density = context.resources.displayMetrics.density
    private val screenH = context.resources.displayMetrics.heightPixels
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private var layout: StaticLayout? = null
    private var layoutW = 0

    fun style(sizeIdx: Int, colorIdx: Int, bgIdx: Int, solid: Boolean = false) {
        opaque = solid
        scale = floatArrayOf(0.7f, 0.85f, 1f, 1.25f, 1.6f)[sizeIdx.coerceIn(0, 4)]
        color = intArrayOf(0xFFFFFFFF.toInt(), 0xFFFFD60A.toInt(), 0xFF000000.toInt(), 0xFF64D2FF.toInt())[colorIdx.coerceIn(0, 3)]
        bg = bgIdx
        layout = null
        invalidate()
    }

    fun setText(t: String) {
        if (t == text) return
        text = t
        layout = null
        invalidate()
    }

    private fun padFor(w: Int): Int = maxOf(6, (w * 0.02f).toInt())

    private fun build(w: Int): StaticLayout? {
        if (text.isEmpty() || w < 20) return null
        val pad = padFor(w)
        val avail = (w - pad * 2).coerceAtLeast(10)
        var size = screenH * 0.040f * scale
        val maxH = screenH * 0.45f
        tp.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        tp.color = color
        if (bg == 2) tp.setShadowLayer(4f * density, 0f, 1.5f * density, 0xFF000000.toInt()) else tp.clearShadowLayer()
        var l: StaticLayout
        while (true) {
            tp.textSize = size
            l = StaticLayout.Builder.obtain(text, 0, text.length, tp, avail)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).build()
            if (l.height + pad * 2 <= maxH || size <= 12f * density) break
            size *= 0.92f
        }
        return l
    }

    /** Height the strip needs for the current text at width [w]. */
    fun needHeight(w: Int): Int {
        val l = build(w) ?: return 0
        layout = l
        layoutW = w
        return l.height + padFor(w) * 2
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        layout = null
        super.onSizeChanged(w, h, ow, oh)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w < 4f || h < 4f || text.isEmpty()) return
        val r = minOf(h * 0.18f, 14f * density)
        when (bg) {
            0 -> { paint.color = if (opaque) 0xFF000000.toInt() else 0xD0000000.toInt(); c.drawRoundRect(RectF(0f, 0f, w, h), r, r, paint) }
            1 -> { paint.color = if (opaque) 0xFFFFFFFF.toInt() else 0xEBFFFFFF.toInt(); c.drawRoundRect(RectF(0f, 0f, w, h), r, r, paint) }
        }
        var l = layout
        if (l == null || layoutW != width) {
            l = build(width)
            layout = l
            layoutW = width
        }
        val ly = l ?: return
        c.save()
        c.translate(padFor(width).toFloat(), (h - ly.height) / 2f)
        ly.draw(c)
        c.restore()
    }
}

/** Movable / resizable box shown while the user chooses where the original text is. */
class BoxView(context: Context) : View(context) {
    var host: ScreenTranslator? = null
    var sizeMode = false
    private val density = context.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var lx = 0f
    private var ly = 0f
    private var sx = 0f
    private var sy = 0f
    private var moved = false
    private var mode = 0     // 0 move, 1 resize, 2 ok button

    private fun okRect(): RectF {
        val s = minOf(width * 0.18f, height * 0.6f).coerceAtLeast(36f * density)
        return RectF(width - s * 1.6f - 6f * density, 6f * density, width - 6f * density, 6f * density + s * 0.8f)
    }

    private fun handle(): Float = maxOf(28f * density, minOf(width, height) * 0.25f)

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lx = ev.rawX; ly = ev.rawY; sx = lx; sy = ly; moved = false
                val hs = handle()
                mode = if (okRect().contains(ev.x, ev.y)) 2 else if (ev.x > width - hs && ev.y > height - hs) 1 else 0
            }
            MotionEvent.ACTION_MOVE -> {
                if (Math.abs(ev.rawX - sx) > 14f * density || Math.abs(ev.rawY - sy) > 14f * density) moved = true
                if (moved && mode != 2) {
                    val dx = (ev.rawX - lx).toInt()
                    val dy = (ev.rawY - ly).toInt()
                    if (dx != 0 || dy != 0) { host?.dragBy(dx, dy, mode == 1); lx = ev.rawX; ly = ev.rawY }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (mode == 2 && !moved) host?.confirm() else if (moved) host?.saveRect()
            }
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w < 4f || h < 4f) return
        p.style = Paint.Style.FILL
        p.color = 0x3330D5C8
        c.drawRect(0f, 0f, w, h, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 3f * density
        p.color = if (sizeMode) 0xFFFF9F0A.toInt() else 0xFF30D5C8.toInt()
        p.pathEffect = DashPathEffect(floatArrayOf(12f * density, 7f * density), 0f)
        c.drawRect(2f * density, 2f * density, w - 2f * density, h - 2f * density, p)
        p.pathEffect = null
        p.style = Paint.Style.FILL
        val hs = handle()
        val tri = Path()
        tri.moveTo(w, h - hs); tri.lineTo(w, h); tri.lineTo(w - hs, h); tri.close()
        c.drawPath(tri, p)
        // OK button
        val ok = okRect()
        p.color = 0xFF30D5C8.toInt()
        c.drawRoundRect(ok, ok.height() / 2f, ok.height() / 2f, p)
        p.color = 0xFF04201E.toInt()
        p.textAlign = Paint.Align.CENTER
        p.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        p.textSize = ok.height() * 0.55f
        c.drawText("OK", ok.centerX(), ok.centerY() - (p.descent() + p.ascent()) / 2f, p)
        // hint (mode aware)
        val hint = if (sizeMode) "وضع الحجم · الأسهم تغيّر الأبعاد · OK مطوّل = تحريك · OK = تأكيد"
        else "وضع التحريك · OK مطوّل = تغيير الحجم · OK = تأكيد"
        p.textSize = minOf(h * 0.22f, 15f * density)
        var tw = p.measureText(hint)
        if (tw > w * 0.9f) { p.textSize = p.textSize * (w * 0.9f) / tw; tw = p.measureText(hint) }
        if (p.textSize >= 9f * density) {
            p.color = 0x99000000.toInt()
            c.drawRoundRect(RectF(w / 2f - tw / 2f - 8f * density, h / 2f - p.textSize, w / 2f + tw / 2f + 8f * density, h / 2f + p.textSize * 0.7f), 8f * density, 8f * density, p)
            p.color = 0xFFFFFFFF.toInt()
            c.drawText(hint, w / 2f, h / 2f, p)
        }
    }
}

/** Side card with the film poster and Arabic info. */
class PosterView(context: Context) : View(context) {
    var data: MovieData? = null
    private val density = context.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val d = data ?: return
        if (w < 4f || h < 4f) return
        val r = w * 0.07f
        path.reset()
        path.addRoundRect(RectF(0f, 0f, w, h), r, r, Path.Direction.CW)
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, 0f, 0f, h, 0xF2101018.toInt(), 0xF2222236.toInt(), Shader.TileMode.CLAMP)
        c.drawPath(path, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.6f * density
        p.color = 0x99FFC83D.toInt()
        c.drawPath(path, p)
        p.style = Paint.Style.FILL
        val pad = w * 0.07f
        var y = pad
        val pb = d.poster
        if (pb != null) {
            val maxW = w - pad * 2
            val maxH = h * 0.58f
            val sc = minOf(maxW / pb.width, maxH / pb.height)
            val dw = pb.width * sc
            val dh = pb.height * sc
            val l = (w - dw) / 2f
            val rr = Path()
            rr.addRoundRect(RectF(l, y, l + dw, y + dh), r * 0.5f, r * 0.5f, Path.Direction.CW)
            c.save()
            c.clipPath(rr)
            c.drawBitmap(pb, null, RectF(l, y, l + dw, y + dh), p)
            c.restore()
            y += dh + pad * 0.8f
        }
        val tw = (w - pad * 2).toInt()
        tp.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        tp.color = 0xFFFFFFFF.toInt()
        tp.textSize = w * 0.085f
        val tl = StaticLayout.Builder.obtain(d.titleAr, 0, d.titleAr.length, tp, tw)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).setIncludePad(false).build()
        c.save(); c.translate(pad, y); tl.draw(c); c.restore()
        y += tl.height + pad * 0.4f
        if (d.meta.isNotEmpty()) {
            tp.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            tp.color = 0xFFFFC83D.toInt()
            tp.textSize = w * 0.058f
            val ml = StaticLayout.Builder.obtain(d.meta, 0, d.meta.length, tp, tw)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).setIncludePad(false).build()
            c.save(); c.translate(pad, y); ml.draw(c); c.restore()
            y += ml.height + pad * 0.5f
        }
        if (d.synopsisAr.isNotEmpty()) {
            tp.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            tp.color = 0xDDFFFFFF.toInt()
            tp.textSize = w * 0.052f
            val left = h - y - pad
            val lines = (left / (tp.textSize * 1.25f)).toInt()
            if (lines >= 1) {
                val sl = StaticLayout.Builder.obtain(d.synopsisAr, 0, d.synopsisAr.length, tp, tw)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END).setIncludePad(false).build()
                c.save(); c.translate(pad, y); sl.draw(c); c.restore()
            }
        }
    }
}

/**
 * Screen translation to Arabic for any app.
 * 1) Text elements inside the user's box are read through accessibility (cheap, most apps).
 * 2) If the box has no text elements (subtitles burned into a channel / video), a screenshot of the
 *    box is read with on-device OCR (Android 11+), only when the picture in the box changed.
 * The Arabic text is drawn over (or just above) the original. Nothing runs when nothing changes.
 */
class ScreenTranslator(private val svc: KeyService) {
    private val ctx: Context = svc
    private val ui = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var worker: Handler? = null
    private var exec: ExecutorService? = null
    private var ocrExec: ExecutorService? = null

    @Volatile var enabled = false
    @Volatile var placing = false
    @Volatile private var running = false
    private var sizeIdx = 2
    private var colorIdx = 0
    private var bgIdx = 0
    private var posterOn = true
    private var ocrOn = true
    @Volatile private var langMask = LangDetect.ALL
    @Volatile private var curLang: String? = null
    private var langIdx = 0

    private var rect = IntArray(4)
    private var sw = 1920
    private var sh = 1080
    private var sizeMode = false
    private var toggled = false
    private var placeSince = 0L
    private var okDown = false
    private var backDown = false

    private var boxView: BoxView? = null
    private var boxAttached = false
    private var transView: TransView? = null
    private var transAttached = false
    @Volatile private var shownText = ""
    private var posterView: PosterView? = null
    private var posterAttached = false

    @Volatile private var scanPending = false
    private var lastScan = 0L
    private var lastDur = 0L
    @Volatile private var lastText = ""
    @Volatile private var lastAr = ""
    @Volatile private var stable = 0
    @Volatile private var emptyCount = 0
    @Volatile private var busy = false
    @Volatile private var pending: String? = null
    private var movieTried = ""
    private val hidePosterRun = Runnable { detachPoster() }
    private var movieExec: ExecutorService? = null
    @Volatile private var lastShotAt = 0L
    private var lastPing = 0L
    private var screenRx: BroadcastReceiver? = null
    private var previewView: TransView? = null
    private val previewHide = Runnable { remove(previewView) }
    private var lastStyleKey = ""
    @Volatile private var emptyStreak = 0
    private var lastNodeCheck = 0L
    private var stickyMode = 0
    /** The sentence is hidden when it has not been seen for a moment; seeing it again refreshes the timer. */
    private val hideRun = Runnable {
        lastText = ""
        lastAr = ""
        stable = 0
        hideTrans()
    }

    // OCR state
    @Volatile private var ocrBusy = false
    @Volatile private var ocrMode = false
    @Volatile private var lastOcrMs = 0L
    private var lastSig: IntArray? = null
    private var lastOcrText = ""

    private val pollRun = object : Runnable {
        override fun run() {
            if (!running) return
            requestScan()
            if (OcrEngine.lastUse > 0 && System.currentTimeMillis() - OcrEngine.lastUse > 1200000L) OcrEngine.release()
            val nowP = SystemClock.elapsedRealtime()
            if (nowP - lastPing > 45000L) {
                lastPing = nowP
                exec?.execute { try { Translator.ping() } catch (e: Exception) { } }
            }
            worker?.postDelayed(this, if (ocrMode) 700L else 1500L)
        }
    }

    fun configure() {
        val on = Prefs.transOn(ctx)
        sizeIdx = Prefs.transSize(ctx)
        colorIdx = Prefs.transColor(ctx)
        bgIdx = Prefs.transBg(ctx)
        posterOn = Prefs.transPoster(ctx)
        ocrOn = Prefs.transOcr(ctx)
        langIdx = Prefs.transLang(ctx)
        langMask = Prefs.transLangMask(ctx)
        val want = LangDetect.scripts(langMask)
        if (mru.toSet() != want.toSet()) {
            val keep = mru.filter { it in want }
            mru.clear()
            mru.addAll(keep)
            for (p in want) if (p !in mru) mru.add(p)
        }
        val styleKey = "" + sizeIdx + "/" + colorIdx + "/" + bgIdx
        val styleChanged = lastStyleKey.isNotEmpty() && styleKey != lastStyleKey
        lastStyleKey = styleKey
        if (!on) {
            if (enabled) shutdown()
            return
        }
        enabled = true
        val dm = DisplayMetrics()
        (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        sw = dm.widthPixels
        sh = dm.heightPixels
        transView?.style(sizeIdx, colorIdx, bgIdx, false)
        if (transAttached) relayoutTrans()
        if (thread == null) {
            val t = HandlerThread("trans-scan", Process.THREAD_PRIORITY_DEFAULT)
            t.start()
            thread = t
            worker = Handler(t.looper)
            exec = Executors.newSingleThreadExecutor()
            movieExec = Executors.newSingleThreadExecutor()
            ocrExec = Executors.newSingleThreadExecutor { r ->
                val th = Thread(r, "trans-ocr")
                th.priority = Thread.NORM_PRIORITY - 1
                th
            }
        }
        if (screenRx == null) {
            val rx = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    // back from sleep: warm everything up again right away
                    if (running) { emptyStreak = 0; warmUp(); requestScan() }
                }
            }
            try {
                val f = IntentFilter(Intent.ACTION_SCREEN_ON)
                f.addAction(Intent.ACTION_USER_PRESENT)
                ctx.registerReceiver(rx, f)
                screenRx = rx
            } catch (e: Exception) { }
        }
        if (Prefs.transRect(ctx) == null) {
            if (!placing) startPlace()
        } else if (!placing && !running) {
            loadRect()
            startRunning()
        }
        if (styleChanged) showPreview()
    }

    /** Shows a sample sentence for a moment so size / colour / background changes are visible at once (even over the settings). */
    private fun showPreview() {
        if (!enabled || placing || Prefs.transRect(ctx) == null) return
        if (!running) loadRect()
        val v = previewView ?: TransView(ctx).also { previewView = it }
        v.style(sizeIdx, colorIdx, bgIdx, false)
        v.setText("هذا نص تجريبي لمعاينة حجم الخط واللون والخلفية")
        attach(v, false, stripRect(v)) { }
        ui.removeCallbacks(previewHide)
        ui.postDelayed(previewHide, 3500L)
    }

    fun shutdown() {
        enabled = false
        running = false
        placing = false
        ui.removeCallbacks(hidePosterRun)
        ui.removeCallbacks(previewHide)
        remove(previewView)
        val rx = screenRx
        if (rx != null) try { ctx.unregisterReceiver(rx) } catch (e: Exception) { }
        screenRx = null
        detachBox()
        detachTrans()
        detachPoster()
        thread?.quitSafely()
        thread = null
        worker = null
        exec?.shutdownNow()
        exec = null
        movieExec?.shutdownNow()
        movieExec = null
        ui.removeCallbacks(hideRun)
        ocrExec?.shutdownNow()
        ocrExec = null
        scanPending = false
        lastText = ""
        shownText = ""
        OcrEngine.release()
    }

    // ---------------------------------------------------------------- placing the box

    private fun loadRect() {
        val f = Prefs.transRect(ctx) ?: floatArrayOf(0.15f, 0.78f, 0.7f, 0.12f)
        rect = intArrayOf((f[0] * sw).toInt(), (f[1] * sh).toInt(), ((f[0] + f[2]) * sw).toInt(), ((f[1] + f[3]) * sh).toInt())
        clamp()
    }

    private fun clamp() {
        val minW = (sw * 0.10f).toInt()
        val minH = (sh * 0.05f).toInt()
        if (rect[2] - rect[0] < minW) rect[2] = rect[0] + minW
        if (rect[3] - rect[1] < minH) rect[3] = rect[1] + minH
        if (rect[0] < 0) rect[0] = 0
        if (rect[1] < 0) rect[1] = 0
        if (rect[2] > sw) rect[2] = sw
        if (rect[3] > sh) rect[3] = sh
    }

    fun saveRect() {
        Prefs.setTransRect(ctx, floatArrayOf(rect[0] / sw.toFloat(), rect[1] / sh.toFloat(),
            (rect[2] - rect[0]) / sw.toFloat(), (rect[3] - rect[1]) / sh.toFloat()))
    }

    /** Where the Arabic strip is drawn: over the box, or just above it (below if there is no room). */
    private fun transRect(): IntArray {
        val h = rect[3] - rect[1]
        return if (rect[1] - h >= 0) intArrayOf(rect[0], rect[1] - h, rect[2], rect[1]) else intArrayOf(rect[0], minOf(sh - h, rect[3]), rect[2], minOf(sh, rect[3] + h))
    }

    /** The strip's window: [transRect] but taller if the text needs it (grows upward, or downward when it sits below the box). */
    private fun stripRect(v: TransView): IntArray {
        val base = transRect()
        val w = base[2] - base[0]
        val h = base[3] - base[1]
        val need = v.needHeight(w)
        if (need <= h) return base
        return if (base[1] >= rect[3]) intArrayOf(base[0], base[1], base[2], minOf(sh, base[1] + need))
        else intArrayOf(base[0], maxOf(0, base[3] - need), base[2], base[3])
    }

    /** The box in the screenshot's own pixels (the screenshot can have a different resolution from the UI). */
    private fun boxFor(f: Bitmap): Rect {
        val kx = f.width.toFloat() / sw
        val ky = f.height.toFloat() / sh
        return OcrPrep.clampRect(f, Rect((rect[0] * kx).toInt(), (rect[1] * ky).toInt(), (rect[2] * kx).toInt(), (rect[3] * ky).toInt()))
    }

    fun startPlace() {
        if (!enabled) return
        running = false
        loadRect()
        svc.showMode("hidden")
        detachTrans()
        placing = true
        sizeMode = false
        okDown = false
        backDown = false
        placeSince = SystemClock.elapsedRealtime()
        val v = boxView ?: BoxView(ctx).also { it.host = this; boxView = it }
        v.sizeMode = false
        attach(v, true, rect) { boxAttached = it }
    }

    fun confirm() {
        if (!placing) return
        placing = false
        saveRect()
        detachBox()
        startRunning()
    }

    fun dragBy(dx: Int, dy: Int, resize: Boolean) {
        if (resize) { rect[2] += dx; rect[3] += dy } else {
            val w = rect[2] - rect[0]
            val h = rect[3] - rect[1]
            val nx = (rect[0] + dx).coerceIn(0, maxOf(0, sw - w))
            val ny = (rect[1] + dy).coerceIn(0, maxOf(0, sh - h))
            rect[0] = nx; rect[1] = ny; rect[2] = nx + w; rect[3] = ny + h
        }
        clamp()
        relayoutBox()
    }

    private var okDownAt = 0L

    private fun flipMode() {
        sizeMode = !sizeMode
        boxView?.sizeMode = sizeMode
        boxView?.invalidate()
    }

    /**
     * Remote while placing: arrows move / resize, OK = start, long OK = switch move<->size.
     * Long press is measured by time (not only by key repeats, which some remotes never send).
     * Number keys 4/6 = narrower/wider, 2/8 = shorter/taller, CH+/CH- = taller/shorter.
     * Key presses that began before the box appeared (the click that switched the option on) are ignored.
     */
    fun onPlaceKey(e: KeyEvent): Boolean {
        val code = e.keyCode
        val now = SystemClock.elapsedRealtime()
        val grace = now - placeSince < 800L
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            if (e.action == KeyEvent.ACTION_DOWN) {
                if (e.repeatCount == 0) { toggled = false; okDown = !grace; okDownAt = now }
                if (okDown && !toggled && (e.repeatCount >= 3 || now - okDownAt >= 650L)) {
                    toggled = true
                    flipMode()
                }
            } else if (e.action == KeyEvent.ACTION_UP) {
                if (okDown && !toggled) {
                    if (now - okDownAt >= 650L) flipMode() else confirm()
                }
                okDown = false
                toggled = false
            }
            return true
        }
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) backDown = !grace
            if (e.action == KeyEvent.ACTION_UP) {
                if (backDown) confirm()
                backDown = false
            }
            return true
        }
        if (e.action != KeyEvent.ACTION_DOWN) return true
        val step = maxOf(6, (sw * 0.008f * (1 + minOf(e.repeatCount, 18) / 2)).toInt())
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> dragBy(-step, 0, sizeMode)
            KeyEvent.KEYCODE_DPAD_RIGHT -> dragBy(step, 0, sizeMode)
            KeyEvent.KEYCODE_DPAD_UP -> dragBy(0, -step, sizeMode)
            KeyEvent.KEYCODE_DPAD_DOWN -> dragBy(0, step, sizeMode)
            KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_NUMPAD_4, KeyEvent.KEYCODE_MEDIA_REWIND -> dragBy(-step, 0, true)
            KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_NUMPAD_6, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> dragBy(step, 0, true)
            KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_NUMPAD_2, KeyEvent.KEYCODE_CHANNEL_UP -> dragBy(0, -step, true)
            KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_NUMPAD_8, KeyEvent.KEYCODE_CHANNEL_DOWN -> dragBy(0, step, true)
        }
        return true
    }

    // ---------------------------------------------------------------- scanning + translating

    private fun startRunning() {
        running = true
        lastText = ""
        lastAr = ""
        lastOcrText = ""
        lastSig = null
        stable = 0
        emptyStreak = 0
        worker?.removeCallbacks(pollRun)
        worker?.postDelayed(pollRun, 800L)
        warmUp()
        requestScan()
    }

    /** Loads the OCR engine, opens the translation connection and fetches the other script models in the background. */
    private fun warmUp() {
        exec?.execute { try { Translator.toArabic("Hello") } catch (e: Exception) { } }
        if (!ocrOn || !OcrEngine.available || Build.VERSION.SDK_INT < 30) return
        ocrExec?.execute {
            try {
                val b = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
                b.eraseColor(0xFFFFFFFF.toInt())
                OcrEngine.recognize(ctx, OcrEngine.files(sticky), b)
                b.recycle()
            } catch (e: Throwable) { }
        }
        Thread {
            try {
                for (p in mru.toList()) if (p != mru[0]) OcrEngine.ensure(ctx, OcrEngine.files(p))
            } catch (e: Throwable) { }
        }.start()
    }

    /** Window events from anywhere on screen: they matter only when the box is read as real text (never for the picture). */
    fun onChange() {
        if (running && !ocrMode) requestScan()
    }

    fun onMode(m: String) {
        if (m == "full" || m == "standby" || m == "prompt") {
            detachBox()
            detachTrans()
            detachPoster()
        } else {
            if (placing) boxView?.let { attach(it, true, rect) { a -> boxAttached = a } }
            else if (running && shownText.isNotEmpty()) showTrans(shownText)
        }
    }

    private fun requestScan() {
        val w = worker ?: return
        if (scanPending || !running) return
        scanPending = true
        // Screenshots may not come closer than ~333 ms (Android refuses them), so ~380 ms is the fastest loop.
        val now0 = SystemClock.elapsedRealtime()
        val since = now0 - lastScan
        val wait: Long = if (ocrMode) maxOf(0L, (if (emptyStreak < 12) 380L else 600L) - since)
        else maxOf(0L, maxOf(500L, lastDur * 3L) - since)
        w.postDelayed({
            scanPending = false
            val t0 = SystemClock.elapsedRealtime()
            lastScan = t0
            doScan()
            lastDur = SystemClock.elapsedRealtime() - t0
            val mm = svc.mode
            if (ocrMode && mm != "full" && mm != "standby" && mm != "prompt") requestScan()
        }, wait)
    }

    private fun doScan() {
        if (!running || placing) return
        val m = svc.mode
        if (m == "full" || m == "standby" || m == "prompt") return
        if (ocrMode && ocrBusy) return
        val now = SystemClock.elapsedRealtime()
        var text = ""
        if (!ocrMode || now - lastNodeCheck > 3000L) {
            lastNodeCheck = now
            text = try { RegionText.read(svc, Rect(rect[0], rect[1], rect[2], rect[3])) } catch (e: Exception) { "" }
        }
        if (text.length >= 2 && text.count { it.isLetter() } >= 2) {
            ocrMode = false
            onText(text)
            return
        }
        if (ocrOn && OcrEngine.available && Build.VERSION.SDK_INT >= 30) {
            ocrMode = true
            ocrOnce()
        } else {
            ocrMode = false
            onText("")
        }
    }

    private val WSPLIT = Regex("[^\\p{L}\\p{N}]+")

    private fun words(t: String): Set<String> {
        val out = HashSet<String>()
        for (w in WSPLIT.split(t.lowercase())) if (w.length > 1) out.add(w)
        return out
    }

    /** Same sentence read with small OCR differences. A second line appearing or disappearing counts as a different text. */
    private fun sameText(a: String, b: String): Boolean {
        if (a == b) return true
        val wa = words(a)
        val wb = words(b)
        if (wa.isEmpty() || wb.isEmpty()) return false
        var inter = 0
        for (w in wa) if (w in wb) inter++
        val uni = wa.size + wb.size - inter
        return uni > 0 && inter.toFloat() / uni >= 0.7f
    }

    /** Keeps the shown sentence alive while it is still being read; hides it shortly after it disappears. */
    private fun refreshHide() {
        var hold = if (ocrMode) minOf(4000L, maxOf(1500L, lastOcrMs * 2L + 700L)) else 1600L
        ui.post {
            ui.removeCallbacks(hideRun)
            ui.postDelayed(hideRun, hold)
        }
    }

    /** Common handling of a freshly read text ("" = nothing in the box). */
    private fun onText(text: String) {
        if (text.length < 2 || text.count { it.isLetter() } < 2) {
            emptyStreak++
            stable = 0
            return
        }
        emptyStreak = 0
        refreshHide()
        if (lastText.isNotEmpty() && sameText(text, lastText)) {
            stable++
            if (stable == 1 && posterOn && lastAr.isNotEmpty() && MovieLookup.looksLikeTitle(lastText)) movie(lastText, lastAr)
            return
        }
        lastText = text
        stable = 0
        translate(text)
    }

    // ---- OCR path

    private fun shotCallback(early: (() -> Unit)?, cb: (Bitmap?) -> Unit): AccessibilityService.TakeScreenshotCallback {
        return object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                early?.invoke()          // the picture is already taken: bring the strip back before the slow copy
                var bmp: Bitmap? = null
                try {
                    val hb = result.hardwareBuffer
                    val hw = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                    if (hw != null) {
                        bmp = hw.copy(Bitmap.Config.ARGB_8888, false)
                        hw.recycle()
                    }
                    hb.close()
                } catch (e: Exception) { }
                cb(bmp)
            }

            override fun onFailure(errorCode: Int) {
                early?.invoke()
                cb(null)
            }
        }
    }

    /** Android refuses screenshots less than ~333 ms apart (that silently dropped half of the reads before). */
    private fun waitShotSlot() {
        val w = 380L - (SystemClock.elapsedRealtime() - lastShotAt)
        if (w > 0) try { Thread.sleep(w) } catch (e: Exception) { }
    }

    /** Screenshot of the whole display (Android 11+). The callback gets a software copy or null. */
    private fun capture(early: (() -> Unit)? = null, cb: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) { early?.invoke(); cb(null); return }
        waitShotSlot()
        lastShotAt = SystemClock.elapsedRealtime()
        try {
            svc.takeScreenshot(android.view.Display.DEFAULT_DISPLAY, { r -> Thread(r).start() }, shotCallback(early, cb))
        } catch (e: Exception) {
            early?.invoke()
            cb(null)
        }
    }

    private fun ocrOnce() {
        if (ocrBusy) return
        val ox = ocrExec ?: return
        ocrBusy = true
        ox.execute {
            capture(null) { frame ->
                if (frame == null) {
                    ocrBusy = false          // a refused screenshot is not "no text"
                } else {
                    try { processFrame(frame) } catch (e: Throwable) { } finally { frame.recycle(); ocrBusy = false }
                }
            }
        }
    }

    /** Scripts, most recently successful first. A language change is found by trying the others right away. */
    private val mru = arrayListOf("Latin", "Cyrillic")
    private val sticky: String get() = mru[0]
    private var lastAltAt = 0L

    private fun readWith(bmp: Bitmap, allowAlt: Boolean): String {
        val r = OcrEngine.recognize(ctx, OcrEngine.files(mru[0]), bmp) ?: return ""
        val c = OcrPrep.clean(r.first)
        if (r.second >= 55 && c.length >= 3) return c
        if (!allowAlt) return ""
        // something that looks like text but was read badly = probably another script: try the others soon
        var letters = 0
        for (ch in r.first) if (ch.isLetter()) letters++
        val now = SystemClock.elapsedRealtime()
        if (now - lastAltAt < (if (letters >= 4) 1500L else 6000L)) return ""
        lastAltAt = now
        for (i in 1 until mru.size) {
            val p = mru[i]
            val a = OcrEngine.recognize(ctx, OcrEngine.files(p), bmp) ?: continue
            val ac = OcrPrep.clean(a.first)
            if (a.second >= 55 && ac.length >= 3) {
                mru.removeAt(i)
                mru.add(0, p)
                return ac
            }
        }
        return ""
    }

    private fun processFrame(frame: Bitmap) {
        val box = boxFor(frame)
        val sig = OcrPrep.sig(frame, box)
        val prev = lastSig
        if (prev != null && OcrPrep.diff(sig, prev) <= 5) {
            onText(lastOcrText)          // same picture: reuse, no OCR work
            return
        }
        lastSig = sig
        val t0 = SystemClock.elapsedRealtime()
        // polarity (bright / dark text) that worked last time goes first; the other one only if it finds nothing
        val order = if (stickyMode == 0) intArrayOf(0, 1) else intArrayOf(1, 0)
        val bmps = ArrayList<Bitmap>()
        var best = ""
        for (m in order) {
            val b = OcrPrep.prepare(frame, box, m) ?: continue
            bmps.add(b)
            best = readWith(b, false)
            if (best.isNotEmpty()) { stickyMode = m; break }
        }
        if (best.isEmpty() && bmps.isNotEmpty()) best = readWith(bmps[0], true)
        for (b in bmps) b.recycle()
        lastOcrMs = SystemClock.elapsedRealtime() - t0
        lastOcrText = best
        onText(best)
    }

    // ---- translate

    private val tLock = Any()
    private var seqCounter = 0
    @Volatile private var shownSeq = 0
    private var pendText: String? = null
    private var pendSeq = 0

    /** Lines of one subtitle are translated together as one sentence. Results are shown in order, never discarded. */
    private fun translate(text0: String) {
        val text = text0.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        val ex = exec ?: return
        val my = synchronized(tLock) { seqCounter++; seqCounter }
        val start = synchronized(tLock) {
            if (busy) { pendText = text; pendSeq = my; false } else { busy = true; true }
        }
        if (!start) return
        ex.execute {
            var cur = text
            var curSeq = my
            while (true) {
                val sl = LangDetect.detect(cur, langMask, curLang)
                if (sl != null) curLang = sl
                val ar = try { Translator.toArabic(cur, sl) } catch (e: Exception) { null }
                if (sl == null) {
                    val d = Translator.lastDetected
                    if (d.isNotEmpty() && LangDetect.isEnabled(d, langMask)) curLang = d
                }
                if (ar == null) {
                    lastText = ""          // failed (network): try again on the next read
                } else if (curSeq > shownSeq) {
                    shownSeq = curSeq
                    if (ar.isEmpty()) {
                        lastAr = ""
                        ui.post { hideTrans() }
                    } else {
                        lastAr = ar
                        val a: String = ar
                        ui.post { showTrans(a) }
                    }
                }
                val nxt: Pair<String, Int>? = synchronized(tLock) {
                    val t = pendText
                    if (t == null) { busy = false; null } else { pendText = null; Pair(t, pendSeq) }
                }
                if (nxt == null) break
                cur = nxt.first
                curSeq = nxt.second
            }
        }
    }

    private fun movie(orig: String, ar: String) {
        if (movieTried == orig) return
        movieTried = orig
        val ex = movieExec ?: return
        ex.execute {
            val d = try { MovieLookup.find(orig, ar) } catch (e: Exception) { null }
            if (d != null) ui.post { showPoster(d) }
        }
    }

    // ---------------------------------------------------------------- diagnostics

    /** Settings "Test OCR": what each reading method sees right now. */
    fun testReport(): String {
        val sb = StringBuilder()
        sb.append("android=").append(Build.VERSION.SDK_INT)
        if (!enabled) return sb.append(" | option is off").toString()
        if (Prefs.transRect(ctx) == null) return sb.append(" | place the box first").toString()
        if (!placing && !running) loadRect()
        val r = Rect(rect[0], rect[1], rect[2], rect[3])
        val nodes = try { RegionText.read(svc, r) } catch (e: Exception) { "" }
        sb.append(" | languages: ").append(LangDetect.CODES.filterIndexed { i, _ -> ((langMask shr i) and 1) == 1 }.joinToString(",")).append(" current=").append(curLang ?: "-")
        sb.append(" | text elements: ").append(if (nodes.isEmpty()) "none" else nodes.take(100).replace('\n', ' '))
        if (Build.VERSION.SDK_INT < 30) return sb.append(" | screenshot: needs Android 11+").toString()
        val latch = CountDownLatch(1)
        var frame: Bitmap? = null
        capture { b -> frame = b; latch.countDown() }
        try { latch.await(6, TimeUnit.SECONDS) } catch (e: Exception) { }
        val f = frame
        if (f == null) return sb.append(" | screenshot: FAILED (re-enable the accessibility service once)").toString()
        sb.append(" | screenshot ").append(f.width).append("x").append(f.height).append(" boxLuma=").append(OcrPrep.avgLuma(f, boxFor(f)))
        sb.append(" | engine=").append(if (OcrEngine.available) "included" else "MISSING")
        if (OcrEngine.available) {
            val box = boxFor(f)
            sb.append(" | box=").append(rect[0]).append(",").append(rect[1]).append(",").append(rect[2]).append(",").append(rect[3]).append(" (only this area is read)")
            val bmps = ArrayList<Bitmap>()
            for (mode in 0..1) { val b = OcrPrep.prepare(f, box, mode); if (b != null) bmps.add(b) }
            if (bmps.isEmpty()) sb.append(" | no text-like pixels in the box")
            for (pf in mru.toList()) {
                var bestConf = -1
                var bestTxt = ""
                var ms = 0L
                var failed = false
                for (b in bmps) {
                    val t0 = SystemClock.elapsedRealtime()
                    val res = OcrEngine.recognize(ctx, OcrEngine.files(pf), b)
                    ms += SystemClock.elapsedRealtime() - t0
                    if (res == null) { failed = true; break }
                    val c = OcrPrep.clean(res.first)
                    if (res.second > bestConf && c.isNotEmpty()) { bestConf = res.second; bestTxt = c }
                }
                sb.append(" | ").append(pf).append(": ")
                if (failed) sb.append(OcrEngine.status) else sb.append(ms).append("ms conf=").append(bestConf).append(" ").append(bestTxt.take(80).replace('\n', ' '))
                if (bestConf >= 70) break
            }
            for (b in bmps) b.recycle()
        } else sb.append(" | ").append(OcrEngine.status)
        f.recycle()
        return sb.toString()
    }

    // ---------------------------------------------------------------- windows

    private fun lp(r: IntArray, touchable: Boolean): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        flags = flags or (if (touchable) WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        val p = WindowManager.LayoutParams(r[2] - r[0], r[3] - r[1], WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT)
        p.gravity = Gravity.TOP or Gravity.START
        p.x = r[0]
        p.y = r[1]
        return p
    }

    private fun attach(v: View, touchable: Boolean, rr: IntArray, done: (Boolean) -> Unit) {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        try {
            if (v.parent != null) wm.updateViewLayout(v, lp(rr, touchable)) else wm.addView(v, lp(rr, touchable))
            done(true)
        } catch (e: Exception) {
            done(false)
        }
    }

    private fun relayoutBox() {
        val v = boxView ?: return
        if (!boxAttached) return
        try { (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).updateViewLayout(v, lp(rect, true)) } catch (e: Exception) { }
    }

    private fun relayoutTrans() {
        val v = transView ?: return
        if (v.parent == null) return
        try { (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).updateViewLayout(v, lp(stripRect(v), false)) } catch (e: Exception) { }
    }

    private fun remove(v: View?) {
        if (v == null || v.parent == null) return
        try { (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) } catch (e: Exception) { }
    }

    private fun detachBox() { remove(boxView); boxAttached = false }
    private fun detachTrans() { remove(transView); transAttached = false }
    private fun detachPoster() { ui.removeCallbacks(hidePosterRun); remove(posterView); posterAttached = false }

    private fun showTrans(ar: String) {
        val m = svc.mode
        shownText = ar
        if (m == "full" || m == "standby" || m == "prompt" || placing) return
        val v = transView ?: TransView(ctx).also { transView = it }
        v.style(sizeIdx, colorIdx, bgIdx, false)
        v.setText(ar)
        v.alpha = 1f
        attach(v, false, stripRect(v)) { transAttached = it }
    }

    private fun hideTrans() {
        shownText = ""
        detachTrans()
        transView?.setText("")
    }

    private fun showPoster(d: MovieData) {
        val m = svc.mode
        if (m == "full" || m == "standby" || m == "prompt" || placing) return
        val v = posterView ?: PosterView(ctx).also { posterView = it }
        v.data = d
        val r = intArrayOf((sw * 0.02f).toInt(), (sh * 0.14f).toInt(), (sw * 0.22f).toInt(), (sh * 0.80f).toInt())
        attach(v, false, r) { posterAttached = it }
        v.invalidate()
        ui.removeCallbacks(hidePosterRun)
        ui.postDelayed(hidePosterRun, 12000L)
    }
}
