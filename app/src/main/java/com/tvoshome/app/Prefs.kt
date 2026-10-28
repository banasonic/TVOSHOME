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

    fun keyName(code: Int): String = KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")
}
