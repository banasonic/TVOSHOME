package com.tvoshome.app

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.text.format.DateFormat
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import java.util.Calendar
import java.util.Random

/**
 * Optional cover for the channel logo (top-right corner).
 *
 * Nothing runs unless the option is on. A short watch starts only after a remote key press or an
 * app switch: it takes a few small screenshots (about every 0.7 s, max ~7 s) looking for the black
 * channel-switch screen; once the picture comes back it scans the top-right corner for a few
 * seconds for one of the 12 known logos. If found twice in a row, a cover (Connect logo / analog
 * clock / digital clock) is drawn over it, sized to the matched logo. Then everything goes idle.
 * Screenshots need Android 11+ (AccessibilityService.takeScreenshot).
 */
class LogoCover(private val svc: KeyService) {
    private val ctx: Context = svc.applicationContext
    private val ui = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var worker: Handler? = null

    @Volatile private var enabled = false
    private var style = 0

    // UI-thread state
    private var view: CoverView? = null
    private var attached = false
    private var active = false
    private var rect = IntArray(4)
    private var lastPkg: String? = null
    private var coverPkg: String? = null
    private var lastArm = 0L

    // worker-thread state
    private var state = 0            // 0 idle, 1 waiting for the black screen, 2 scanning for the logo
    private var deadline = 0L
    private var sawBlack = false
    private var hits = 0
    private var lastHit: LogoMatch? = null
    private var ticking = false
    private var failures = 0

    private val tickRun = Runnable { tick() }

    fun configure() {
        enabled = Prefs.logoOn(ctx) && Build.VERSION.SDK_INT >= 30
        style = Prefs.logoStyle(ctx)
        if (!enabled) {
            stopWorker()
            hideCover()
            return
        }
        if (thread == null) {
            val t = HandlerThread("logo-scan", Process.THREAD_PRIORITY_BACKGROUND)
            t.start()
            thread = t
            worker = Handler(t.looper)
        }
        view?.setStyle(style)
        if (active) layoutCover()
    }

    fun shutdown() {
        enabled = false
        stopWorker()
        hideCover()
    }

    private fun stopWorker() {
        val w = worker
        w?.post { state = 0; ticking = false; w.removeCallbacks(tickRun) }
        thread?.quitSafely()
        thread = null
        worker = null
    }

    // ------------------------------------------------------------------ triggers (UI thread)

    fun onKey() {
        arm()
    }

    fun onWindow(pkg: String?) {
        if (!enabled || pkg == null) return
        if (pkg == ctx.packageName || pkg == "com.android.systemui" ||
            pkg.contains("inputmethod") || pkg.contains("keyboard")) return
        if (active && coverPkg != null && pkg != coverPkg) hideCover()
        lastPkg = pkg
        arm()
    }

    fun onMode(m: String) {
        if (!active) return
        if (m == "full" || m == "standby" || m == "prompt") detachView() else attachView()
    }

    private fun arm() {
        if (!enabled) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastArm < 1200L) return
        lastArm = now
        val w = worker ?: return
        w.post {
            state = 1
            deadline = SystemClock.elapsedRealtime() + 7000L
            sawBlack = false
            hits = 0
            lastHit = null
            if (!ticking) {
                ticking = true
                w.post(tickRun)
            }
        }
    }

    // ------------------------------------------------------------------ worker

    private fun tick() {
        val w = worker ?: return
        if (state == 0 || !enabled) {
            ticking = false
            return
        }
        if (SystemClock.elapsedRealtime() > deadline) {
            state = 0
            ticking = false
            return
        }
        if (Build.VERSION.SDK_INT < 30) {
            state = 0
            ticking = false
            return
        }
        shoot(w)
    }

    private fun shoot(w: Handler) {
        try {
            svc.takeScreenshot(Display.DEFAULT_DISPLAY, { r -> w.post(r) }, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    var bmp: Bitmap? = null
                    try {
                        val hb = result.hardwareBuffer
                        val hw = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                        if (hw != null) {
                            bmp = hw.copy(Bitmap.Config.ARGB_8888, false)
                            hw.recycle()
                        }
                        hb.close()
                    } catch (e: Exception) {
                    }
                    failures = 0
                    if (bmp != null) {
                        try { onFrame(bmp) } catch (e: Exception) { Log.d("LogoCover", "frame error " + e) }
                        bmp.recycle()
                    }
                    next(700L)
                }

                override fun onFailure(errorCode: Int) {
                    failures++
                    if (failures >= 4) {
                        state = 0
                        ticking = false
                    } else {
                        next(1500L)
                    }
                }
            })
        } catch (e: Exception) {
            state = 0
            ticking = false
        }
    }

    private fun next(delay: Long) {
        val w = worker ?: return
        if (state == 0) {
            ticking = false
            return
        }
        w.postDelayed(tickRun, delay)
    }

    private fun isDark(b: Bitmap): Boolean {
        val sw = 48
        val sh = 27
        val s = Bitmap.createScaledBitmap(b, sw, sh, true)
        val px = IntArray(sw * sh)
        s.getPixels(px, 0, sw, 0, 0, sw, sh)
        if (s !== b) s.recycle()
        var dark = 0
        var total = 0
        for (y in 0 until sh) for (x in 0 until sw) {
            if (x >= 26 && y < 9) continue     // top-right corner (where the logo / our cover is)
            total++
            val p = px[y * sw + x]
            val l = (((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 151 + (p and 0xFF) * 28) shr 8
            if (l < 26) dark++
        }
        return total > 0 && dark >= total * 0.9f
    }

    private fun onFrame(b: Bitmap) {
        val now = SystemClock.elapsedRealtime()
        val dark = isDark(b)
        if (state == 1) {
            if (dark) {
                if (!sawBlack) {
                    sawBlack = true
                    ui.post { hideCover() }
                }
                deadline = maxOf(deadline, now + 9000L)
            } else if (sawBlack) {
                state = 2
                deadline = now + 12000L
                hits = 0
                lastHit = null
                scan(b)
            }
        } else if (state == 2) {
            if (dark) {
                state = 1
                sawBlack = true
                deadline = now + 9000L
                ui.post { hideCover() }
            } else {
                scan(b)
            }
        }
    }

    private fun scan(b: Bitmap) {
        val sw = b.width
        val sh = b.height
        val x0 = (sw * 0.5f).toInt()
        val rw = sw - x0
        val rh = (sh * 0.30f).toInt()
        if (rw < 50 || rh < 20) return
        val k = LogoDetector.RW.toFloat() / rw
        val rhS = maxOf(20, (rh * k).toInt())
        val crop = Bitmap.createBitmap(b, x0, 0, rw, rh)
        val sc = Bitmap.createScaledBitmap(crop, LogoDetector.RW, rhS, true)
        val px = IntArray(LogoDetector.RW * rhS)
        sc.getPixels(px, 0, LogoDetector.RW, 0, 0, LogoDetector.RW, rhS)
        if (sc !== crop) sc.recycle()
        crop.recycle()
        val f = LogoDetector.features(px, LogoDetector.RW, rhS)
        val m = LogoDetector.detect(ctx, f, LogoDetector.RW, rhS)
        if (m == null || m.score < 0.55f) {
            hits = 0
            lastHit = null
            return
        }
        val lh = lastHit
        val near = lh != null && Math.abs(lh.x - m.x) <= m.w / 5 && Math.abs(lh.y - m.y) <= m.h / 5
        hits = if (near) hits + 1 else 1
        lastHit = m
        Log.d("LogoCover", "candidate tpl=" + (m.tpl + 1) + " score=" + m.score + " hits=" + hits)
        if (hits >= 2 || m.score >= 0.8f) {
            state = 0
            val e = LogoDetector.extend(m)
            val padX = 0.04f * (e[2] - e[0])
            val padY = 0.08f * (e[3] - e[1])
            val l = maxOf(0, (x0 + (e[0] - padX) / k).toInt())
            val t = maxOf(0, ((e[1] - padY) / k).toInt())
            val r = minOf(sw, (x0 + (e[2] + padX) / k).toInt())
            val bt = minOf(sh, ((e[3] + padY) / k).toInt())
            ui.post { showCover(l, t, r, bt) }
        }
    }

    // ------------------------------------------------------------------ cover window (UI thread)

    private fun showCover(l: Int, t: Int, r: Int, b: Int) {
        if (!enabled || r - l < 20 || b - t < 12) return
        rect = intArrayOf(l, t, r, b)
        coverPkg = lastPkg
        active = true
        if (view == null) view = CoverView(ctx, style)
        view?.setStyle(style)
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
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
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
        }
    }

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
        coverPkg = null
        detachView()
    }
}

/** The drawn cover: Connect logo with random lively effects, or an analog / digital clock. */
class CoverView(context: Context, private var style: Int) : View(context) {
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
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    private fun drawAnalog(c: Canvas, w: Float, h: Float) {
        c.drawColor(0xFF08080C.toInt())
        val cx = w / 2f
        val cy = h / 2f
        val r = minOf(w, h) * 0.46f
        facePaint.style = Paint.Style.FILL
        facePaint.color = 0xFF17171E.toInt()
        c.drawCircle(cx, cy, r, facePaint)
        linePaint.color = 0xFFE9E9F0.toInt()
        linePaint.strokeWidth = r * 0.04f
        c.drawCircle(cx, cy, r, linePaint)
        for (i in 0 until 12) {
            val a = Math.toRadians(i * 30.0 - 90.0)
            val l0 = if (i % 3 == 0) 0.82f else 0.9f
            linePaint.strokeWidth = if (i % 3 == 0) r * 0.06f else r * 0.03f
            c.drawLine(cx + (Math.cos(a) * r * l0).toFloat(), cy + (Math.sin(a) * r * l0).toFloat(),
                cx + (Math.cos(a) * r * 0.96f).toFloat(), cy + (Math.sin(a) * r * 0.96f).toFloat(), linePaint)
        }
        val cal = Calendar.getInstance()
        val sec = cal.get(Calendar.SECOND)
        val min = cal.get(Calendar.MINUTE) + sec / 60f
        val hr = (cal.get(Calendar.HOUR) % 12) + min / 60f
        hand(c, cx, cy, hr * 30.0, r * 0.5f, r * 0.075f, 0xFFFFFFFF.toInt())
        hand(c, cx, cy, min * 6.0, r * 0.76f, r * 0.05f, 0xFFFFFFFF.toInt())
        hand(c, cx, cy, sec * 6.0, r * 0.86f, r * 0.025f, 0xFFFF453A.toInt())
        facePaint.color = 0xFFFF453A.toInt()
        c.drawCircle(cx, cy, r * 0.05f, facePaint)
    }

    private fun hand(c: Canvas, cx: Float, cy: Float, deg: Double, len: Float, width: Float, color: Int) {
        val a = Math.toRadians(deg - 90.0)
        linePaint.color = color
        linePaint.strokeWidth = width
        c.drawLine(cx, cy, cx + (Math.cos(a) * len).toFloat(), cy + (Math.sin(a) * len).toFloat(), linePaint)
    }

    private fun drawDigital(c: Canvas, w: Float, h: Float) {
        val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        bg.shader = LinearGradient(0f, 0f, 0f, h, 0xFF16161D.toInt(), 0xFF050508.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, bg)
        val cal = Calendar.getInstance()
        var hh = cal.get(Calendar.HOUR_OF_DAY)
        var suffix = ""
        if (!is24) {
            suffix = if (hh >= 12) "PM" else "AM"
            hh = hh % 12
            if (hh == 0) hh = 12
        }
        val txt = (if (is24 && hh < 10) "0" else "") + hh + ":" + (if (cal.get(Calendar.MINUTE) < 10) "0" else "") + cal.get(Calendar.MINUTE)
        var size = h * 0.62f
        textPaint.textSize = size
        val tw = textPaint.measureText(txt)
        val maxW = w * (if (suffix.isEmpty()) 0.92f else 0.78f)
        if (tw > maxW) {
            size *= maxW / tw
            textPaint.textSize = size
        }
        val cx = if (suffix.isEmpty()) w / 2f else w * 0.46f
        val cy = h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        c.drawText(txt, cx, cy, textPaint)
        if (suffix.isNotEmpty()) {
            textPaint.textSize = size * 0.32f
            c.drawText(suffix, w * 0.89f, h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f + size * 0.2f, textPaint)
        }
    }
}
