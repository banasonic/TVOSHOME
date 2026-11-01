package com.tvoshome.app

import android.content.Context
import android.os.Process
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Downloads + parses today's matches in the background and keeps the ready-to-show result on disk. */
object MatchesRepo {
    @Volatile private var busy = false

    private fun file(ctx: Context) = File(ctx.filesDir, "matches.json")
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("matches", Context.MODE_PRIVATE)
    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    fun cached(ctx: Context): String = try { file(ctx).readText() } catch (e: Exception) { "" }

    private fun stale(ctx: Context, maxAge: Long): Boolean {
        val p = prefs(ctx)
        if (!file(ctx).exists()) return true
        if (p.getString("day", "") != today()) return true
        return System.currentTimeMillis() - p.getLong("ts", 0L) > maxAge
    }

    /** Returns true if a download was started. [done] gets the new JSON (or null on failure) on a background thread. */
    fun refresh(ctx: Context, force: Boolean, done: (String?) -> Unit): Boolean {
        if (busy) return false
        if (!force && !stale(ctx, 1800000L)) return false
        busy = true
        Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            var result: String? = null
            try {
                val parsed = KooraParser.parse(download())
                if (parsed != null) {
                    val now = System.currentTimeMillis()
                    val r = JSONObject(parsed).put("day", today()).put("ts", now).toString()
                    file(ctx).writeText(r)
                    prefs(ctx).edit().putString("day", today()).putLong("ts", now).apply()
                    result = r
                }
            } catch (e: Exception) {
            }
            busy = false
            done(result)
        }.start()
        return true
    }

    private fun download(): String {
        val c = URL(KeyService.KOORA_URL).openConnection() as HttpURLConnection
        c.connectTimeout = 12000
        c.readTimeout = 20000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        c.setRequestProperty("Accept-Language", "ar,en;q=0.8")
        c.setRequestProperty("Accept", "text/html,application/xhtml+xml")
        val code = c.responseCode
        if (code !in 200..299) {
            c.disconnect()
            throw Exception("http $code")
        }
        val bytes = ByteArrayOutputStream()
        c.inputStream.use { ins ->
            val buf = ByteArray(16384)
            var total = 0
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                bytes.write(buf, 0, n)
                total += n
                if (total > 5000000) break
            }
        }
        c.disconnect()
        return bytes.toString("UTF-8")
    }
}
