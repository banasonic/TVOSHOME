package com.tvoshome.app

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.util.Base64
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.webkit.JavascriptInterface
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Methods callable from the web UI as window.Native.*  (they run on a WebView background thread). */
class Bridge(private val svc: KeyService) {
    private val ctx: Context get() = svc.applicationContext
    private val ui = Handler(Looper.getMainLooper())

    @JavascriptInterface fun stats(): String = SysInfo.stats(ctx)
    @JavascriptInterface fun apps(): String = SysInfo.running(ctx).toString()
    @JavascriptInterface fun clearAll(): Int = SysInfo.killAll(ctx)
    @JavascriptInterface fun kill(pkg: String) { SysInfo.kill(ctx, pkg) }
    @JavascriptInterface fun thermalReport(): String = SysInfo.thermalReport(ctx)

    /** Last known device location as {"lat":..,"lon":..} or "" (needs the location permission, see the app screen). */
    @JavascriptInterface fun deviceLoc(): String {
        return try {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            var best: Location? = null
            for (p in lm.getProviders(true)) {
                val l = try { lm.getLastKnownLocation(p) } catch (e: SecurityException) { null }
                if (l != null && (best == null || l.time > best.time)) best = l
            }
            if (best == null) "" else JSONObject().put("lat", best.latitude).put("lon", best.longitude).toString()
        } catch (e: Exception) { "" }
    }

    @JavascriptInterface fun net(): String = SysInfo.net(ctx)
    @JavascriptInterface fun bt(): Boolean = SysInfo.bt()

    @JavascriptInterface fun hide() { svc.showMode("hidden") }
    @JavascriptInterface fun flash(ms: Int) { svc.flash(ms) }

    @JavascriptInterface fun getKey(): String {
        val c = Prefs.keyCode(ctx)
        return JSONObject()
            .put("code", c)
            .put("name", Prefs.keyName(c))
            .put("longMs", Prefs.longMs(ctx))
            .put("consume", Prefs.consume(ctx))
            .toString()
    }
    @JavascriptInterface fun learnKey() { svc.startLearn() }
    @JavascriptInterface fun setLongMs(ms: Int) { Prefs.setLongMs(ctx, ms) }
    @JavascriptInterface fun setConsume(b: Boolean) { Prefs.setConsume(ctx, b) }

    @JavascriptInterface fun openSettings(kind: String) {
        ui.post {
            try {
                val action = when (kind) {
                    "wifi" -> Settings.ACTION_WIFI_SETTINGS
                    "bt" -> Settings.ACTION_BLUETOOTH_SETTINGS
                    else -> Settings.ACTION_SETTINGS
                }
                svc.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) { }
            svc.showMode("hidden")
        }
    }

    @JavascriptInterface fun assistant() {
        ui.post {
            svc.showMode("hidden")
            try {
                svc.startActivity(Intent(Intent.ACTION_VOICE_COMMAND).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                try {
                    svc.startActivity(Intent(Intent.ACTION_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e2: Exception) { }
            }
        }
    }

    @JavascriptInterface fun pickSound() {
        ui.post {
            svc.showMode("hidden")
            try {
                svc.startActivity(Intent(svc, SoundPickActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) { }
        }
    }
    @JavascriptInterface fun soundName(): String = Prefs.soundName(ctx)
    @JavascriptInterface fun playCustom() {
        ui.post {
            try {
                val u = Prefs.soundUri(ctx)
                if (u != null) {
                    val mp = MediaPlayer()
                    mp.setDataSource(ctx, Uri.parse(u))
                    mp.setOnCompletionListener { it.release() }
                    mp.prepare()
                    mp.start()
                }
            } catch (e: Exception) { }
        }
    }

    private val pool = Executors.newFixedThreadPool(3)
    private val allowed = setOf(
        "api.open-meteo.com", "geocoding-api.open-meteo.com", "air-quality-api.open-meteo.com",
        "get.geojs.io", "ipwho.is", "ipapi.co", "api.bigdatacloud.net", "www.kooora.com"
    )

    /** Async HTTPS GET (no CORS limits). Result comes back through window.onHttp(id, status, base64Body). */
    @JavascriptInterface fun httpGet(id: Int, url: String) {
        val host = try { Uri.parse(url).host } catch (e: Exception) { null }
        if (host == null || !allowed.contains(host) || !url.startsWith("https://")) {
            svc.js("window.onHttp&&onHttp($id,0,'')")
            return
        }
        pool.execute {
            var status = 0
            var body = ""
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 12000
                c.readTimeout = 18000
                c.instanceFollowRedirects = true
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11; TV Box) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                c.setRequestProperty("Accept-Language", "ar,en;q=0.8")
                status = c.responseCode
                val stream = if (status in 200..299) c.inputStream else c.errorStream
                body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                c.disconnect()
            } catch (e: Exception) {
                status = 0
            }
            val b64 = Base64.encodeToString(body.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            svc.js("window.onHttp&&onHttp($id,$status,'$b64')")
        }
    }

    /** Loads kooora.com "today's matches" in a hidden WebView; result arrives via window.onKoora(ok, base64Json). */
    @JavascriptInterface fun fetchKoora() { svc.fetchKoora() }
}
