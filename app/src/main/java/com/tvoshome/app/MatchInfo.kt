package com.tvoshome.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** One piece of on-screen text with its position. */
class TextHit(val t: String, val r: Rect)

/** Teams found in the on-screen text (names exactly as written on screen, any language). */
class TeamPair(val a: String, val b: String, val r: Rect) {
    val key: String get() = MatchParse.norm(a) + "|" + MatchParse.norm(b)
}

/** What the card shows. */
class MatchData(
    val homeAr: String, val awayAr: String,
    val homeLogo: Bitmap?, val awayLogo: Bitmap?,
    val comp: String, val channel: String, val commentator: String, val src: String
)

object MatchParse {
    private val RANGE = Regex("\\d{1,2}[:.]\\d{2}\\s*[-–—~]+\\s*\\d{1,2}[:.]\\d{2}")
    private val TIME = Regex("\\b\\d{1,2}[:.]\\d{2}\\b")
    private val DATE = Regex("\\b\\d{1,2}[/.]\\d{1,2}(?:[/.]\\d{2,4})?\\b")
    private val PAREN = Regex("[(\\[][^)\\]]{0,30}[)\\]]")
    private val NOISE = Regex("(?iu)(?<![\\p{L}])(?:live!?|now|next|hd|fhd|uhd|sd|direct|en direct|en vivo|ao vivo|dal vivo|u[žz]ivo|ישיר|שידור חי|مباشر|بث مباشر|replay|rerun|premiere|new)(?![\\p{L}])")
    private val SYM = Regex("[^\\p{L}\\p{N}\\s\\-–—:'’.&/]")
    private val SEP = Regex("\\s(?:vs\\.?|v|x|-|–|—|ضد|נגד|против|contro|gegen|contre|tegen)\\s", RegexOption.IGNORE_CASE)
    private val SCORE = Regex("\\s\\d{1,2}\\s*[-–—:]\\s*\\d{1,2}\\s")
    private val FREQ = Regex("\\d{4,5}\\D{0,6}[VvHh]\\D{0,6}\\d{4,5}")
    private val SP = Regex("\\s+")
    private val NONLETTER = Regex("[^\\p{L}]")

    fun norm(s: String): String = NONLETTER.replace(s.lowercase(), "")

    /** Keeps the last (left side) or first (right side) few words of one side of the separator. */
    private fun clean(part: String, left: Boolean): String? {
        var t = part.trim()
        if (left && t.contains(':')) t = t.substringAfterLast(':')
        t = t.trim('-', '–', '—', ':', '.', '&', '/', ' ', '\'', '’')
        val w = t.split(' ').filter { it.isNotEmpty() }
        if (w.isEmpty()) return null
        val k = if (w.size > 5) 5 else w.size
        t = (if (left) w.takeLast(k) else w.take(k)).joinToString(" ")
        var letters = 0
        var digits = 0
        for (ch in t) { if (ch.isLetter()) letters++ else if (ch.isDigit()) digits++ }
        if (letters < 2 || digits > 2 || t.length > 45) return null
        return t
    }

    /**
     * "21:35--23:45 Live! Czech Republic - Spain" -> ("Czech Republic", "Spain").
     * Times, "Live", brackets and symbols are dropped in any language; whether the two names really are
     * teams is decided later by [MatchLookup] (Wikidata), not here.
     */
    fun parse(raw: String, r: Rect): TeamPair? {
        for (line0 in raw.split('\n')) {
            if (line0.length < 5 || FREQ.containsMatchIn(line0)) continue
            var s = RANGE.replace(line0, " ")
            s = TIME.replace(s, " ")
            s = DATE.replace(s, " ")
            s = PAREN.replace(s, " ")
            s = NOISE.replace(s, " ")
            s = SYM.replace(s, " ")
            s = " " + SP.replace(s, " ").trim() + " "
            if (s.length < 7) continue
            val sc = SCORE.find(s)
            val parts: List<String> = if (sc != null) listOf(s.substring(0, sc.range.first), s.substring(sc.range.last + 1)) else SEP.split(s).filter { it.isNotBlank() }
            if (parts.size < 2) continue
            val a = clean(parts[parts.size - 2], true) ?: continue
            val b = clean(parts[parts.size - 1], false) ?: continue
            if (norm(a) == norm(b) || norm(a).isEmpty() || norm(b).isEmpty()) continue
            return TeamPair(a, b, r)
        }
        return null
    }

    /** Shorter alternatives of a side (extra words can stick to it): left drops leading words, right drops trailing ones. */
    fun variants(s: String, left: Boolean): List<String> {
        val w = s.split(' ').filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        for (i in 0..2) {
            if (w.size - i < 1) break
            out.add((if (left) w.drop(i) else w.take(w.size - i)).joinToString(" "))
        }
        return out
    }
}

/** Resolves foreign team names to Arabic names + logos (Wikidata) and finds channel / commentator (kooora data). */
object MatchLookup {
    private val UA = "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
    private val FOOT = Regex("(?i)football|soccer|كرة القدم|كرة قدم|כדורגל|calcio|calcist|fu(ß|ss)ball|voetbal|fudbal|фудбал|футбол|ποδοσφαιρ|futbol|fútbol|futebol|fotboll|fodbold|piłk|fotbal|nogomet|labdarúg|futbal|jalkapallo")

    fun http(url: String, maxBytes: Int = 900000): String? {
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 10000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept-Language", "ar,en;q=0.8")
            if (c.responseCode !in 200..299) { c.disconnect(); return null }
            val out = java.io.ByteArrayOutputStream()
            c.inputStream.use { ins ->
                val buf = ByteArray(16384)
                var total = 0
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    total += n
                    if (total > maxBytes) break
                }
            }
            c.disconnect()
            out.toString("UTF-8")
        } catch (e: Exception) { null }
    }

    fun bitmap(url: String): Bitmap? {
        if (url.isEmpty()) return null
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 10000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", UA)
            val bytes = c.inputStream.use { it.readBytes() }
            c.disconnect()
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            var ss = 1
            while (o.outWidth / (ss * 2) >= 160 && o.outHeight / (ss * 2) >= 160) ss *= 2
            val o2 = BitmapFactory.Options()
            o2.inSampleSize = ss
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o2)
        } catch (e: Exception) { null }
    }

    private fun langsFor(s: String): List<String> {
        var he = false; var cy = false; var ar = false; var el = false
        for (ch in s) {
            val c = ch.code
            if (c in 0x0590..0x05FF) he = true
            else if (c in 0x0400..0x04FF) cy = true
            else if (c in 0x0600..0x06FF) ar = true
            else if (c in 0x0370..0x03FF) el = true
        }
        return when {
            he -> listOf("he", "en")
            cy -> listOf("sr", "ru", "en")
            ar -> listOf("ar")
            el -> listOf("el", "en")
            else -> listOf("en", "it", "de", "fr", "nl")
        }
    }

    class Team(val ar: String, val logoUrl: String)

    private fun enc(t: String): String = URLEncoder.encode(t, "UTF-8")

    private fun search(q: String, lang: String): JSONArray? {
        val r = http("https://www.wikidata.org/w/api.php?action=wbsearchentities&type=item&limit=7&format=json&language=" + lang +
            "&uselang=" + lang + "&search=" + enc(q), 200000) ?: return null
        return try { JSONObject(r).optJSONArray("search") } catch (e: Exception) { null }
    }

    private fun footId(arr: JSONArray?): String? {
        if (arr == null) return null
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (FOOT.containsMatchIn(o.optString("description", ""))) return o.getString("id")
        }
        return null
    }

    private fun englishLabel(id: String): String? {
        val r = http("https://www.wikidata.org/w/api.php?action=wbgetentities&props=labels&languages=en&format=json&ids=" + id, 100000) ?: return null
        return try { JSONObject(r).getJSONObject("entities").getJSONObject(id).getJSONObject("labels").getJSONObject("en").getString("value") } catch (e: Exception) { null }
    }

    private fun entity(id: String, name: String): Team? {
        val e = http("https://www.wikidata.org/w/api.php?action=wbgetentities&props=labels|claims&languages=ar|en&format=json&ids=" + id, 600000) ?: return null
        return try {
            val ent = JSONObject(e).getJSONObject("entities").getJSONObject(id)
            val labels = ent.optJSONObject("labels")
            val ar = labels?.optJSONObject("ar")?.optString("value", "") ?: ""
            val en = labels?.optJSONObject("en")?.optString("value", "") ?: ""
            var logo = ""
            val p154 = ent.optJSONObject("claims")?.optJSONArray("P154")
            if (p154 != null && p154.length() > 0) {
                val f = p154.getJSONObject(0).optJSONObject("mainsnak")?.optJSONObject("datavalue")?.optString("value", "") ?: ""
                if (f.isNotEmpty()) logo = "https://commons.wikimedia.org/wiki/Special:FilePath/" + enc(f).replace("+", "%20") + "?width=160"
            }
            Team(if (ar.isNotEmpty()) ar else if (en.isNotEmpty()) en else name, logo)
        } catch (ex: Exception) { null }
    }

    /**
     * Is [name] (any language) a football club or national team? Wikidata decides:
     * a club is found by its description; a country name is mapped to "<country> national football team".
     */
    fun team(name: String): Team? {
        var firstId: String? = null
        for (lang in langsFor(name)) {
            val arr = search(name, lang) ?: continue
            val id = footId(arr)
            if (id != null) return entity(id, name)
            if (firstId == null && arr.length() > 0) firstId = arr.getJSONObject(0).getString("id")
        }
        if (firstId != null) {
            val en = englishLabel(firstId)
            if (en != null) {
                val id = footId(search(en + " national football team", "en"))
                if (id != null) return entity(id, name)
            }
        }
        return null
    }

    // ---- fuzzy match against kooora's Arabic names
    private val AR_DIAC = Regex("[\\u064B-\\u065F\\u0670]")
    private val STOP = setOf("نادي", "فريق", "منتخب", "ال")

    private fun tokens(s: String): Set<String> {
        var t = AR_DIAC.replace(s, "")
        t = t.replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ى', 'ي').replace('ة', 'ه')
        val out = HashSet<String>()
        for (w in t.split(Regex("[^\\p{L}\\p{N}]+"))) {
            var x = w.lowercase()
            if (x.startsWith("ال") && x.length > 3) x = x.substring(2)
            if (x.length >= 2 && x !in STOP) out.add(x)
        }
        return out
    }

    private fun sim(a: String, b: String): Float {
        val x = tokens(a)
        val y = tokens(b)
        if (x.isEmpty() || y.isEmpty()) return 0f
        var inter = 0
        for (t in x) if (t in y) inter++
        return inter.toFloat() / maxOf(x.size, y.size)
    }

    class KMatch(val h: String, val a: String, val hl: String, val al: String, val comp: String, val ch: List<String>, val url: String, val swapped: Boolean)

    fun kooraFind(ctx: Context, arA: String, arB: String): KMatch? {
        try { MatchesRepo.refresh(ctx, false) { } } catch (e: Exception) { }
        val js = MatchesRepo.cached(ctx)
        if (js.isEmpty()) return null
        val arr: JSONArray = try { JSONObject(js).getJSONArray("m") } catch (e: Exception) { return null }
        var best: KMatch? = null
        var bestScore = 0f
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val h = o.optString("h")
            val a = o.optString("a")
            val s1 = minOf(sim(h, arA), sim(a, arB))
            val s2 = minOf(sim(h, arB), sim(a, arA))
            val s = maxOf(s1, s2)
            if (s >= 0.5f && s > bestScore) {
                bestScore = s
                val chA = o.optJSONArray("ch")
                val ch = ArrayList<String>()
                if (chA != null) for (k in 0 until chA.length()) ch.add(chA.getString(k))
                best = KMatch(h, a, o.optString("hl"), o.optString("al"), o.optString("c"), ch, o.optString("u"), s2 > s1)
            }
        }
        return best
    }

    private val TAG = Regex("<[^>]+>")
    private val SP = Regex("\\s+")
    private val COMM = Regex("(?:المعلق(?:ون)?|معلق)\\s*[:：]?\\s*([\\u0600-\\u06FF]+(?:\\s+[\\u0600-\\u06FF]+){0,3})")

    fun commentator(url: String): String {
        if (url.isEmpty()) return ""
        val h = http(url, 700000) ?: return ""
        val t = SP.replace(TAG.replace(h, " "), " ")
        val m = COMM.find(t) ?: return ""
        return m.groupValues[1].trim()
    }

    /** Full resolution for a pair (blocking, network). Null = the two names are not both football teams. */
    fun resolve(ctx: Context, p: TeamPair, trace: StringBuilder? = null): MatchData? {
        var ta: Team? = null
        for (v in MatchParse.variants(p.a, true)) {
            ta = team(v)
            trace?.append(" [").append(v).append(if (ta != null) "=OK" else "=no").append("]")
            if (ta != null) break
        }
        if (ta == null) return null
        var tb: Team? = null
        for (v in MatchParse.variants(p.b, false)) {
            tb = team(v)
            trace?.append(" [").append(v).append(if (tb != null) "=OK" else "=no").append("]")
            if (tb != null) break
        }
        if (tb == null) return null
        var nameA = ta.ar
        var nameB = tb.ar
        var logoA = ta.logoUrl
        var logoB = tb.logoUrl
        var comp = ""
        var channel = ""
        var comm = ""
        var src = "wikidata"
        val k = kooraFind(ctx, nameA, nameB)
        if (k != null) {
            nameA = if (k.swapped) k.a else k.h
            nameB = if (k.swapped) k.h else k.a
            val la = if (k.swapped) k.al else k.hl
            val lb = if (k.swapped) k.hl else k.al
            if (la.isNotEmpty()) logoA = la
            if (lb.isNotEmpty()) logoB = lb
            comp = k.comp
            channel = k.ch.firstOrNull() ?: ""
            comm = commentator(k.url)
            src += "+kooora"
        }
        return MatchData(nameA, nameB, bitmap(logoA), bitmap(logoB), comp, channel, comm, src)
    }
}

/**
 * Small card (white, dark text, coloured crests) that announces the match shown on screen for ~10 s.
 * The position / size is saved; long-press it (or Settings -> Edit) to move and resize.
 */
class MatchCard(private val svc: KeyService) {
    private val ctx: Context = svc
    private val ui = Handler(Looper.getMainLooper())
    private var view: MatchView? = null
    private var attached = false
    private var rect = IntArray(4)
    private var sw = 1920
    private var sh = 1080
    @Volatile var editing = false
    private var sizeMode = false
    private val hideRun = Runnable { if (!editing) hide() }
    private val stopEditRun = Runnable { if (editing) stopEdit() }

    private fun loadRect() {
        val dm = DisplayMetrics()
        (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        sw = dm.widthPixels
        sh = dm.heightPixels
        val f = Prefs.matchRect(ctx)
        rect = intArrayOf((f[0] * sw).toInt(), (f[1] * sh).toInt(), ((f[0] + f[2]) * sw).toInt(), ((f[1] + f[3]) * sh).toInt())
        clamp()
    }

    private fun clamp() {
        val minW = (sw * 0.10f).toInt()
        val minH = (sh * 0.10f).toInt()
        if (rect[2] - rect[0] < minW) rect[2] = rect[0] + minW
        if (rect[3] - rect[1] < minH) rect[3] = rect[1] + minH
        if (rect[0] < 0) rect[0] = 0
        if (rect[1] < 0) rect[1] = 0
        if (rect[2] > sw) rect[2] = sw
        if (rect[3] > sh) rect[3] = sh
    }

    private fun save() {
        Prefs.setMatchRect(ctx, floatArrayOf(rect[0] / sw.toFloat(), rect[1] / sh.toFloat(),
            (rect[2] - rect[0]) / sw.toFloat(), (rect[3] - rect[1]) / sh.toFloat()))
    }

    fun reset() {
        Prefs.resetMatchRect(ctx)
        loadRect()
        relayout()
    }

    /** Shows [d] for [ms] milliseconds. */
    fun show(d: MatchData, ms: Long) {
        val m = svc.mode
        if (m == "full" || m == "standby" || m == "prompt") return
        loadRect()
        val v = view ?: MatchView(ctx).also { it.host = this; view = it }
        v.data = d
        attach()
        ui.removeCallbacks(hideRun)
        ui.postDelayed(hideRun, ms)
    }

    fun hide() {
        ui.removeCallbacks(hideRun)
        if (editing) return
        detach()
    }

    fun onMode(m: String) {
        if (m == "full" || m == "standby" || m == "prompt") {
            if (editing) { editing = false; view?.setEditing(false, false) }
            detach()
        }
    }

    fun shutdown() {
        editing = false
        ui.removeCallbacks(hideRun)
        detach()
    }

    private fun params(): WindowManager.LayoutParams {
        val p = WindowManager.LayoutParams(
            rect[2] - rect[0], rect[3] - rect[1],
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = rect[0]
        p.y = rect[1]
        return p
    }

    private fun attach() {
        val v = view ?: return
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        try {
            if (attached) wm.updateViewLayout(v, params()) else { wm.addView(v, params()); attached = true }
            v.invalidate()
        } catch (e: Exception) {
            attached = false
        }
    }

    private fun relayout() {
        if (!attached) return
        val v = view ?: return
        try { (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).updateViewLayout(v, params()) } catch (e: Exception) { }
    }

    private fun detach() {
        val v = view ?: return
        if (!attached) return
        try { (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) } catch (e: Exception) { }
        attached = false
    }

    // ---- edit mode
    fun toggleEdit() { if (editing) stopEdit() else startEdit() }

    fun startEdit() {
        loadRect()
        val v = view ?: MatchView(ctx).also { it.host = this; view = it }
        if (v.data == null) {
            v.data = MatchData("الفريق الأول", "الفريق الثاني", null, null, "", "القناة الناقلة", "المعلق", "sample")
        }
        editing = true
        sizeMode = false
        v.setEditing(true, false)
        attach()
        postEditTimeout()
    }

    fun stopEdit() {
        editing = false
        ui.removeCallbacks(stopEditRun)
        view?.setEditing(false, false)
        save()
        ui.removeCallbacks(hideRun)
        ui.postDelayed(hideRun, 2500L)
    }

    private fun postEditTimeout() {
        if (!editing) return
        ui.removeCallbacks(stopEditRun)
        ui.postDelayed(stopEditRun, 25000L)
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
        relayout()
        postEditTimeout()
    }

    fun commit() { save() }

    /** Remote: arrows move/resize (resize moves the right and bottom edges), OK switches, Back saves. */
    fun onEditKey(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_UP) {
            if (e.keyCode == KeyEvent.KEYCODE_BACK || e.keyCode == KeyEvent.KEYCODE_ESCAPE) stopEdit()
            return true
        }
        val step = maxOf(3, (sw * 0.004f * (1 + minOf(e.repeatCount, 18) / 3)).toInt())
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (e.repeatCount == 0) {
                sizeMode = !sizeMode
                view?.setEditing(true, sizeMode)
                postEditTimeout()
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> { dragBy(-step, 0, sizeMode); save() }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { dragBy(step, 0, sizeMode); save() }
            KeyEvent.KEYCODE_DPAD_UP -> { dragBy(0, -step, sizeMode); save() }
            KeyEvent.KEYCODE_DPAD_DOWN -> { dragBy(0, step, sizeMode); save() }
        }
        return true
    }
}

class MatchView(context: Context) : View(context) {
    var host: MatchCard? = null
    var data: MatchData? = null
    private var editing = false
    private var sizeMode = false
    private var lx = 0f
    private var ly = 0f
    private var sx = 0f
    private var sy = 0f
    private var moved = false
    private var resizing = false
    private val density = context.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val clip = Path()
    private val longRun = Runnable { host?.toggleEdit() }

    fun setEditing(on: Boolean, size: Boolean) { editing = on; sizeMode = size; invalidate() }

    private fun handleSize(): Float = maxOf(28f * density, minOf(width, height) * 0.2f)

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lx = ev.rawX; ly = ev.rawY; sx = lx; sy = ly
                moved = false
                val hs = handleSize()
                resizing = editing && ev.x > width - hs && ev.y > height - hs
                postDelayed(longRun, 650L)
            }
            MotionEvent.ACTION_MOVE -> {
                if (Math.abs(ev.rawX - sx) > 16f * density || Math.abs(ev.rawY - sy) > 16f * density) {
                    moved = true
                    removeCallbacks(longRun)
                }
                if (editing && moved) {
                    val dx = (ev.rawX - lx).toInt()
                    val dy = (ev.rawY - ly).toInt()
                    if (dx != 0 || dy != 0) { host?.dragBy(dx, dy, resizing); lx = ev.rawX; ly = ev.rawY }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longRun)
                if (editing && moved) host?.commit()
            }
        }
        return true
    }

    private fun fit(text: String, maxW: Float, size: Float) {
        p.textSize = size
        val w = p.measureText(text)
        if (w > maxW && w > 0f) p.textSize = size * maxW / w
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w < 2f || h < 2f) return
        val d = data ?: return
        val r = minOf(w, h) * 0.12f
        clip.reset()
        clip.addRoundRect(RectF(0f, 0f, w, h), r, r, Path.Direction.CW)
        c.save()
        c.clipPath(clip)
        p.style = Paint.Style.FILL
        p.color = 0xFFFFFFFF.toInt()
        c.drawRect(0f, 0f, w, h, p)
        val pad = w * 0.06f
        p.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        p.textAlign = Paint.Align.RIGHT
        // top line: commentator + channel
        var y = h * 0.14f
        val top = ArrayList<String>()
        if (d.commentator.isNotEmpty()) top.add("المعلق: " + d.commentator)
        if (d.channel.isNotEmpty()) top.add(d.channel)
        p.color = 0xFF3A3A3C.toInt()
        if (top.isNotEmpty()) {
            fit(top.joinToString(" · "), w - pad * 2, h * 0.095f)
            c.drawText(top.joinToString(" · "), w - pad, y, p)
        }
        // divider
        p.color = 0x22000000
        c.drawRect(pad, h * 0.2f, w - pad, h * 0.2f + 2f * density, p)
        // two team rows
        val rowH = (h - h * 0.24f) / 2f
        for (i in 0..1) {
            val name = if (i == 0) d.homeAr else d.awayAr
            val logo = if (i == 0) d.homeLogo else d.awayLogo
            val cy = h * 0.22f + rowH * i + rowH / 2f
            val ls = rowH * 0.78f
            var textRight = w - pad
            if (logo != null) {
                c.drawBitmap(logo, null, RectF(w - pad - ls, cy - ls / 2f, w - pad, cy + ls / 2f), p)
                textRight = w - pad - ls - pad * 0.6f
            }
            p.color = 0xFF1C1C1E.toInt()
            fit(name, textRight - pad, rowH * 0.46f)
            c.drawText(name, textRight, cy - (p.descent() + p.ascent()) / 2f, p)
        }
        c.restore()
        if (editing) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2.5f * density
            p.color = 0xFF000000.toInt()
            p.pathEffect = DashPathEffect(floatArrayOf(10f * density, 6f * density), 0f)
            c.drawRect(2f * density, 2f * density, w - 2f * density, h - 2f * density, p)
            p.pathEffect = null
            p.style = Paint.Style.FILL
            p.color = if (sizeMode) 0xFFFF9F0A.toInt() else 0xFF30D5C8.toInt()
            val hs = handleSize()
            val tri = Path()
            tri.moveTo(w, h - hs); tri.lineTo(w, h); tri.lineTo(w - hs, h); tri.close()
            c.drawPath(tri, p)
        }
    }
}

/**
 * Watches the same on-screen text the logo cover reads. When a match line appears (only trusted
 * when a satellite-frequency line is on screen too), resolves it once and shows the card.
 */
class MatchWatcher(private val svc: KeyService) {
    private val ctx: Context = svc
    private val ui = Handler(Looper.getMainLooper())
    val card = MatchCard(svc)
    @Volatile var enabled = false
    private var present = false
    private var lastKey = ""
    private var anchor: FloatArray? = Prefs.matchAnchor(svc)
    private val cache = object : LinkedHashMap<String, MatchData>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MatchData>?): Boolean = size > 12
    }
    @Volatile private var busyKey = ""

    fun configure() {
        enabled = Prefs.matchOn(ctx)
        if (!enabled) { present = false; lastKey = ""; ui.post { card.shutdown() } }
    }

    private val neg: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Called on the scan thread with this scan's texts. */
    fun feed(texts: List<TextHit>, hasFreq: Boolean, sw: Int, sh: Int) {
        if (!enabled) return
        var found: TeamPair? = null
        if (hasFreq) {
            val ar = anchor
            val anchorR = if (ar != null && sw > 0) Rect((ar[0] * sw).toInt(), (ar[1] * sh).toInt(), (ar[2] * sw).toInt(), (ar[3] * sh).toInt()) else null
            anchorR?.inset(-anchorR.height(), -anchorR.height())
            var any: TeamPair? = null
            for (t in texts) {
                val p = MatchParse.parse(t.t, t.r) ?: continue
                if (neg.contains(p.key)) continue
                if (anchorR != null && Rect.intersects(anchorR, t.r)) { found = p; break }
                if (any == null) any = p
            }
            if (found == null) found = any
        }
        if (found == null) { present = false; return }
        val f = found
        if (present && f.key == lastKey) return      // same info bar still on screen
        val cached = synchronized(cache) { cache[f.key] }
        if (cached != null) {
            present = true
            lastKey = f.key
            rememberSpot(f, sw, sh)
            ui.post { card.show(cached, 10000L) }
            return
        }
        if (busyKey.isNotEmpty()) return              // one lookup at a time
        busyKey = f.key
        Thread {
            val d = try { MatchLookup.resolve(ctx, f) } catch (e: Exception) { null }
            busyKey = ""
            if (d == null) {
                neg.add(f.key)                        // not two football teams (or no network): don't ask again
            } else {
                synchronized(cache) { cache[f.key] = d }
                present = true
                lastKey = f.key
                rememberSpot(f, sw, sh)
                ui.post { card.show(d, 10000L) }
            }
        }.start()
    }

    private fun rememberSpot(f: TeamPair, sw: Int, sh: Int) {
        if (sw > 0 && sh > 0 && f.r.width() > 0) {
            val a = floatArrayOf(f.r.left / sw.toFloat(), f.r.top / sh.toFloat(), f.r.right / sw.toFloat(), f.r.bottom / sh.toFloat())
            anchor = a
            Prefs.setMatchAnchor(ctx, a)
        }
    }

    /** Settings "Match test": what was read, how it was cleaned, and what the lookup said. */
    fun test(texts: List<TextHit>, hasFreq: Boolean): String {
        val sb = StringBuilder()
        sb.append("freq-line=").append(hasFreq).append(" texts=").append(texts.size)
        val seen = HashSet<String>()
        val pairs = ArrayList<TeamPair>()
        for (t in texts) {
            val p = MatchParse.parse(t.t, t.r) ?: continue
            if (seen.add(p.key)) pairs.add(p)
        }
        if (pairs.isEmpty()) {
            sb.append(" | no separator line found. samples: ")
            sb.append(texts.filter { it.t.length in 8..90 }.take(8).joinToString(" ; ") { it.t.replace('\n', ' ') })
            return sb.toString()
        }
        for (p in pairs.take(3)) {
            sb.append(" | candidate: ").append(p.a).append(" / ").append(p.b)
            val tr = StringBuilder()
            val d = try { MatchLookup.resolve(ctx, p, tr) } catch (e: Exception) { null }
            sb.append(tr)
            if (d == null) { sb.append(" -> not football teams"); continue }
            sb.append(" -> ").append(d.homeAr).append(" × ").append(d.awayAr)
            sb.append(" logos=").append(if (d.homeLogo != null) "1" else "0").append(if (d.awayLogo != null) "1" else "0")
            sb.append(" ch=").append(d.channel).append(" comm=").append(d.commentator).append(" src=").append(d.src)
            ui.post { card.show(d, 10000L) }
            break
        }
        return sb.toString()
    }
}
