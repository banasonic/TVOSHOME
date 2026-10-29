package com.tvoshome.app

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

@Suppress("DEPRECATION")
object SysInfo {
    private var lastTotal = LongArray(0)
    private var lastIdle = LongArray(0)

    private fun readText(f: File): String? = try { f.readText().trim() } catch (e: Exception) { null }
    private fun readLong(f: File): Long? = readText(f)?.toLongOrNull()
    private fun cores(): Int = Runtime.getRuntime().availableProcessors()

    /** Per-core usage from /proc/stat (may be blocked on Android 8+). */
    private fun procStat(): DoubleArray? {
        val lines = try { File("/proc/stat").readLines() } catch (e: Exception) { return null }
        val rows = lines.filter { it.startsWith("cpu") && it.length > 3 && it[3].isDigit() }
        if (rows.isEmpty()) return null
        val n = rows.size
        val tot = LongArray(n)
        val idl = LongArray(n)
        for (i in 0 until n) {
            val p = rows[i].trim().split(Regex("\\s+"))
            var t = 0L
            for (j in 1 until minOf(p.size, 9)) t += p[j].toLongOrNull() ?: 0L
            tot[i] = t
            idl[i] = (p.getOrNull(4)?.toLongOrNull() ?: 0L) + (p.getOrNull(5)?.toLongOrNull() ?: 0L)
        }
        val out = DoubleArray(n)
        if (lastTotal.size == n) {
            for (i in 0 until n) {
                val dt = tot[i] - lastTotal[i]
                val di = idl[i] - lastIdle[i]
                out[i] = if (dt > 0) (100.0 * (dt - di) / dt).coerceIn(0.0, 100.0) else 0.0
            }
        }
        lastTotal = tot
        lastIdle = idl
        return out
    }

    /** Fallback: estimate load from the current cpufreq step. */
    private fun freqLoad(): DoubleArray? {
        val n = cores()
        val out = DoubleArray(n)
        var ok = false
        for (i in 0 until n) {
            val base = "/sys/devices/system/cpu/cpu$i/cpufreq/"
            val cur = readLong(File(base + "scaling_cur_freq")) ?: readLong(File(base + "cpuinfo_cur_freq"))
            val mx = readLong(File(base + "cpuinfo_max_freq"))
            val mn = readLong(File(base + "cpuinfo_min_freq")) ?: 0L
            if (cur != null && mx != null && mx > mn) {
                out[i] = ((cur - mn) * 100.0 / (mx - mn)).coerceIn(0.0, 100.0)
                ok = true
            }
        }
        return if (ok) out else null
    }

    class Sensor(val path: String, val name: String, val c: Double)

    private fun readNum(f: File): Double? =
        readText(f)?.split(Regex("\\s+"))?.firstOrNull()?.toDoubleOrNull()

    /** Raw kernel values are usually millidegrees; some chips report tenths or whole degrees. */
    private fun norm(raw: Double): Double = when {
        raw > 1000.0 -> raw / 1000.0
        raw in 200.0..1300.0 -> raw / 10.0
        else -> raw
    }

    /** Standard places: thermal zones (both sysfs roots) and hwmon. */
    fun sensors(): List<Sensor> {
        val out = ArrayList<Sensor>()
        val seen = HashSet<String>()
        for (d in listOf("/sys/class/thermal", "/sys/devices/virtual/thermal")) {
            val zones = File(d).listFiles()?.filter { it.name.startsWith("thermal_zone") } ?: emptyList()
            for (z in zones) {
                val f = File(z, "temp")
                val v = readNum(f) ?: continue
                val c = norm(v)
                if (c < 1.0 || c > 150.0) continue
                if (!seen.add(try { f.canonicalPath } catch (e: Exception) { f.path })) continue
                out.add(Sensor(f.path, readText(File(z, "type")) ?: z.name, c))
            }
        }
        val hw = File("/sys/class/hwmon").listFiles() ?: emptyArray()
        for (h in hw) {
            val nm = readText(File(h, "name")) ?: h.name
            val ts = h.listFiles()?.filter { it.name.matches(Regex("temp\\d+_input")) } ?: emptyList()
            for (t in ts) {
                val v = readNum(t) ?: continue
                val c = norm(v)
                if (c < 1.0 || c > 150.0) continue
                if (!seen.add(try { t.canonicalPath } catch (e: Exception) { t.path })) continue
                out.add(Sensor(t.path, nm + "/" + t.name, c))
            }
        }
        return out
    }

    private val nameRe = Regex("^(temp\\d*(_input)?|temperature|cpu_temp|chip_temp|soc_temp|.*_temp|tsensor.*)$", RegexOption.IGNORE_CASE)
    private var deepCache: List<Sensor>? = null
    private var deepAt = 0L

    /**
     * Vendor-neutral discovery for SoCs that do not use the standard thermal framework (e.g. some Goke boards):
     * walks /sys/devices (depth-limited, symlinks skipped) looking for temperature-like files.
     */
    private fun deepScan(): List<Sensor> {
        val now = System.currentTimeMillis()
        val cached = deepCache
        if (cached != null && now - deepAt < 300000L) return cached
        val out = ArrayList<Sensor>()
        var visited = 0
        fun walk(d: File, depth: Int) {
            if (depth > 6 || visited > 4000 || out.size >= 60) return
            val kids = try { d.listFiles() } catch (e: Exception) { null } ?: return
            for (k in kids) {
                visited++
                try {
                    if (java.nio.file.Files.isSymbolicLink(k.toPath())) continue
                    if (k.isDirectory) {
                        if (k.name != "power" && k.name != "subsystem") walk(k, depth + 1)
                    } else if (nameRe.matches(k.name)) {
                        val v = readNum(k) ?: continue
                        val c = norm(v)
                        if (c in 10.0..120.0) out.add(Sensor(k.path, k.parentFile?.name + "/" + k.name, c))
                    }
                } catch (e: Exception) { }
            }
        }
        walk(File("/sys/devices"), 0)
        deepCache = out
        deepAt = now
        return out
    }

    private var dumpAt = 0L
    private var dumpVal: Pair<Double, String>? = null

    /** Needs: adb shell pm grant com.tvoshome.app android.permission.DUMP  (reads Android's thermal service). */
    private fun dumpsys(): Pair<Double, String>? {
        val now = System.currentTimeMillis()
        if (now - dumpAt < 15000L) return dumpVal
        dumpAt = now
        dumpVal = null
        try {
            val p = Runtime.getRuntime().exec(arrayOf("dumpsys", "thermalservice"))
            val txt = p.inputStream.bufferedReader().use { it.readText() }
            p.destroy()
            val re = Regex("Temperature\\{mValue=([-0-9.]+), mType=(\\d+), mName=([^,}]+)")
            var best: Pair<Double, String>? = null
            for (m in re.findAll(txt)) {
                val v = m.groupValues[1].toDoubleOrNull() ?: continue
                val type = m.groupValues[2]
                val name = m.groupValues[3]
                if (v in 10.0..120.0 && (type == "0" || best == null)) best = Pair(v, name)
            }
            dumpVal = best
        } catch (e: Exception) { }
        return dumpVal
    }

    private fun pick(l: List<Sensor>): Sensor? {
        var best: Sensor? = null
        var bs = -1
        for (s in l) {
            if (s.c < 10.0 || s.c > 120.0) continue
            val n = (s.name + " " + s.path).lowercase()
            val sc = when {
                n.contains("cpu") || n.contains("soc") || n.contains("core") || n.contains("big") ||
                    n.contains("little") || n.contains("tsens") || n.contains("cluster") ||
                    n.contains("a53") || n.contains("a55") || n.contains("goke") || n.contains("gk") -> 3
                n.contains("gpu") || n.contains("ddr") || n.contains("battery") || n.contains("wifi") || n.contains("modem") -> 0
                else -> 2
            }
            if (sc > bs) { bs = sc; best = s }
        }
        return best
    }

    /** Returns (celsius, source) or (-1, ""). Order: custom path, standard sensors, deep scan, dumpsys. */
    fun pickTemp(ctx: Context): Pair<Double, String> {
        val custom = Prefs.thermalPath(ctx)
        if (custom.isNotEmpty()) {
            val v = readNum(File(custom))
            if (v != null) {
                val c = norm(v)
                if (c in 1.0..150.0) return Pair(c, custom)
            }
        }
        pick(sensors())?.let { return Pair(it.c, it.name) }
        pick(deepScan())?.let { return Pair(it.c, it.path) }
        val d = dumpsys()
        if (d != null) return Pair(d.first, "dumpsys:" + d.second)
        return Pair(-1.0, "")
    }

    fun thermalReport(ctx: Context): String {
        val used = pickTemp(ctx).second
        val arr = JSONArray()
        val all = ArrayList<Sensor>()
        all.addAll(sensors())
        if (all.isEmpty()) all.addAll(deepScan())
        for (s in all.take(14)) {
            arr.put(JSONObject().put("name", s.name).put("path", s.path).put("c", s.c).put("used", s.name == used || s.path == used))
        }
        val d = dumpsys()
        if (all.isEmpty() && d != null) {
            arr.put(JSONObject().put("name", "dumpsys:" + d.second).put("path", "dumpsys").put("c", d.first).put("used", true))
        }
        return JSONObject().put("list", arr).toString()
    }

    private fun loadAvg(): Double? = try {
        val t = File("/proc/loadavg").readText().trim().split(" ")[0].toDouble()
        (t / cores() * 100.0).coerceIn(0.0, 100.0)
    } catch (e: Exception) { null }

    private fun curFreqMHz(): Long {
        for (p in listOf(
            "/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq",
            "/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_cur_freq",
            "/sys/devices/system/cpu/cpufreq/policy0/scaling_cur_freq"
        )) {
            val v = readLong(File(p))
            if (v != null && v > 0) return v / 1000L
        }
        return 0L
    }

    fun stats(ctx: Context): String {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val total = mi.totalMem / 1048576L
        val used = total - mi.availMem / 1048576L
        var src = "/proc/stat"
        var per = procStat()
        if (per == null) {
            per = freqLoad()
            src = "frequency (estimated)"
        }
        if (per == null) {
            val la = loadAvg()
            if (la != null) {
                per = DoubleArray(cores()) { la }
                src = "loadavg (estimated)"
            } else {
                src = "none"
            }
        }
        val cpus = JSONArray()
        var avg = -1.0
        if (per != null) {
            for (v in per) cpus.put(Math.round(v))
            avg = per.average()
        }
        val t = pickTemp(ctx)
        return JSONObject()
            .put("ramTotal", total)
            .put("ramUsed", used)
            .put("cpu", Math.round(avg))
            .put("cpus", cpus)
            .put("src", src)
            .put("temp", t.first)
            .put("tsrc", t.second)
            .put("freq", curFreqMHz())
            .put("cores", cores())
            .put("hw", Build.HARDWARE)
            .put("model", Build.MODEL)
            .toString()
    }

    fun hasUsage(ctx: Context): Boolean = try {
        val ao = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        ao.checkOpNoThrow("android:get_usage_stats", Process.myUid(), ctx.packageName) == AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) { false }

    /** Apps that are (or recently were) running. RAM per app is -1 when Android hides it. */
    fun running(ctx: Context): JSONArray {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val pm = ctx.packageManager
        val mem = LinkedHashMap<String, Int>()
        try {
            am.runningAppProcesses?.forEach { p ->
                var mb = -1
                try {
                    val i = am.getProcessMemoryInfo(intArrayOf(p.pid))
                    if (i.isNotEmpty() && i[0].totalPss > 0) mb = i[0].totalPss / 1024
                } catch (e: Exception) { }
                p.pkgList?.forEach { pkg ->
                    val old = mem[pkg] ?: -1
                    mem[pkg] = if (mb > old) mb else old
                }
            }
        } catch (e: Exception) { }
        if (hasUsage(ctx)) {
            try {
                val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                val now = System.currentTimeMillis()
                usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 86400000L, now)
                    ?.filter { it.lastTimeUsed > now - 3600000L }
                    ?.sortedByDescending { it.lastTimeUsed }
                    ?.forEach { if (!mem.containsKey(it.packageName)) mem[it.packageName] = -1 }
            } catch (e: Exception) { }
        }
        val skip = HashSet<String>()
        skip.add(ctx.packageName)
        skip.add("android")
        skip.add("com.android.systemui")
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        pm.queryIntentActivities(home, 0).forEach { skip.add(it.activityInfo.packageName) }
        val arr = JSONArray()
        for ((pkg, mb) in mem) {
            if (skip.contains(pkg)) continue
            if (pm.getLaunchIntentForPackage(pkg) == null && pm.getLeanbackLaunchIntentForPackage(pkg) == null) continue
            val label = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { pkg }
            arr.put(JSONObject().put("name", label).put("pkg", pkg).put("mb", mb))
            if (arr.length() >= 25) break
        }
        return arr
    }

    fun kill(ctx: Context, pkg: String) {
        try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.killBackgroundProcesses(pkg)
        } catch (e: Exception) { }
    }

    fun killAll(ctx: Context): Int {
        val a = running(ctx)
        var n = 0
        for (i in 0 until a.length()) {
            kill(ctx, a.getJSONObject(i).getString("pkg"))
            n++
        }
        return n
    }

    fun net(ctx: Context): String {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val ni = cm.activeNetworkInfo
            when {
                ni == null || !ni.isConnected -> "none"
                ni.type == ConnectivityManager.TYPE_WIFI -> "wifi"
                ni.type == ConnectivityManager.TYPE_ETHERNET -> "eth"
                else -> "other"
            }
        } catch (e: Exception) { "none" }
    }

    fun bt(): Boolean = try { BluetoothAdapter.getDefaultAdapter()?.isEnabled == true } catch (e: Exception) { false }
}
