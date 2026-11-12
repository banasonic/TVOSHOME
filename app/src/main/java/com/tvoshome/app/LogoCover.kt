package com.tvoshome.app

import android.content.Context
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
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.text.format.DateFormat
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.accessibilityservice.AccessibilityService
import java.util.ArrayDeque
import java.util.Calendar
import java.util.Random

/** One finding of [FreqScan]. */
class FreqHit(val kind: Int, val r: Rect)

/** Looks for the satellite parameters text "11258 V 30000" (symbol rate +-4) in the text of every window. */
object FreqScan {
    private val RX = Regex("(\\d{4,5})\\D{0,6}([VvHh])\\D{0,6}(\\d{4,5})")
    private val UNITS = Regex("(?i)(mhz|msym/s|ksym/s|msps|ksps|ms/s|ks/s|sym/s|sr|symbol rate|freq(uency)?|pol)")
    private val VERT = Regex("(?i)vertical|عمودي|رأسي")
    private val HORZ = Regex("(?i)horizontal|أفقي")
    const val NONE = 0
    const val OTHER = 1     // some other frequency line
    const val TARGET = 2    // 11258 V 30000 (+-4)

    fun classify(raw: CharSequence?): Int {
        if (raw == null || raw.length < 9 || raw.length > 400) return NONE
        var t = raw.toString()
        t = VERT.replace(t, " V ")
        t = HORZ.replace(t, " H ")
        t = UNITS.replace(t, " ")
        var res = NONE
        for (m in RX.findAll(t)) {
            val f = m.groupValues[1].toInt()
            val p = m.groupValues[2]
            val s = m.groupValues[3].toInt()
            if (f == 11258 && (p == "V" || p == "v") && Math.abs(s - 30000) <= 4) return TARGET
            if (f in 3000..14000 && s in 1000..60000) res = OTHER
        }
        return res
    }

    private fun free(n: AccessibilityNodeInfo) {
        try { n.recycle() } catch (e: Exception) { }
    }

    private fun roots(svc: AccessibilityService): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        try {
            val ws = svc.windows
            if (ws != null) for (w in ws) {
                try {
                    if (w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY || w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
                    val r = w.root ?: continue
                    if (r.packageName?.toString() == svc.packageName) { free(r); continue }
                    out.add(r)
                } catch (e: Exception) { }
            }
        } catch (e: Exception) { }
        if (out.isEmpty()) {
            try {
                val r = svc.rootInActiveWindow
                if (r != null && r.packageName?.toString() != svc.packageName) out.add(r) else if (r != null) free(r)
            } catch (e: Exception) { }
        }
        return out
    }

    /** Walks every window (max ~4000 nodes) and returns what it found, with the screen position of each hit. */
    fun scan(svc: AccessibilityService, hits: ArrayList<FreqHit>, texts: ArrayList<TextHit>? = null) {
        var count = 0
        for (root in roots(svc)) {
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            stack.add(root)
            while (!stack.isEmpty()) {
                val n = stack.removeLast()
                if (count >= 4000) {
                    free(n)
                    continue
                }
                count++
                var r = classify(n.text)
                if (r < TARGET) {
                    val d = classify(n.contentDescription)
                    if (d > r) r = d
                }
                val cc = n.childCount
                val sb = if (cc in 2..6) StringBuilder() else null
                for (i in 0 until cc) {
                    val c = n.getChild(i) ?: continue
                    if (sb != null) {
                        val ct = c.text
                        if (ct != null && ct.isNotEmpty()) sb.append(ct).append(' ')
                    }
                    stack.add(c)
                }
                if (sb != null && sb.length > 8 && r < TARGET) {
                    val j = classify(sb)
                    if (j > r) r = j
                }
                val wantText = texts != null && texts.size < 250
                if ((r > NONE && hits.size < 12) || wantText) {
                    val b = Rect()
                    n.getBoundsInScreen(b)
                    if (r > NONE && hits.size < 12) hits.add(FreqHit(r, b))
                    if (wantText && texts != null) {
                        val tt = n.text
                        if (tt != null && tt.length in 5..160) texts.add(TextHit(tt.toString(), b))
                        if (sb != null && sb.length in 5..160) texts.add(TextHit(sb.toString().trim(), b))
                    }
                }
                free(n)
                if (r == TARGET && texts == null) {
                    while (!stack.isEmpty()) free(stack.removeLast())
                    return
                }
            }
        }
    }

    /** What the service can see: windows, node/text counts and any frequency-like text. */
    fun diag(svc: AccessibilityService): String {
        val sb = StringBuilder()
        var wins = 0
        var nodes = 0
        var texts = 0
        val seen = ArrayList<String>()
        val pk = ArrayList<String>()
        for (root in roots(svc)) {
            wins++
            pk.add(root.packageName?.toString() ?: "?")
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            stack.add(root)
            while (!stack.isEmpty()) {
                val n = stack.removeLast()
                if (nodes >= 4000) { free(n); continue }
                nodes++
                val t = n.text?.toString() ?: n.contentDescription?.toString()
                if (!t.isNullOrEmpty()) {
                    texts++
                    if (seen.size < 6 && t.length < 120 && Regex("\\d{4,5}").containsMatchIn(t) && Regex("(?i)[vh]").containsMatchIn(t)) seen.add(t.replace('\n', ' '))
                }
                for (i in 0 until n.childCount) {
                    val c = n.getChild(i) ?: continue
                    stack.add(c)
                }
                free(n)
            }
        }
        val hits = ArrayList<FreqHit>()
        scan(svc, hits)
        sb.append("windows=").append(wins).append(" [").append(pk.joinToString(",")).append("] nodes=").append(nodes).append(" texts=").append(texts)
        sb.append(" | match=").append(if (hits.any { it.kind == TARGET }) "FOUND" else if (hits.isNotEmpty()) "other-freq" else "none")
        if (seen.isNotEmpty()) sb.append(" | samples: ").append(seen.joinToString(" ; "))
        return sb.toString()
    }
}

/**
 * Optional cover for the channel logo (top-right corner).
 *
 * The accessibility service reports window changes; a throttled background scan (at most every
 * ~0.7 s, and only while a change happened) reads the on-screen text. When "11258 V 30000" shows
 * up anywhere, the cover (Connect logo / analog clock / digital clock) is drawn at the saved
 * position and size. It goes away when another frequency line shows up or the app changes.
 * Long press on the cover (or Settings -> Edit) lets you move and resize it; every change is saved.
 */
class LogoCover(private val svc: KeyService) {
    private val ctx: Context = svc
    private var lastErr = ""
    private val ui = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var worker: Handler? = null

    @Volatile private var enabled = false
    @Volatile private var wantLogo = false
    @Volatile private var scanPending = false
    private var lastScan = 0L
    private var style = 0

    private var view: CoverView? = null
    private var attached = false
    private var active = false
    private var preview = false
    @Volatile var editing = false
    private var sizeMode = false
    private var anchor: FloatArray? = null
    private var lastDur = 0L
    private var rect = IntArray(4)
    private var sw = 1920
    private var sh = 1080

    private val stopEditRun = Runnable { if (editing) stopEdit() }

    fun configure() {
        wantLogo = Prefs.logoOn(ctx)
        enabled = wantLogo || Prefs.matchOn(ctx)
        style = Prefs.logoStyle(ctx)
        if (!wantLogo) {
            if (editing) stopEdit()
            hideCover()
        }
        if (!enabled) {
            stopWorker()
            if (editing) stopEdit()
            hideCover()
            return
        }
        if (thread == null) {
            val t = HandlerThread("logo-scan", Process.THREAD_PRIORITY_BACKGROUND)
            t.start()
            thread = t
            worker = Handler(t.looper)
            worker?.postDelayed(pollRun, 1500L)
        }
        val dm = DisplayMetrics()
        (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        sw = dm.widthPixels
        sh = dm.heightPixels
        anchor = Prefs.logoAnchor(ctx)
        view?.setStyle(style)
        requestScan()
    }

    fun shutdown() {
        enabled = false
        stopWorker()
        editing = false
        hideCover()
    }

    private fun stopWorker() {
        thread?.quitSafely()
        thread = null
        worker = null
        scanPending = false
    }

    // ------------------------------------------------------------------ scanning

    private fun ignored(pkg: String): Boolean =
        pkg == ctx.packageName || pkg.contains("inputmethod") || pkg.contains("keyboard")

    private val pollRun = object : Runnable {
        override fun run() {
            if (!enabled) return
            requestScan()
            worker?.postDelayed(this, 2000L)
        }
    }

    /** Any change in any window (the info bar is often a separate window). */
    fun onContent(pkg: String?) {
        if (!enabled || (pkg != null && ignored(pkg))) return
        requestScan()
    }

    fun onWindow(pkg: String?) {
        if (!enabled || pkg == null || ignored(pkg)) return
        if (active && !preview && !editing && svc.isHome(pkg)) hideCover()
        requestScan()
    }

    private fun requestScan() {
        val w = worker ?: return
        if (scanPending) return
        scanPending = true
        val wait = maxOf(0L, maxOf(500L, lastDur * 3L) - (SystemClock.elapsedRealtime() - lastScan))
        w.postDelayed({
            scanPending = false
            val t0 = SystemClock.elapsedRealtime()
            lastScan = t0
            doScan()
            lastDur = SystemClock.elapsedRealtime() - t0
        }, wait)
    }

    private fun anchorRect(): Rect? {
        val a = anchor ?: return null
        return Rect((a[0] * sw).toInt(), (a[1] * sh).toInt(), (a[2] * sw).toInt(), (a[3] * sh).toInt())
    }

    private fun doScan() {
        if (!enabled || editing) return
        val m = svc.mode
        if (m == "full" || m == "standby" || m == "prompt") return
        val hits = ArrayList<FreqHit>()
        val texts: ArrayList<TextHit>? = if (svc.matchOn()) ArrayList() else null
        try { FreqScan.scan(svc, hits, texts) } catch (e: Exception) { }
        if (texts != null) svc.matchFeed(texts, hits.isNotEmpty(), sw, sh)
        if (wantLogo && hits.isNotEmpty()) ui.post { handle(hits) }
    }

    private fun handle(hits: ArrayList<FreqHit>) {
        if (!wantLogo || editing) return
        var target: FreqHit? = null
        for (h in hits) if (h.kind == FreqScan.TARGET) { target = h; break }
        if (target != null) {
            // remember where the frequency text lives (info bar / list) so later changes there are noticed
            val r = target.r
            if (sw > 0 && sh > 0 && r.width() > 0 && r.height() > 0) {
                val a = floatArrayOf(r.left / sw.toFloat(), r.top / sh.toFloat(), r.right / sw.toFloat(), r.bottom / sh.toFloat())
                anchor = a
                Prefs.setLogoAnchor(ctx, a)
            }
            if (!active) {
                preview = false
                showCover()
            }
            return
        }
        // another frequency in the same place the target was seen = channels changed -> remove the cover
        if (active && !preview) {
            val ar = anchorRect() ?: return
            val grow = maxOf(ar.width(), ar.height()) / 4
            ar.inset(-grow, -grow)
            for (h in hits) {
                if (h.kind == FreqScan.OTHER && Rect.intersects(ar, h.r)) {
                    hideCover()
                    return
                }
            }
        }
    }

    fun onMode(m: String) {
        if (!active) return
        if (editing) return
        if (m == "full" || m == "standby" || m == "prompt") detachView() else attachView()
    }

    // ------------------------------------------------------------------ geometry

    private fun loadRect() {
        val dm = DisplayMetrics()
        (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        sw = dm.widthPixels
        sh = dm.heightPixels
        val f = Prefs.logoRect(ctx)
        rect = intArrayOf((f[0] * sw).toInt(), (f[1] * sh).toInt(), ((f[0] + f[2]) * sw).toInt(), ((f[1] + f[3]) * sh).toInt())
        clampRect()
    }

    private fun clampRect() {
        val minW = (sw * 0.04f).toInt()
        val minH = (sh * 0.04f).toInt()
        if (rect[2] - rect[0] < minW) rect[0] = rect[2] - minW
        if (rect[3] - rect[1] < minH) rect[3] = rect[1] + minH
        if (rect[0] < 0) rect[0] = 0
        if (rect[1] < 0) rect[1] = 0
        if (rect[2] > sw) rect[2] = sw
        if (rect[3] > sh) rect[3] = sh
    }

    private fun saveRect() {
        Prefs.setLogoRect(ctx, floatArrayOf(rect[0] / sw.toFloat(), rect[1] / sh.toFloat(),
            (rect[2] - rect[0]) / sw.toFloat(), (rect[3] - rect[1]) / sh.toFloat()))
    }

    fun resetRect() {
        Prefs.resetLogoRect(ctx)
        loadRect()
        layoutCover()
    }

    /** Move (resize=false) or resize (resize=true: the left and bottom edges move) by a pixel delta. */
    fun dragBy(dx: Int, dy: Int, resize: Boolean) {
        if (resize) {
            rect[0] += dx
            rect[3] += dy
        } else {
            val w = rect[2] - rect[0]
            val h = rect[3] - rect[1]
            val nx = (rect[0] + dx).coerceIn(0, maxOf(0, sw - w))
            val ny = (rect[1] + dy).coerceIn(0, maxOf(0, sh - h))
            rect[0] = nx
            rect[1] = ny
            rect[2] = nx + w
            rect[3] = ny + h
        }
        clampRect()
        layoutCover()
        postEditTimeout()
    }

    fun commit() {
        saveRect()
    }

    // ------------------------------------------------------------------ edit mode

    fun toggleEdit() {
        if (editing) stopEdit() else startEdit()
    }

    fun startEdit() {
        if (!wantLogo || editing) return
        loadRect()
        if (!active) {
            preview = true
            active = true
        }
        ensureView()
        editing = true
        sizeMode = false
        view?.setEditing(true, false)
        attachView()
        postEditTimeout()
    }

    fun stopEdit() {
        editing = false
        ui.removeCallbacks(stopEditRun)
        view?.setEditing(false, false)
        saveRect()
        if (preview) {
            preview = false
            hideCover()
        } else {
            val m = svc.mode
            if (m == "full" || m == "standby" || m == "prompt") detachView() else layoutCover()
        }
    }

    private fun postEditTimeout() {
        if (!editing) return
        ui.removeCallbacks(stopEditRun)
        ui.postDelayed(stopEditRun, 25000L)
    }

    /** Remote control while editing: arrows move / resize, OK switches move<->size, BACK finishes. */
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
            KeyEvent.KEYCODE_DPAD_LEFT -> { dragBy(-step, 0, sizeMode); saveRect() }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { dragBy(step, 0, sizeMode); saveRect() }
            KeyEvent.KEYCODE_DPAD_UP -> { dragBy(0, -step, sizeMode); saveRect() }
            KeyEvent.KEYCODE_DPAD_DOWN -> { dragBy(0, step, sizeMode); saveRect() }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> { }
        }
        return true
    }

    // ------------------------------------------------------------------ cover window (UI thread)

    private fun ensureView() {
        if (view == null) {
            val v = CoverView(ctx, style)
            v.host = this
            view = v
        }
        view?.setStyle(style)
    }

    private fun showCover() {
        loadRect()
        active = true
        ensureView()
        val m = svc.mode
        if (m == "full" || m == "standby" || m == "prompt") return
        attachView()
    }

    private fun params(): WindowManager.LayoutParams {
        val p = WindowManager.LayoutParams(
            rect[2] - rect[0],
            rect[3] - rect[1],
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = rect[0]
        p.y = rect[1]
        return p
    }

    private fun attachView() {
        val v = view ?: return
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        try {
            if (attached) wm.updateViewLayout(v, params()) else {
                wm.addView(v, params())
                attached = true
            }
            v.start()
        } catch (e: Exception) {
            attached = false
            lastErr = e.toString()
            android.util.Log.d("LogoCover", "addView failed: " + e)
        }
    }

    /** For the Test report. */
    fun state(): String = "cover: on=" + enabled + " active=" + active + " attached=" + attached +
        " mode=" + svc.mode + " rect=" + rect.joinToString(",") + (if (lastErr.isNotEmpty()) " err=" + lastErr else "")

    private fun layoutCover() {
        if (!attached) return
        val v = view ?: return
        try {
            (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).updateViewLayout(v, params())
        } catch (e: Exception) {
        }
    }

    private fun detachView() {
        val v = view ?: return
        v.stop()
        if (!attached) return
        try {
            (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (e: Exception) {
        }
        attached = false
    }

    fun hideCover() {
        active = false
        preview = false
        detachView()
    }
}

/** The drawn cover: Connect logo with random lively effects, or an analog / digital clock. */
class CoverView(context: Context, private var style: Int) : View(context) {
    var host: LogoCover? = null
    private var editing = false
    private var sizeMode = false
    private var lx = 0f
    private var ly = 0f
    private var sx = 0f
    private var sy = 0f
    private var moved = false
    private var resizing = false
    private val density = context.resources.displayMetrics.density
    private val editPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val longRun = Runnable { host?.toggleEdit() }

    fun setEditing(on: Boolean, size: Boolean) {
        editing = on
        sizeMode = size
        invalidate()
    }

    private fun handleSize(): Float = maxOf(28f * density, minOf(width, height) * 0.22f)

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lx = ev.rawX; ly = ev.rawY; sx = lx; sy = ly
                moved = false
                val hs = handleSize()
                resizing = editing && ev.x < hs && ev.y > height - hs
                postDelayed(longRun, 650L)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (Math.abs(ev.rawX - sx) > 16f * density || Math.abs(ev.rawY - sy) > 16f * density) {
                    moved = true
                    removeCallbacks(longRun)
                }
                if (editing && moved) {
                    val dx = (ev.rawX - lx).toInt()
                    val dy = (ev.rawY - ly).toInt()
                    if (dx != 0 || dy != 0) {
                        host?.dragBy(dx, dy, resizing)
                        lx = ev.rawX
                        ly = ev.rawY
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longRun)
                if (editing && moved) host?.commit()
                return true
            }
        }
        return true
    }

    private fun drawEditOverlay(c: Canvas, w: Float, h: Float) {
        editPaint.style = Paint.Style.STROKE
        editPaint.strokeWidth = 2.5f * density
        editPaint.color = Color.WHITE
        editPaint.pathEffect = DashPathEffect(floatArrayOf(10f * density, 6f * density), 0f)
        c.drawRect(2f * density, 2f * density, w - 2f * density, h - 2f * density, editPaint)
        editPaint.pathEffect = null
        editPaint.style = Paint.Style.FILL
        editPaint.color = if (sizeMode) 0xFFFF9F0A.toInt() else 0xFF30D5C8.toInt()
        val hs = handleSize()
        val p = Path()
        p.moveTo(0f, h - hs)
        p.lineTo(hs, h)
        p.lineTo(0f, h)
        p.close()
        c.drawPath(p, editPaint)
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val icon: Bitmap? = try {
        context.assets.open("logo_cover/connect.png").use { BitmapFactory.decodeStream(it) }
    } catch (e: Exception) { null }
    private var plate: Bitmap? = null
    private val rnd = Random()
    private var running = false
    private var fx = 0                 // 0 none, 1 sheen, 2 pulse, 3 wiggle, 4 flip
    private var fxStart = 0L
    private var fxDur = 1L
    private var lastFx = 0
    private val clip = Path()
    private val is24 = DateFormat.is24HourFormat(context)

    private val animRun = object : Runnable {
        override fun run() {
            if (!running) return
            invalidate()
            if (fx != 0) postOnAnimation(this)
        }
    }
    private val fxRun = Runnable {
        if (!running) return@Runnable
        var n = 1 + rnd.nextInt(4)
        if (n == lastFx) n = 1 + (n % 4)
        lastFx = n
        fx = n
        fxStart = SystemClock.uptimeMillis()
        fxDur = when (n) { 1 -> 1000L; 2 -> 650L; 3 -> 700L; else -> 800L }
        postOnAnimation(animRun)
    }
    private val clockRun = object : Runnable {
        override fun run() {
            if (!running) return
            invalidate()
            postDelayed(this, if (style == 1) 1000L else 15000L)
        }
    }

    fun setStyle(s: Int) {
        if (s == style) return
        style = s
        if (running) {
            stop(); start()
        }
        invalidate()
    }

    fun start() {
        if (running) return
        running = true
        fx = 0
        if (style == 0) scheduleFx(1500L + rnd.nextInt(2000)) else postDelayed(clockRun, if (style == 1) 1000L else 15000L)
        invalidate()
    }

    fun stop() {
        running = false
        removeCallbacks(fxRun)
        removeCallbacks(animRun)
        removeCallbacks(clockRun)
        fx = 0
    }

    private fun scheduleFx(delay: Long) {
        removeCallbacks(fxRun)
        postDelayed(fxRun, delay)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w < 2f || h < 2f) return
        val r = minOf(w, h) * 0.18f
        clip.reset()
        clip.addRoundRect(RectF(0f, 0f, w, h), r, r, Path.Direction.CW)
        c.save()
        c.clipPath(clip)
        when (style) {
            1 -> drawAnalog(c, w, h)
            2 -> drawDigital(c, w, h)
            else -> drawConnect(c, w, h)
        }
        c.restore()
        if (editing) drawEditOverlay(c, w, h)
    }

    private fun progress(): Float {
        if (fx == 0) return 0f
        val t = (SystemClock.uptimeMillis() - fxStart).toFloat() / fxDur
        if (t >= 1f) {
            fx = 0
            scheduleFx(3500L + rnd.nextInt(4500))
            return 0f
        }
        return t
    }

    private fun drawConnect(c: Canvas, w: Float, h: Float) {
        val ic = icon
        if (ic == null) {
            c.drawColor(0xFF5B2A86.toInt())
            return
        }
        var p = plate
        if (p == null) {
            // vertical colour strip taken from the icon's own edge so the banner extends it seamlessly
            val col = IntArray(ic.height)
            val x = maxOf(1, (ic.width * 0.04f).toInt())
            val lo = (ic.height * 0.16f).toInt()
            val hi = (ic.height * 0.84f).toInt()
            for (y in 0 until ic.height) col[y] = ic.getPixel(x, y.coerceIn(lo, hi)) or 0xFF000000.toInt()
            p = Bitmap.createBitmap(col, 1, ic.height, Bitmap.Config.ARGB_8888)
            plate = p
        }
        c.drawBitmap(p, null, RectF(0f, 0f, w, h), paint)
        val t = progress()
        val s = minOf(w, h)
        var scale = 1f
        var rot = 0f
        var sx = 1f
        when (fx) {
            2 -> scale = 1f + 0.07f * Math.sin(Math.PI * t).toFloat()
            3 -> rot = 5f * Math.sin(2.0 * Math.PI * t).toFloat() * (1f - t)
            4 -> sx = Math.abs(Math.cos(Math.PI * t)).toFloat().coerceAtLeast(0.04f)
        }
        c.save()
        c.translate(w / 2f, h / 2f)
        c.rotate(rot)
        c.scale(scale * sx, scale)
        c.drawBitmap(ic, null, RectF(-s / 2f, -s / 2f, s / 2f, s / 2f), paint)
        c.restore()
        if (fx == 1) {
            val bw = w * 0.35f
            val pos = -bw + (w + bw * 2f) * t
            val sh = Paint(Paint.ANTI_ALIAS_FLAG)
            sh.shader = LinearGradient(pos, 0f, pos + bw, h * 0.6f,
                intArrayOf(0x00FFFFFF, 0x66FFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, sh)
        }
    }

    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }
    private val DAYS = arrayOf("الأحد", "الاثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت")
    private val MONS = arrayOf("يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو", "يوليو", "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر")
    private val HMONS = arrayOf("محرم", "صفر", "ربيع الأول", "ربيع الآخر", "جمادى الأولى", "جمادى الآخرة", "رجب", "شعبان", "رمضان", "شوال", "ذو القعدة", "ذو الحجة")
    private var hijriDay = -1
    private var hijriText = ""

    private fun hijri(cal: Calendar): String {
        val key = cal.get(Calendar.YEAR) * 400 + cal.get(Calendar.DAY_OF_YEAR)
        if (key == hijriDay) return hijriText
        hijriDay = key
        hijriText = try {
            val ic = android.icu.util.IslamicCalendar()
            ic.calculationType = android.icu.util.IslamicCalendar.CalculationType.ISLAMIC_UMALQURA
            ic.timeInMillis = cal.timeInMillis
            ic.get(android.icu.util.Calendar.DAY_OF_MONTH).toString() + " " + HMONS[ic.get(android.icu.util.Calendar.MONTH).coerceIn(0, 11)] +
                " " + ic.get(android.icu.util.Calendar.YEAR) + " هـ"
        } catch (e: Exception) { "" }
        return hijriText
    }

    private fun line(c: Canvas, t: String, cx: Float, base: Float, maxW: Float, size: Float, color: Int) {
        if (t.isEmpty()) return
        textPaint.color = color
        textPaint.textSize = size
        val tw = textPaint.measureText(t)
        if (tw > maxW && tw > 0f) textPaint.textSize = size * maxW / tw
        c.drawText(t, cx, base, textPaint)
    }

    private fun drawAnalog(c: Canvas, w: Float, h: Float) {
        c.drawColor(0xFFFFFFFF.toInt())
        val cal = Calendar.getInstance()
        val d = minOf(h * 0.8f, w * 0.44f)
        val cx = h * 0.1f + d / 2f
        val cy = h / 2f
        val r = d / 2f
        facePaint.style = Paint.Style.FILL
        facePaint.color = 0xFFF2F2F7.toInt()
        c.drawCircle(cx, cy, r, facePaint)
        linePaint.color = 0xFFD1D1D6.toInt()
        linePaint.strokeWidth = r * 0.05f
        c.drawCircle(cx, cy, r, linePaint)
        for (i in 0 until 12) {
            val a = Math.toRadians(i * 30.0 - 90.0)
            val q = i % 3 == 0
            linePaint.color = if (q) 0xFF1C1C1E.toInt() else 0xFF8E8E93.toInt()
            linePaint.strokeWidth = if (q) r * 0.07f else r * 0.035f
            val l0 = if (q) 0.78f else 0.86f
            c.drawLine(cx + (Math.cos(a) * r * l0).toFloat(), cy + (Math.sin(a) * r * l0).toFloat(),
                cx + (Math.cos(a) * r * 0.94f).toFloat(), cy + (Math.sin(a) * r * 0.94f).toFloat(), linePaint)
        }
        val sec = cal.get(Calendar.SECOND)
        val min = cal.get(Calendar.MINUTE) + sec / 60f
        val hr = (cal.get(Calendar.HOUR) % 12) + min / 60f
        hand(c, cx, cy, hr * 30.0, r * 0.5f, r * 0.09f, 0xFF1C1C1E.toInt())
        hand(c, cx, cy, min * 6.0, r * 0.75f, r * 0.06f, 0xFF1C1C1E.toInt())
        hand(c, cx, cy, sec * 6.0, r * 0.84f, r * 0.03f, 0xFFFF9F0A.toInt())
        facePaint.color = 0xFFFF9F0A.toInt()
        c.drawCircle(cx, cy, r * 0.06f, facePaint)
        // text block (right of the face)
        val left = h * 0.1f + d + h * 0.1f
        val bw = w - left - h * 0.08f
        if (bw > 20f) {
            val mx = left + bw / 2f
            line(c, DAYS[cal.get(Calendar.DAY_OF_WEEK) - 1], mx, h * 0.30f, bw, h * 0.17f, 0xFF1C1C1E.toInt())
            line(c, cal.get(Calendar.DAY_OF_MONTH).toString() + " " + MONS[cal.get(Calendar.MONTH)] + " " + cal.get(Calendar.YEAR), mx, h * 0.50f, bw, h * 0.115f, 0xFF3A3A3C.toInt())
            line(c, hijri(cal), mx, h * 0.68f, bw, h * 0.115f, 0xFF6E6E73.toInt())
            line(c, timeText(cal, false), mx, h * 0.88f, bw, h * 0.13f, 0xFFFF9F0A.toInt())
        }
    }

    private fun timeText(cal: Calendar, ampm: Boolean): String {
        var hh = cal.get(Calendar.HOUR_OF_DAY)
        val mm = cal.get(Calendar.MINUTE)
        var suffix = ""
        if (!is24) {
            suffix = if (hh >= 12) " PM" else " AM"
            hh %= 12
            if (hh == 0) hh = 12
        }
        return (if (is24 && hh < 10) "0" else "") + hh + ":" + (if (mm < 10) "0" else "") + mm + (if (ampm) suffix else "")
    }

    private fun hand(c: Canvas, cx: Float, cy: Float, deg: Double, len: Float, width: Float, color: Int) {
        val a = Math.toRadians(deg - 90.0)
        linePaint.color = color
        linePaint.strokeWidth = width
        c.drawLine(cx, cy, cx + (Math.cos(a) * len).toFloat(), cy + (Math.sin(a) * len).toFloat(), linePaint)
    }

    private fun drawDigital(c: Canvas, w: Float, h: Float) {
        c.drawColor(0xFFFFFFFF.toInt())
        val cal = Calendar.getInstance()
        val cx = w / 2f
        val bw = w * 0.88f
        line(c, timeText(cal, true), cx, h * 0.40f, bw, h * 0.34f, 0xFF1C1C1E.toInt())
        line(c, DAYS[cal.get(Calendar.DAY_OF_WEEK) - 1] + " " + cal.get(Calendar.DAY_OF_MONTH) + " " + MONS[cal.get(Calendar.MONTH)] + " " + cal.get(Calendar.YEAR),
            cx, h * 0.64f, bw, h * 0.13f, 0xFF3A3A3C.toInt())
        line(c, hijri(cal), cx, h * 0.84f, bw, h * 0.13f, 0xFF6E6E73.toInt())
    }
}
