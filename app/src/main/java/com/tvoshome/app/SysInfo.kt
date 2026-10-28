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

    /** CPU/SoC temperature in Celsius, or -1 when unreadable. */
    fun temp(): Double {
        var best = -1.0
        var bestScore = -1
        val zones = File("/sys/class/thermal").listFiles()?.filter { it.name.startsWith("thermal_zone") } ?: emptyList()
        for (z in zones) {
            val raw = readLong(File(z, "temp")) ?: continue
            val c = if (raw > 1000) raw / 1000.0 else raw.toDouble()
            if (c < 5.0 || c > 130.0) continue
            val type = (readText(File(z, "type")) ?: "").lowercase()
            val score = when {
                type.contains("cpu") || type.contains("soc") || type.contains("big") ||
                    type.contains("little") || type.contains("tsens") || type.contains("core") -> 3
                type.contains("gpu") || type.contains("ddr") || type.contains("battery") -> 1
                else -> 2
            }
            if (score > bestScore) { bestScore = score; best = c }
        }
        if (best > 0) return best
        val hw = File("/sys/class/hwmon").listFiles() ?: emptyArray()
        for (h in hw) {
            val raw = readLong(File(h, "temp1_input")) ?: continue
            val c = if (raw > 1000) raw / 1000.0 else raw.toDouble()
            if (c in 5.0..130.0) return c
        }
        return -1.0
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
            src = if (per != null) "frequency (estimated)" else "none"
        }
        val cpus = JSONArray()
        var avg = -1.0
        if (per != null) {
            for (v in per) cpus.put(Math.round(v))
            avg = per.average()
        }
        val f = (readLong(File("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq")) ?: 0L) / 1000L
        return JSONObject()
            .put("ramTotal", total)
            .put("ramUsed", used)
            .put("cpu", Math.round(avg))
            .put("cpus", cpus)
            .put("src", src)
            .put("temp", temp())
            .put("freq", f)
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
