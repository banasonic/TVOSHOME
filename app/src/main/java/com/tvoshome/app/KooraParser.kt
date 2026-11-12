package com.tvoshome.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

/**
 * Extracts today's matches from the kooora.com "today's matches" HTML without any WebView/DOM
 * (same logic as assets/koora.js, but fast and light: it runs on a background thread).
 */
object KooraParser {
    private val skipRe = Regex("<(script|style|noscript)[^>]*>.*?</\\1>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val tagRe = Regex("<[^>]+>")
    private val spaceRe = Regex("\\s+")
    private val h1Re = Regex("<h1[^>]*>.*?</h1>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val aRe = Regex("<a\\s[^>]*?href\\s*=\\s*[\"']([^\"']*)[\"'][^>]*>(.*?)</a>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val timeRe = Regex("^\\d{1,2}:\\d{2}$")
    private val numRe = Regex("&#(\\d+);")
    private val imgRe = Regex("<img[^>]*?(?:data-src|src)\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)

    private fun abs(u: String): String = when {
        u.startsWith("//") -> "https:" + u
        u.startsWith("/") -> "https://www.kooora.com" + u
        else -> u
    }

    private fun decode(t: String): String {
        var s = t.replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"")
            .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
        if (s.contains("&#")) {
            s = numRe.replace(s) { m ->
                try { String(Character.toChars(m.groupValues[1].toInt())) } catch (e: Exception) { "" }
            }
        }
        return s
    }

    private fun clean(t: String): String = spaceRe.replace(decode(t), " ").trim()
    private fun texts(frag: String): List<String> = tagRe.split(frag).map { clean(it) }.filter { it.isNotEmpty() }

    private fun href(h: String): String {
        val d = decode(h)
        return try { URLDecoder.decode(d.replace("+", "%2B"), "UTF-8") } catch (e: Exception) { d }
    }

    fun parse(htmlIn: String): String? {
        val html0 = skipRe.replace(htmlIn, "")
        var start = -1
        for (m in h1Re.findAll(html0)) {
            if (m.value.contains("المباريات والنتائج")) {
                start = m.range.last + 1
                break
            }
        }
        if (start < 0) return null
        var html = html0.substring(start)
        val fi = html.indexOf("<footer")
        if (fi > 0) html = html.substring(0, fi)

        val out = JSONArray()
        val seen = HashSet<String>()
        var comp: Pair<String, String>? = null
        var round = ""
        var cur: JSONObject? = null
        var last = 0

        for (m in aRe.findAll(html)) {
            if (comp != null && m.range.first > last) {
                for (t in texts(html.substring(last, m.range.first))) {
                    if (t != "إعلان" && t != "شاهد مباشرة على" && t.length < 30) round = t
                }
            }
            last = m.range.last + 1
            val hr = href(m.groupValues[1])
            val inner = m.groupValues[2]

            if (hr.contains("/مباراة/")) {
                val id = hr.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
                if (!seen.add(id)) {
                    cur = null
                    continue
                }
                val ts = texts(inner)
                var k = -1
                for (i in ts.indices) {
                    if (timeRe.matches(ts[i])) { k = i; break }
                }
                val nm = if (k < 0) ts.take(4) else ts.take(k)
                val h = nm.getOrElse(0) { "" }
                val a = when (nm.size) {
                    4 -> nm[2]
                    2 -> nm[1]
                    else -> nm.getOrElse((nm.size + 1) / 2) { "" }
                }
                val o = JSONObject()
                    .put("id", id)
                    .put("c", comp?.first ?: "")
                    .put("k", comp?.second ?: "")
                    .put("r", round)
                    .put("h", h)
                    .put("a", a)
                    .put("t", if (k >= 0) ts[k] else "")
                    .put("st", if (k < 0) ts.drop(4).joinToString(" ") else "")
                    .put("x", if (k >= 0) ts.drop(k + 1).joinToString(" ") else "")
                    .put("u", abs(hr.substringBefore('#')))
                    .put("hl", imgRe.findAll(inner).map { abs(decode(it.groupValues[1])) }.toList().getOrElse(0) { "" })
                    .put("al", imgRe.findAll(inner).map { abs(decode(it.groupValues[1])) }.toList().getOrElse(1) { "" })
                    .put("ch", JSONArray())
                out.put(o)
                cur = o
                continue
            }
            if (hr.contains("/مسابقة/") && inner.contains("<img", true)) {
                val t2 = texts(inner)
                comp = Pair(t2.getOrElse(0) { "" }, t2.getOrElse(1) { "" })
                round = ""
                cur = null
                continue
            }
            val c = cur
            if (c != null && hr.startsWith("http") && !hr.contains("kooora.com")) {
                val name = clean(tagRe.replace(inner, " "))
                val arr = c.getJSONArray("ch")
                var dup = false
                for (i in 0 until arr.length()) if (arr.getString(i) == name) dup = true
                if (name.isNotEmpty() && !dup && arr.length() < 14) arr.put(name)
            }
        }
        if (out.length() == 0) return null
        return JSONObject().put("m", out).toString()
    }
}
