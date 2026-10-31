package com.tvoshome.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Serves https://appassets.androidplatform.net/appicon/<package> as a PNG of the app's launcher icon. */
class AppIconHandler(private val ctx: Context) : WebViewAssetLoader.PathHandler {
    private val cache = LruCache<String, ByteArray>(48)

    override fun handle(path: String): WebResourceResponse? {
        val pkg = path.trim('/')
        if (pkg.isEmpty()) return null
        var bytes = cache.get(pkg)
        if (bytes == null) {
            bytes = render(pkg)
            if (bytes == null) return null
            cache.put(pkg, bytes)
        }
        return WebResourceResponse("image/png", null, ByteArrayInputStream(bytes))
    }

    private fun render(pkg: String): ByteArray? {
        return try {
            val d = ctx.packageManager.getApplicationIcon(pkg)
            val size = 96
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            d.setBounds(0, 0, size, size)
            d.draw(c)
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            bmp.recycle()
            out.toByteArray()
        } catch (e: Exception) { null }
    }
}
