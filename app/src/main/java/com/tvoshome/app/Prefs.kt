package com.tvoshome.app

import android.content.Context
import android.view.KeyEvent

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    fun keyCode(c: Context): Int = sp(c).getInt("key_code", KeyEvent.KEYCODE_MENU)
    fun setKeyCode(c: Context, v: Int) { sp(c).edit().putInt("key_code", v).apply() }

    fun longMs(c: Context): Int = sp(c).getInt("long_ms", 700)
    fun setLongMs(c: Context, v: Int) { sp(c).edit().putInt("long_ms", v.coerceIn(400, 2000)).apply() }

    fun consume(c: Context): Boolean = sp(c).getBoolean("consume", false)
    fun setConsume(c: Context, v: Boolean) { sp(c).edit().putBoolean("consume", v).apply() }

    fun soundUri(c: Context): String? = sp(c).getString("sound_uri", null)
    fun soundName(c: Context): String = sp(c).getString("sound_name", "") ?: ""
    fun setSound(c: Context, uri: String, name: String) {
        sp(c).edit().putString("sound_uri", uri).putString("sound_name", name).apply()
    }

    fun autoMin(c: Context): Int = sp(c).getInt("auto_min", 0)
    fun autoWifi(c: Context): Boolean = sp(c).getBoolean("auto_wifi", true)
    fun setAutoMin(c: Context, v: Int) { sp(c).edit().putInt("auto_min", v.coerceAtLeast(0)).apply() }
    fun setAutoWifi(c: Context, v: Boolean) { sp(c).edit().putBoolean("auto_wifi", v).apply() }
    fun wifiOffByUs(c: Context): Boolean = sp(c).getBoolean("wifi_off_by_us", false)
    fun setWifiOffByUs(c: Context, v: Boolean) { sp(c).edit().putBoolean("wifi_off_by_us", v).apply() }

    fun highFps(c: Context): Boolean = sp(c).getBoolean("high_fps", true)
    fun setHighFps(c: Context, v: Boolean) { sp(c).edit().putBoolean("high_fps", v).apply() }

    fun logoOn(c: Context): Boolean = sp(c).getBoolean("logo_on", false)
    fun logoStyle(c: Context): Int = sp(c).getInt("logo_style", 0)
    fun setLogo(c: Context, on: Boolean, style: Int) { sp(c).edit().putBoolean("logo_on", on).putInt("logo_style", style.coerceIn(0, 2)).apply() }

    /** Cover rectangle as fractions of the screen: left, top, width, height. */
    fun logoRect(c: Context): FloatArray {
        val p = sp(c)
        return floatArrayOf(p.getFloat("logo_fx", 0.815f), p.getFloat("logo_fy", 0.03f), p.getFloat("logo_fw", 0.16f), p.getFloat("logo_fh", 0.13f))
    }
    fun setLogoRect(c: Context, r: FloatArray) {
        sp(c).edit().putFloat("logo_fx", r[0]).putFloat("logo_fy", r[1]).putFloat("logo_fw", r[2]).putFloat("logo_fh", r[3]).apply()
    }
    /** Where the frequency text was last seen (fractions: left, top, right, bottom), or null. */
    fun logoAnchor(c: Context): FloatArray? {
        val p = sp(c)
        if (!p.contains("logo_ax")) return null
        return floatArrayOf(p.getFloat("logo_ax", 0f), p.getFloat("logo_ay", 0f), p.getFloat("logo_az", 0f), p.getFloat("logo_aw", 0f))
    }
    fun setLogoAnchor(c: Context, a: FloatArray) {
        sp(c).edit().putFloat("logo_ax", a[0]).putFloat("logo_ay", a[1]).putFloat("logo_az", a[2]).putFloat("logo_aw", a[3]).apply()
    }

    fun resetLogoRect(c: Context) {
        sp(c).edit().remove("logo_fx").remove("logo_fy").remove("logo_fw").remove("logo_fh").apply()
    }

    fun thermalPath(c: Context): String = sp(c).getString("thermal_path", "") ?: ""
    fun setThermalPath(c: Context, v: String) { sp(c).edit().putString("thermal_path", v.trim()).apply() }

    fun keyName(code: Int): String = KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")
}
