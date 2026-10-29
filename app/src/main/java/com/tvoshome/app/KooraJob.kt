package com.tvoshome.app

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.ContextThemeWrapper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream

/**
 * Loads kooora.com "today's matches" in a hidden WebView (so the page runs its own scripts and
 * shows times in the device time zone), then extracts the matches with assets/koora.js.
 */
class KooraJob(private val svc: KeyService, private val url: String, private val onEnd: () -> Unit) {
    private val ui = Handler(Looper.getMainLooper())
    private var w: WebView? = null
    private var tries = 0
    private var lastN = -1
    private var finished = false
    private var script = ""
    private val poll = Runnable { step() }
    private val timeout = Runnable { done(false, "timeout") }

    @SuppressLint("SetJavaScriptEnabled")
    fun start() {
        script = try {
            svc.assets.open("koora.js").bufferedReader().use { it.readText() }
        } catch (e: Exception) { "" }
        if (script.isEmpty()) {
            done(false, "no script")
            return
        }
        try {
            val v = WebView(ContextThemeWrapper(svc.applicationContext, R.style.AppTheme))
            v.settings.javaScriptEnabled = true
            v.settings.domStorageEnabled = true
            v.settings.userAgentString = UA
            v.layout(0, 0, 1280, 3000)
            v.settings.blockNetworkImage = true
            v.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val h = request?.url?.host ?: return null
                    for (b in BLOCK) {
                        if (h.contains(b)) return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                    }
                    return null
                }

                override fun onPageFinished(view: WebView?, u: String?) {
                    ui.postDelayed(poll, 2500)
                }
            }
            w = v
            v.loadUrl(url)
            ui.postDelayed(timeout, 45000)
        } catch (e: Exception) {
            done(false, "webview")
        }
    }

    private fun step() {
        val v = w
        if (finished || v == null) return
        tries++
        val js = "(function(){try{" + script + ";return JSON.stringify(kooraExtract(document))}catch(e){return 'ERR'+e}})()"
        v.evaluateJavascript(js) { raw ->
            val s = try { (JSONTokener(raw).nextValue() as? String) ?: "" } catch (e: Exception) { "" }
            var n = 0
            if (s.startsWith("{")) {
                try { n = JSONObject(s).optJSONArray("m")?.length() ?: 0 } catch (e: Exception) { }
            }
            if (n > 0 && n == lastN) {
                done(true, s)
            } else if (tries >= 14) {
                if (n > 0) done(true, s) else done(false, "empty")
            } else {
                lastN = n
                ui.postDelayed(poll, 1500)
            }
        }
    }

    private fun done(ok: Boolean, payload: String) {
        if (finished) return
        finished = true
        ui.removeCallbacks(poll)
        ui.removeCallbacks(timeout)
        try {
            w?.stopLoading()
            w?.destroy()
        } catch (e: Exception) { }
        w = null
        val b = Base64.encodeToString(payload.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        svc.js("window.onKoora&&onKoora($ok,'$b')")
        onEnd()
    }

    companion object {
        val BLOCK = listOf("doubleclick", "googlesyndication", "googletagmanager", "google-analytics", "adservice", "taboola", "outbrain", "facebook", "criteo", "adnxs", "amazon-adsystem", "scorecardresearch", "hotjar", "teads", "pubmatic", "rubiconproject")
        const val UA = "Mozilla/5.0 (Linux; Android 11; TV Box) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}
