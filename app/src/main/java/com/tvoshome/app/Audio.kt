package com.tvoshome.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowManager
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

/** Shared state between the settings, the permission screen, the capture service and the strip. */
object AudioHub {
    @Volatile var resultCode = 0
    @Volatile var projData: Intent? = null
    @Volatile var running = false
    @Volatile var status = ""
    @Volatile var plan = ""
    @Volatile var listener: ((String, Boolean) -> Unit)? = null
}

/**
 * Where the sound comes from. Android gives apps no way to tap the finished output of a TV tuner / HDMI path,
 * so the choice is between what the platform exposes:
 *  - "submix": REMOTE_SUBMIX, only when the app is installed as a system / privileged app;
 *  - "input": a wired audio input (USB sound card, line-in, HDMI-in, TV tuner) that the box lists as an input device;
 *  - "projection": other apps' media output (Android 10+, asks for a permission, apps may refuse);
 *  - "mic": the microphone (hears the speakers).
 */
object AudioSources {
    // device types: tuner, HDMI, HDMI ARC, USB device, USB headset, line analog, line digital, aux line
    private val WIRED = intArrayOf(17, 9, 10, 11, 22, 5, 6, 19)
    private const val BUILTIN_MIC = 15

    private fun am(ctx: Context) = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun inputs(ctx: Context): List<AudioDeviceInfo> =
        try { am(ctx).getDevices(AudioManager.GET_DEVICES_INPUTS).toList() } catch (e: Throwable) { emptyList() }

    fun describe(ctx: Context): String {
        val l = inputs(ctx)
        if (l.isEmpty()) return "none"
        return l.joinToString(", ") { it.type.toString() + ":" + (it.productName ?: "") }
    }

    fun pickWired(ctx: Context): AudioDeviceInfo? {
        val l = inputs(ctx)
        for (t in WIRED) for (d in l) if (d.type == t) return d
        return null
    }

    private fun submixWorks(): Boolean = try {
        val r = AudioRecord(8, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, 8192)
        val ok = r.state == AudioRecord.STATE_INITIALIZED
        r.release()
        ok
    } catch (e: Throwable) { false }

    /** mode: 0 auto, 1 device audio (Android 10+), 2 wired input, 3 microphone. Also writes a status line. */
    fun plan(ctx: Context, mode: Int): String {
        when (mode) {
            1 -> {
                return if (Build.VERSION.SDK_INT >= 29) { AudioHub.status = "source: device audio (needs the capture permission)"; "projection" }
                else { AudioHub.status = "device audio capture needs Android 10+; use a wired input instead"; "none" }
            }
            2 -> {
                val d = pickWired(ctx)
                return if (d != null) { AudioHub.status = "source: wired input " + (d.productName ?: d.type); "input" }
                else { AudioHub.status = "no USB / line / HDMI / tuner audio input found (inputs: " + describe(ctx) + ")"; "none" }
            }
            3 -> { AudioHub.status = "source: microphone"; return "mic" }
        }
        if (submixWorks()) { AudioHub.status = "source: system output (privileged)"; return "submix" }
        val d = pickWired(ctx)
        if (d != null) { AudioHub.status = "source: wired input " + (d.productName ?: d.type); return "input" }
        if (Build.VERSION.SDK_INT >= 29) { AudioHub.status = "source: device audio (needs the capture permission)"; return "projection" }
        for (x in inputs(ctx)) if (x.type == BUILTIN_MIC) { AudioHub.status = "source: microphone (Android 9 cannot capture the device audio; this hears the speakers)"; return "mic" }
        AudioHub.status = "Android 9 has no way to capture the device audio. Connect a USB / line-in audio input (inputs: " + describe(ctx) + ")"
        return "none"
    }
}

/** Offline speech recognition (Vosk). Used through reflection so the app still builds if the library is unavailable. */
object VoskEngine {
    private val modelCls: Class<*>? by lazy { try { Class.forName("org.vosk.Model") } catch (e: Throwable) { null } }
    private val recCls: Class<*>? by lazy { try { Class.forName("org.vosk.Recognizer") } catch (e: Throwable) { null } }

    val available: Boolean get() = modelCls != null && recCls != null

    fun loadModel(path: String): Any? = try {
        modelCls!!.getConstructor(String::class.java).newInstance(path)
    } catch (e: Throwable) {
        AudioHub.status = "model load failed: " + (e.cause?.message ?: e.javaClass.simpleName)
        null
    }

    fun recognizer(model: Any, rate: Float): Any? = try {
        recCls!!.getConstructor(modelCls!!, java.lang.Float.TYPE).newInstance(model, rate)
    } catch (e: Throwable) {
        AudioHub.status = "recognizer failed: " + (e.cause?.message ?: e.javaClass.simpleName)
        null
    }

    fun accept(r: Any, d: ByteArray, len: Int): Boolean = try {
        (recCls!!.getMethod("acceptWaveForm", ByteArray::class.java, Integer.TYPE).invoke(r, d, len) as? Boolean) ?: false
    } catch (e: Throwable) { false }

    private fun text(r: Any, method: String, key: String): String {
        return try {
            val j = recCls!!.getMethod(method).invoke(r) as? String ?: return ""
            JSONObject(j).optString(key, "").trim()
        } catch (e: Throwable) { "" }
    }

    fun result(r: Any): String = text(r, "getResult", "text")
    fun partial(r: Any): String = text(r, "getPartialResult", "partial")
    fun finalText(r: Any): String = text(r, "getFinalResult", "text")

    fun reset(r: Any) {
        try { recCls!!.getMethod("reset").invoke(r) } catch (e: Throwable) { }
    }

    fun close(o: Any?) {
        if (o == null) return
        try { o.javaClass.getMethod("close").invoke(o) } catch (e: Throwable) { }
    }
}

/** Downloads (once) and unpacks the small English model (~40 MB). */
object VoskModelStore {
    private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"

    fun dir(ctx: Context) = File(ctx.filesDir, "vosk/model-en")

    fun ready(ctx: Context): Boolean = File(dir(ctx), "am/final.mdl").exists() || File(dir(ctx), "conf/model.conf").exists()

    @Synchronized
    fun ensure(ctx: Context): Boolean {
        if (ready(ctx)) return true
        val root = File(ctx.filesDir, "vosk")
        root.mkdirs()
        val zip = File(root, "model.zip.part")
        try {
            AudioHub.status = "downloading the English speech model (~40 MB)"
            val c = URL(MODEL_URL).openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.instanceFollowRedirects = true
            if (c.responseCode !in 200..299) {
                AudioHub.status = "model download failed (http " + c.responseCode + ")"
                c.disconnect()
                return false
            }
            val total = c.contentLengthLong
            var done = 0L
            c.inputStream.use { ins ->
                FileOutputStream(zip).use { out ->
                    val buf = ByteArray(32768)
                    var lastReport = 0L
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastReport > 2_000_000L) {
                            lastReport = done
                            AudioHub.status = "downloading the English speech model: " + (done / 1_000_000L) + (if (total > 0) "/" + (total / 1_000_000L) else "") + " MB"
                        }
                    }
                }
            }
            c.disconnect()
            AudioHub.status = "unpacking the model"
            val dest = dir(ctx)
            dest.deleteRecursively()
            dest.mkdirs()
            val destPath = dest.canonicalPath
            ZipInputStream(zip.inputStream().buffered()).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    val rel = e.name.substringAfter('/', "")
                    if (rel.isEmpty()) continue
                    val f = File(dest, rel)
                    if (!f.canonicalPath.startsWith(destPath)) continue
                    if (e.isDirectory) { f.mkdirs(); continue }
                    f.parentFile?.mkdirs()
                    FileOutputStream(f).use { o -> zin.copyTo(o) }
                }
            }
        } catch (e: Exception) {
            AudioHub.status = "model download failed: " + e.javaClass.simpleName
            return false
        } finally {
            zip.delete()
        }
        val ok = ready(ctx)
        AudioHub.status = if (ok) "model ready" else "model files missing after unpacking"
        return ok
    }
}

/**
 * Cheap "is somebody talking?" detector for 16 kHz audio (20 ms frames). Looks at energy over the
 * noise floor, how much of the sound sits in the voice band and the 2-8 Hz rhythm of syllables, so
 * music, effects and silence are not sent to the recogniser (this is also what keeps the CPU idle).
 */
class SpeechGate {
    private val n = 512
    private val win = FloatArray(n) { (0.5 - 0.5 * Math.cos(2.0 * Math.PI * it / (n - 1))).toFloat() }
    private val re = FloatArray(n)
    private val im = FloatArray(n)
    private var floorDb = -55f
    private val env = FloatArray(50)
    private var envPos = 0
    private var envN = 0
    private var modOk = false
    private var frames = 0
    private val recent = BooleanArray(10)
    private var recentPos = 0
    private var hang = 0
    private var modBad = 0
    private val cosT = Array(7) { f -> DoubleArray(50) { Math.cos(2.0 * Math.PI * (f + 2) * it / 50.0) } }
    private val sinT = Array(7) { f -> DoubleArray(50) { Math.sin(2.0 * Math.PI * (f + 2) * it / 50.0) } }
    var speaking = false
        private set

    private fun fft() {
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wlr = Math.cos(ang).toFloat()
            val wli = Math.sin(ang).toFloat()
            val half = len / 2
            var i = 0
            while (i < n) {
                var wr = 1f
                var wi = 0f
                for (k in 0 until half) {
                    val a = i + k
                    val b = a + half
                    val vr = re[b] * wr - im[b] * wi
                    val vi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - vr
                    im[b] = im[a] - vi
                    re[a] = re[a] + vr
                    im[a] = im[a] + vi
                    val nwr = wr * wlr - wi * wli
                    wi = wr * wli + wi * wlr
                    wr = nwr
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun speechFrame(x: ShortArray, off: Int): Boolean {
        var e = 0.0
        var zc = 0
        var prev = 0f
        for (i in 0 until 320) {
            val v = x[off + i] / 32768f
            e += v * v
            if (i > 0 && ((v >= 0f) != (prev >= 0f))) zc++
            prev = v
        }
        val em = e / 320.0
        val db = (10.0 * Math.log10(em + 1e-10)).toFloat()
        val zcr = zc / 320f
        if (db < floorDb) floorDb = db else floorDb += 0.01f
        if (floorDb < -70f) floorDb = -70f
        if (floorDb > -35f) floorDb = -35f
        env[envPos] = Math.sqrt(em).toFloat()
        envPos = (envPos + 1) % 50
        if (envN < 50) envN++
        for (i in 0 until n) {
            re[i] = if (i < 320) (x[off + i] / 32768f) * win[i] else 0f
            im[i] = 0f
        }
        fft()
        var speechE = 0.0
        var totalE = 0.0
        for (k in 3..255) {
            val p = (re[k] * re[k] + im[k] * im[k]).toDouble()
            totalE += p
            if (k in 10..109) speechE += p
        }
        val ratio = if (totalE > 0.0) (speechE / totalE).toFloat() else 0f
        return db > floorDb + 9f && db > -50f && ratio > 0.55f && zcr < 0.30f
    }

    /** Share of the 1-second loudness curve that moves at 2-8 Hz (the rhythm of syllables). */
    private fun updateRhythm() {
        if (envN < 50) return
        var mean = 0f
        for (v in env) mean += v
        mean /= 50f
        val xs = DoubleArray(50)
        var tot = 0.0
        for (i in 0 until 50) {
            xs[i] = (env[(envPos + i) % 50] - mean).toDouble()
            tot += xs[i] * xs[i]
        }
        if (tot < 1e-12) { modOk = false; return }
        var band = 0.0
        for (f in 0 until 7) {
            var cr = 0.0
            var ci = 0.0
            for (i in 0 until 50) {
                cr += xs[i] * cosT[f][i]
                ci += xs[i] * sinT[f][i]
            }
            band += cr * cr + ci * ci
        }
        modOk = band / (50.0 * tot) > 0.28
    }

    /** One 20 ms frame (320 samples). 0 = quiet, 1 = talking, 2 = talking just started, 3 = talking just ended. */
    fun process(x: ShortArray, off: Int): Int {
        val sp = speechFrame(x, off)
        recent[recentPos] = sp
        recentPos = (recentPos + 1) % 10
        frames++
        if (frames % 10 == 0) updateRhythm()
        var cnt = 0
        for (b in recent) if (b) cnt++
        if (!speaking) {
            if (cnt >= 5 && modOk) {
                speaking = true
                hang = 0
                modBad = 0
                return 2
            }
            return 0
        }
        if (sp) hang = 0 else hang++
        if (!modOk) modBad++ else modBad = 0
        if (hang >= 30 || modBad >= 100) {
            speaking = false
            return 3
        }
        return 1
    }
}

/** Foreground service that listens to the device's own audio output and feeds the recogniser. */
class AudioCaptureService : Service() {
    private var proj: MediaProjection? = null
    private var worker: Thread? = null
    @Volatile private var stop = false
    private val junk = setOf("the", "a", "huh", "uh", "um", "hmm", "ah", "oh", "mm")

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startFg(plan: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("audio_tr", "Audio translate", NotificationManager.IMPORTANCE_LOW))
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "audio_tr") else Notification.Builder(this)
        b.setContentTitle("Audio translate").setContentText("Listening").setSmallIcon(android.R.drawable.ic_btn_speak_now)
        try {
            if (plan == "projection" && Build.VERSION.SDK_INT >= 29) startForeground(7711, b.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            else if (Build.VERSION.SDK_INT >= 30) startForeground(7711, b.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(7711, b.build())
        } catch (e: Exception) {
            AudioHub.status = "could not start the foreground service: " + e.javaClass.simpleName
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val plan = if (AudioHub.plan.isNotEmpty()) AudioHub.plan else AudioSources.plan(this, Prefs.audioSrc(this))
        AudioHub.plan = plan
        startFg(plan)
        if (AudioHub.running) return START_NOT_STICKY
        if (plan == "none") {
            stopSelf()
            return START_NOT_STICKY
        }
        if (plan == "projection") {
            val data = AudioHub.projData
            if (Build.VERSION.SDK_INT < 29 || data == null) {
                AudioHub.status = "no capture permission yet"
                stopSelf()
                return START_NOT_STICKY
            }
            try {
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val p = mpm.getMediaProjection(AudioHub.resultCode, data)
                p.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() { stop = true }
                }, Handler(Looper.getMainLooper()))
                proj = p
            } catch (e: Exception) {
                AudioHub.status = "capture permission failed: " + e.javaClass.simpleName
                stopSelf()
                return START_NOT_STICKY
            }
        }
        stop = false
        val t = Thread({ runCapture(plan) }, "audio-cap")
        t.priority = Thread.NORM_PRIORITY
        worker = t
        t.start()
        return START_NOT_STICKY
    }

    private fun openRecord(plan: String): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bytes = maxOf(min * 2, 64000)
        return when (plan) {
            "submix" -> AudioRecord(8, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bytes)
            "input", "mic" -> {
                val r = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bytes)
                if (plan == "input") {
                    val d = AudioSources.pickWired(applicationContext)
                    if (d != null && Build.VERSION.SDK_INT >= 23) r.setPreferredDevice(d)
                }
                r
            }
            else -> {
                val p = proj ?: return null
                val cfg = AudioPlaybackCaptureConfiguration.Builder(p)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
                val fmt = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(16000)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
                AudioRecord.Builder().setAudioFormat(fmt).setBufferSizeInBytes(bytes).setAudioPlaybackCaptureConfig(cfg).build()
            }
        }
    }

    private fun emit(t0: String, final: Boolean) {
        val t = t0.trim()
        if (t.length < 3) return
        if (final && t.lowercase() in junk) return
        AudioHub.listener?.invoke(t, final)
    }

    private fun runCapture(plan: String) {
        var model: Any? = null
        var recog: Any? = null
        var rec: AudioRecord? = null
        AudioHub.running = true
        try {
            if (!VoskEngine.available) { AudioHub.status = "speech engine is not included in this build"; return }
            if (!VoskModelStore.ensure(applicationContext)) return
            AudioHub.status = "loading the speech model"
            model = VoskEngine.loadModel(VoskModelStore.dir(applicationContext).absolutePath) ?: return
            recog = VoskEngine.recognizer(model, 16000f) ?: return
            rec = openRecord(plan)
            if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
                AudioHub.status = "could not open the audio input (" + plan + "). inputs: " + AudioSources.describe(applicationContext)
                return
            }
            rec.startRecording()
            AudioHub.status = "listening (" + plan + ")"
            val gate = SpeechGate()
            val buf = ShortArray(1600)
            val acc = ShortArray(4000)
            var accLen = 0
            val pre = Array(15) { ShortArray(320) }
            var preN = 0
            var prePos = 0
            val pend = ByteArray(6400)
            var pendLen = 0
            var lastPartialAt = 0L
            var lastPartial = ""
            var silentReads = 0
            val r: Any = recog

            fun flush() {
                if (pendLen <= 0) return
                val end = VoskEngine.accept(r, pend, pendLen)
                pendLen = 0
                if (end) {
                    emit(VoskEngine.result(r), true)
                    lastPartial = ""
                } else {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastPartialAt >= 450L) {
                        lastPartialAt = now
                        val pt = VoskEngine.partial(r)
                        if (pt.isNotEmpty() && pt != lastPartial) {
                            lastPartial = pt
                            emit(pt, false)
                        }
                    }
                }
            }

            fun feed(fr: ShortArray) {
                for (i in 0 until 320) {
                    val v = fr[i].toInt()
                    pend[pendLen++] = (v and 0xFF).toByte()
                    pend[pendLen++] = ((v shr 8) and 0xFF).toByte()
                }
                if (pendLen >= 3200) flush()
            }

            while (!stop) {
                val got = rec.read(buf, 0, buf.size)
                if (got <= 0) {
                    if (got < 0) break
                    continue
                }
                // some apps block capture: the stream is then pure digital silence
                var peak = 0
                for (i in 0 until got) { val a = Math.abs(buf[i].toInt()); if (a > peak) peak = a }
                if (peak == 0) { if (++silentReads == 150) AudioHub.status = "listening (" + plan + "), but only silence arrives (the app or the TV path does not allow capture)" } else {
                    if (silentReads >= 150) AudioHub.status = "listening (" + plan + ")"
                    silentReads = 0
                }
                System.arraycopy(buf, 0, acc, accLen, got)
                accLen += got
                var off = 0
                while (accLen - off >= 320) {
                    val fr = ShortArray(320)
                    System.arraycopy(acc, off, fr, 0, 320)
                    off += 320
                    val code = gate.process(fr, 0)
                    pre[prePos] = fr
                    prePos = (prePos + 1) % 15
                    if (preN < 15) preN++
                    when (code) {
                        2 -> {
                            // talking started: send the last 300 ms too so the first word is not cut
                            val start = (prePos - preN + 15) % 15
                            for (k in 0 until preN) feed(pre[(start + k) % 15])
                            preN = 0
                        }
                        1 -> feed(fr)
                        3 -> {
                            flush()
                            emit(VoskEngine.finalText(r), true)
                            VoskEngine.reset(r)
                            lastPartial = ""
                        }
                    }
                }
                if (off > 0) {
                    System.arraycopy(acc, off, acc, 0, accLen - off)
                    accLen -= off
                }
                flush()
            }
        } catch (e: Throwable) {
            AudioHub.status = "audio capture failed: " + e.javaClass.simpleName + " " + (e.message ?: "")
        } finally {
            try { rec?.stop() } catch (e: Throwable) { }
            try { rec?.release() } catch (e: Throwable) { }
            VoskEngine.close(recog)
            VoskEngine.close(model)
            AudioHub.running = false
            stopSelf()
        }
    }

    override fun onDestroy() {
        stop = true
        try { proj?.stop() } catch (e: Throwable) { }
        proj = null
        AudioHub.projData = null
        if (!AudioHub.running) AudioHub.status = "stopped"
        super.onDestroy()
    }
}

/** Transparent screen that asks for the audio permission and the capture permission (needed once per start). */
class AudioPermActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 11)
        } else {
            next()
        }
    }

    /** Wired / microphone sources only need the audio permission; device-audio capture also needs the projection dialog. */
    private fun next() {
        val plan = AudioSources.plan(this, Prefs.audioSrc(this))
        AudioHub.plan = plan
        when (plan) {
            "none" -> finish()
            "projection" -> askProjection()
            else -> { startCapture(); finish() }
        }
    }

    private fun startCapture() {
        val i = Intent(this, AudioCaptureService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        } catch (e: Exception) {
            AudioHub.status = "could not start the capture service: " + e.javaClass.simpleName
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) next()
        else {
            AudioHub.status = "the audio permission was denied"
            finish()
        }
    }

    private fun askProjection() {
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mpm.createScreenCaptureIntent(), 12)
        } catch (e: Exception) {
            AudioHub.status = "this device has no screen / audio capture permission dialog"
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 12 && resultCode == RESULT_OK && data != null) {
            AudioHub.resultCode = resultCode
            AudioHub.projData = data
            AudioHub.status = "starting"
            startCapture()
        } else {
            AudioHub.status = "capture permission was not granted"
        }
        finish()
    }
}

/** The side of the feature that lives in the accessibility service: shows the Arabic strip for what is heard. */
class AudioTranslator(private val svc: KeyService) {
    private val ctx: Context = svc
    private val ui = Handler(Looper.getMainLooper())
    private var exec: ExecutorService? = null
    private var view: TransView? = null
    private var attached = false
    @Volatile var enabled = false
    private val lock = Any()
    private var seq = 0
    @Volatile private var shownSeq = 0
    private var lastPartialReq = 0L
    private var lastPartialSent = ""
    private var preloading = false
    private var shown = ""
    private val hideRun = Runnable { detach() }

    fun configure(ask: Boolean) {
        val on = Prefs.audioOn(ctx)
        if (!on) {
            if (enabled) stop()
            return
        }
        enabled = true
        if (exec == null) exec = Executors.newSingleThreadExecutor()
        AudioHub.listener = { t, f -> onSpeech(t, f) }
        if (!VoskEngine.available) {
            AudioHub.status = "speech engine is not included in this build"
            return
        }
        if (!preloading && !VoskModelStore.ready(ctx)) {
            preloading = true
            Thread {
                VoskModelStore.ensure(ctx)
                preloading = false
            }.start()
        }
        val plan = AudioSources.plan(ctx, Prefs.audioSrc(ctx))
        AudioHub.plan = ""
        if (ask && !AudioHub.running && plan != "none") {
            val i = Intent(ctx, AudioPermActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { ctx.startActivity(i) } catch (e: Exception) { AudioHub.status = "could not open the permission screen" }
        }
    }

    fun stop() {
        enabled = false
        AudioHub.listener = null
        try { ctx.stopService(Intent(ctx, AudioCaptureService::class.java)) } catch (e: Exception) { }
        ui.post { detach() }
        exec?.shutdownNow()
        exec = null
    }

    fun shutdown() { stop() }

    fun onMode(m: String) {
        if (m == "full" || m == "standby" || m == "prompt") detach()
    }

    private fun onSpeech(text: String, final: Boolean) {
        if (!enabled) return
        val t = text.trim()
        if (t.length < 3) return
        val now = SystemClock.elapsedRealtime()
        if (!final) {
            if (now - lastPartialReq < 600L || t == lastPartialSent) return
            lastPartialReq = now
            lastPartialSent = t
        }
        val my = synchronized(lock) { seq++; seq }
        val ex = exec ?: return
        ex.execute {
            val ar = try { Translator.toArabic(t, "en") } catch (e: Exception) { null }
            if (ar != null && ar.isNotEmpty() && my > shownSeq) {
                shownSeq = my
                ui.post { show(ar, final, t.length) }
            }
        }
    }

    private fun show(ar: String, final: Boolean, chars: Int) {
        val m = svc.mode
        if (m == "full" || m == "standby" || m == "prompt") return
        val dm = DisplayMetrics()
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm.defaultDisplay.getRealMetrics(dm)
        val sw = dm.widthPixels
        val sh = dm.heightPixels
        val v = view ?: TransView(ctx).also { view = it }
        v.style(Prefs.transSize(ctx), Prefs.transColor(ctx), Prefs.transBg(ctx), false)
        v.setText(ar)
        val left = (sw * 0.1f).toInt()
        val right = (sw * 0.9f).toInt()
        val bottom = (sh * 0.92f).toInt()
        val baseTop = (sh * 0.82f).toInt()
        val need = v.needHeight(right - left)
        val top = if (need > bottom - baseTop) maxOf(0, bottom - need) else baseTop
        val p = WindowManager.LayoutParams(
            right - left, bottom - top,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = left
        p.y = top
        try {
            if (v.parent != null) wm.updateViewLayout(v, p) else wm.addView(v, p)
            attached = true
        } catch (e: Exception) {
            attached = false
        }
        shown = ar
        val hold = if (final) (2200 + 60 * chars).coerceIn(2500, 7000) else 2500
        ui.removeCallbacks(hideRun)
        ui.postDelayed(hideRun, hold.toLong())
    }

    private fun detach() {
        ui.removeCallbacks(hideRun)
        val v = view ?: return
        if (v.parent == null) return
        try { (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) } catch (e: Exception) { }
        attached = false
        shown = ""
    }
}
