package com.tvoshome.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.OpenableColumns

/** Transparent helper that lets the user pick an audio file from the device for notifications. */
class SoundPickActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "audio/*"
        try {
            startActivityForResult(i, 1)
        } catch (e: Exception) {
            KeyService.instance?.setMode("full")
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val u = data?.data
        if (resultCode == RESULT_OK && u != null) {
            try {
                contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) { }
            var name = "audio"
            try {
                contentResolver.query(u, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val ix = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (ix >= 0) name = c.getString(ix)
                    }
                }
            } catch (e: Exception) { }
            Prefs.setSound(this, u.toString(), name)
            val safe = name.replace("'", " ").replace("\\", " ")
            KeyService.instance?.js("window.onSoundPicked&&onSoundPicked('$safe')")
        }
        KeyService.instance?.setMode("full")
        finish()
    }
}
