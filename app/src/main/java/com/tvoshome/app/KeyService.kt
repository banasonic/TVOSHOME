package com.tvoshome.app

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.WebViewAssetLoader

/**
 * Accessibility service that
 *  1) watches the remote key you chose and reacts to a LONG press,
 *  2) hosts the overlay (a transparent WebView drawn above every app).
 * Overlay modes: hidden | full (cards, focusable) | toast (notification only, not touchable).
 */
class KeyService : AccessibilityService() {

    companion object {
        @Volatile var instance: KeyService? = null
        const val KOORA_URL = "https://www.kooora.com/%D9%83%D8%B1%D8%A9-%D8%A7%D9%84%D9%82%D8%AF%D9%85/%D9%85%D8%A8%D8%A7%D8%B1%D9%8A%D8%A7%D8%AA-%D8%A7%D9%84%D9%8A%D9%88%D9%85"
    }

    private val ui = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var root: OverlayRoot? = null
    private var web: WebView? = null
    @Volatile var mode = "hidden"
    private var learning = false
    private var longFired = false
    private var flashEnd: Runnable? = null
    private val longRun = Runnable {
        longFired = true
        toggle()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (root == null) buildOverlay()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        ui.post {
            try {
                val r = root
                if (r != null && r.parent != null) wm?.removeView(r)
            } catch (e: Exception) { }
        }
        return super.onUnbind(intent)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildOverlay() {
        val themed = ContextThemeWrapper(applicationContext, R.style.AppTheme)
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        val w = WebView(themed)
        w.setBackgroundColor(Color.TRANSPARENT)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.mediaPlaybackRequiresUserGesture = false
        w.settings.allowFileAccess = false
        w.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val u = request?.url ?: return null
                return loader.shouldInterceptRequest(u)
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                ui.post { rebuildOverlay() }
                return true
            }
        }
        w.addJavascriptInterface(Bridge(this), "Native")
        w.loadUrl("https://appassets.androidplatform.net/assets/index.html")
        val r = OverlayRoot(themed, this)
        r.addView(w, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        web = w
        root = r
    }

    /** WebView renderer died (low RAM on TV boxes): rebuild it and restore the overlay state. */
    private fun rebuildOverlay() {
        val was = mode
        try {
            val r = root
            if (r != null && r.parent != null) wm?.removeView(r)
        } catch (e: Exception) { }
        try { web?.destroy() } catch (e: Exception) { }
        mode = "hidden"
        root = null
        web = null
        buildOverlay()
        if (was != "hidden") applyMode(was)
    }

    private var kooraBusy = false

    fun fetchKoora() {
        ui.post {
            if (!kooraBusy) {
                kooraBusy = true
                KooraJob(this, KOORA_URL) { kooraBusy = false }.start()
            }
        }
    }

    private fun params(focusable: Boolean): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        if (!focusable) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        val p = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        return p
    }

    fun js(code: String) {
        ui.post { web?.evaluateJavascript(code, null) }
    }

    fun showMode(m: String) {
        ui.post { applyMode(m) }
    }

    private fun applyMode(m: String) {
        val r = root ?: return
        val manager = wm ?: return
        if (m == mode) return
        flashEnd?.let { ui.removeCallbacks(it) }
        flashEnd = null
        try {
            if (r.parent != null) manager.removeView(r)
        } catch (e: Exception) { }
        mode = m
        web?.evaluateJavascript("window.setMode&&setMode('$m')", null)
        if (m != "hidden") {
            try {
                manager.addView(r, params(m == "full"))
                if (m == "full") {
                    r.requestFocus()
                    web?.requestFocus()
                }
            } catch (e: Exception) {
                mode = "hidden"
            }
        }
    }

    fun toggle() {
        showMode(if (mode == "full") "hidden" else "full")
    }

    /** Show only the light notification toast for [ms] milliseconds when the overlay is hidden. */
    fun flash(ms: Int) {
        ui.post {
            if (mode == "hidden") {
                applyMode("toast")
                val end = Runnable { if (mode == "toast") applyMode("hidden") }
                flashEnd = end
                ui.postDelayed(end, ms.toLong())
            }
        }
    }

    fun handleBack() {
        ui.post {
            web?.evaluateJavascript("(window.onBack&&window.onBack())?1:0") { v ->
                if (v != "1") applyMode("hidden")
            }
        }
    }

    fun startLearn() {
        learning = true
        ui.postDelayed({
            if (learning) {
                learning = false
                reportKey()
            }
        }, 10000)
    }

    private fun reportKey() {
        val c = Prefs.keyCode(this)
        js("window.onKeyLearned&&onKeyLearned('${Prefs.keyName(c)}',$c)")
    }

    override fun onKeyEvent(e: KeyEvent): Boolean {
        if (learning) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
                learning = false
                if (e.keyCode != KeyEvent.KEYCODE_BACK) Prefs.setKeyCode(this, e.keyCode)
                reportKey()
            }
            return true
        }
        if (e.keyCode != Prefs.keyCode(this)) return false
        val consume = Prefs.consume(this)
        if (e.action == KeyEvent.ACTION_DOWN) {
            if (e.repeatCount == 0) {
                longFired = false
                ui.removeCallbacks(longRun)
                ui.postDelayed(longRun, Prefs.longMs(this).toLong())
                return consume
            }
            return longFired || consume
        }
        if (e.action == KeyEvent.ACTION_UP) {
            ui.removeCallbacks(longRun)
            val was = longFired
            longFired = false
            return was || consume
        }
        return false
    }
}

/** Container of the overlay WebView: BACK closes the open card window first, then hides the overlay. */
class OverlayRoot(ctx: Context, private val svc: KeyService) : FrameLayout(ctx) {
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (event.action == KeyEvent.ACTION_UP) svc.handleBack()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
