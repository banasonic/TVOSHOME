package com.tvoshome.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Simple remote-friendly setup screen. */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private var capturing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(56, 40, 56, 40)

        val title = TextView(this)
        title.text = "TVOSHOME"
        title.textSize = 28f
        col.addView(title)

        status = TextView(this)
        status.textSize = 16f
        status.setPadding(0, 16, 0, 24)
        col.addView(status)

        col.addView(button("1. Enable the service (Accessibility)") { open(Settings.ACTION_ACCESSIBILITY_SETTINGS) })
        col.addView(button("2. Allow usage access (optional - for the apps list)") { open(Settings.ACTION_USAGE_ACCESS_SETTINGS) })
        col.addView(button("Keep alive: disable battery optimization") { open(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) })
        col.addView(button("3. Record the toggle key from your remote") {
            capturing = true
            status.text = "Press the key on your remote now...\n(BACK cancels. HOME can't be recorded by apps.)"
        })
        col.addView(button("Long-press shorter (-100 ms)") {
            Prefs.setLongMs(this, Prefs.longMs(this) - 100)
            refresh()
        })
        col.addView(button("Long-press longer (+100 ms)") {
            Prefs.setLongMs(this, Prefs.longMs(this) + 100)
            refresh()
        })
        col.addView(button("Show / hide the overlay now") {
            val s = KeyService.instance
            if (s == null) Toast.makeText(this, "Enable the service first", Toast.LENGTH_LONG).show() else s.toggle()
        })

        val help = TextView(this)
        help.textSize = 14f
        help.setPadding(0, 24, 0, 0)
        help.text = "Hold the chosen key (in any app) to show the cards. Hold it again, press BACK, or tap an empty area to hide them."
        col.addView(help)

        val sv = ScrollView(this)
        sv.addView(col)
        setContentView(sv)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun button(text: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.setOnClickListener { onClick() }
        return b
    }

    private fun open(action: String) {
        try {
            startActivity(Intent(action))
        } catch (e: Exception) {
            Toast.makeText(this, "Settings screen not available on this device", Toast.LENGTH_LONG).show()
        }
    }

    private fun refresh() {
        val on = KeyService.instance != null
        status.text = "Service: " + (if (on) "ENABLED" else "OFF - enable it in Accessibility") +
            "\nUsage access: " + (if (SysInfo.hasUsage(this)) "granted" else "not granted (optional)") +
            "\nToggle key: " + Prefs.keyName(Prefs.keyCode(this)) +
            "  |  long press " + Prefs.longMs(this) + " ms"
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (capturing) {
            capturing = false
            if (keyCode != KeyEvent.KEYCODE_BACK) Prefs.setKeyCode(this, keyCode)
            refresh()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
