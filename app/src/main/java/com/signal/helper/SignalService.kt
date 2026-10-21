package com.signal.helper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

class SignalService : Service() {
    private lateinit var wm: WindowManager
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vd: VirtualDisplay? = null
    private var bgThread: HandlerThread? = null
    @Volatile private var last: Bitmap? = null
    private var lastT = 0L
    private var tts: TextToSpeech? = null
    private val ui = Handler(Looper.getMainLooper())

    private var tf = 60
    private var step = 0
    private var wins = 0
    private var losses = 0
    private var startStake = 1.0
    private var maxSteps = 5
    private val pay = 0.8
    private var left = 0

    private lateinit var bubble: TextView
    private lateinit var panel: LinearLayout
    private lateinit var stepTv: TextView
    private lateinit var sigTv: TextView
    private lateinit var infoTv: TextView

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        startStake = intent.getDoubleExtra("stake", 1.0)
        maxSteps = max(1, intent.getIntExtra("steps", 5))
        startForegroundNow()
        val code = intent.getIntExtra("code", 0)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>("data")
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(code, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { ui.post { stopSelf() } }
        }, ui)
        setupCapture()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        setupOverlay()
        tts = TextToSpeech(this) { s ->
            if (s == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                say("Screen share is on. Now go to your trading app.")
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundNow() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("sig", "Signal Helper", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "sig")
            .setContentTitle("Signal Helper chal raha hai")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else startForeground(1, n)
    }

    private fun setupCapture() {
        val dm = resources.displayMetrics
        val cw = dm.widthPixels / 2
        val ch = dm.heightPixels / 2
        bgThread = HandlerThread("cap").also { it.start() }
        val bg = Handler(bgThread!!.looper)
        reader = ImageReader.newInstance(cw, ch, PixelFormat.RGBA_8888, 2)
        reader!!.setOnImageAvailableListener({ r ->
            val img = try { r.acquireLatestImage() } catch (e: Exception) { null }
            if (img != null) {
                try {
                    val now = System.currentTimeMillis()
                    if (now - lastT >= 400) {
                        lastT = now
                        val p = img.planes[0]
                        val full = Bitmap.createBitmap(p.rowStride / p.pixelStride, ch, Bitmap.Config.ARGB_8888)
                        full.copyPixelsFromBuffer(p.buffer)
                        val crop = Bitmap.createBitmap(full, 0, 0, cw, ch)
                        last = Bitmap.createScaledBitmap(crop, 320, 320 * ch / cw, true)
                    }
                } catch (e: Exception) {
                } finally { img.close() }
            }
        }, bg)
        vd = projection?.createVirtualDisplay("cap", cw, ch, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, null)
    }

    private fun lp(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun oval(c: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c) }
    private fun round(c: Int) = GradientDrawable().apply { cornerRadius = 36f; setColor(c) }

    private fun btn(t: String, col: Int, f: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; setTextColor(Color.WHITE); background = round(col)
        setPadding(24, 8, 24, 8); setOnClickListener { f() }
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(6, 6, 6, 6) }
    }

    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

    private fun setupOverlay() {
        bubble = TextView(this).apply {
            text = "📡"; textSize = 24f; gravity = Gravity.CENTER; background = oval(0xFF5B4BD6.toInt())
        }
        val bp = lp(150, 150).apply { x = 0; y = 400 }
        var dx = 0f; var dy = 0f; var sx = 0; var sy = 0; var moved = false
        bubble.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; sx = bp.x; sy = bp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val mx = (e.rawX - dx).toInt(); val my = (e.rawY - dy).toInt()
                    if (abs(mx) > 12 || abs(my) > 12) moved = true
                    bp.x = sx + mx; bp.y = sy + my; wm.updateViewLayout(bubble, bp)
                }
                MotionEvent.ACTION_UP -> if (!moved) togglePanel()
            }
            true
        }
        wm.addView(bubble, bp)

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24)
            background = round(0xF2262243.toInt()); visibility = View.GONE
        }
        stepTv = TextView(this).apply { setTextColor(0xFF9A96BD.toInt()); textSize = 14f; gravity = Gravity.CENTER }
        sigTv = TextView(this).apply { text = "Wait"; setTextColor(Color.WHITE); textSize = 40f; gravity = Gravity.CENTER }
        infoTv = TextView(this).apply { text = "Trading chart kholo, phir Signal do dabao."; setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.CENTER }
        panel.addView(stepTv); panel.addView(sigTv); panel.addView(infoTv)
        val r1 = row()
        listOf("5s" to 5, "10s" to 10, "15s" to 15, "1m" to 60, "5m" to 300).forEach { (t, s) ->
            r1.addView(btn(t, 0xFF3A3562.toInt()) { tf = s; infoTv.text = "Time frame: $t" })
        }
        panel.addView(r1)
        val r2 = row(); r2.addView(btn("Signal do", 0xFF5B4BD6.toInt()) { giveSignal() }); panel.addView(r2)
        val r3 = row()
        r3.addView(btn("Win", 0xFF12A150.toInt()) { record(true) })
        r3.addView(btn("Loss", 0xFFE0392D.toInt()) { record(false) })
        r3.addView(btn("Band", 0xFF555555.toInt()) { stopSelf() })
        panel.addView(r3)
        val w = (resources.displayMetrics.widthPixels * 0.92).toInt()
        val pp = lp(w, WindowManager.LayoutParams.WRAP_CONTENT).apply { x = (resources.displayMetrics.widthPixels - w) / 2; y = 150 }
        wm.addView(panel, pp)
        showStep()
    }

    private fun togglePanel() {
        panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun stake() = startStake * (1 + pay).pow(step)
    private fun showStep() { stepTv.text = "Step ${step + 1} / $maxSteps  |  Stake $" + String.format("%.2f", stake()) }
    private fun say(t: String) { tts?.speak(t, TextToSpeech.QUEUE_FLUSH, null, "s") }
    private fun label(s: Int) = if (s >= 60) "${s / 60} minute" else "$s seconds"

    private fun giveSignal() {
        ui.removeCallbacksAndMessages(null)
        infoTv.text = "Chart dekh raha hoon..."
        panel.visibility = View.INVISIBLE
        bubble.visibility = View.INVISIBLE
        ui.postDelayed({
            val bmp = last
            panel.visibility = View.VISIBLE
            bubble.visibility = View.VISIBLE
            evaluate(bmp)
        }, 1000)
    }

    private fun analyze(bmp: Bitmap): Double? {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h)
        val y0 = (h * 0.12).toInt(); val y1 = (h * 0.72).toInt(); val x1 = (w * 0.82).toInt()
        val cols = ArrayList<Double>()
        for (x in 0 until x1) {
            var n = 0; var sy = 0L
            for (y in y0 until y1) {
                val c = px[y * w + x]
                val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
                val grn = g > 140 && g > r * 1.35 && g > b * 1.25
                val red = r > 170 && r > g * 1.3 && r > b * 1.5
                if (grn || red) { n++; sy += y }
            }
            if (n >= 2) cols.add(sy.toDouble() / n)
        }
        if (cols.size < 12) return null
        val k = max(4, cols.size / 4)
        val rec = cols.takeLast(k)
        val prev = cols.subList(cols.size - 2 * k, cols.size - k)
        return prev.average() - rec.average()
    }

    private fun evaluate(bmp: Bitmap?) {
        val diff = if (bmp == null) null else analyze(bmp)
        if (diff == null) {
            sigTv.text = "Wait"; sigTv.setTextColor(Color.WHITE)
            infoTv.text = "Chart nahi mila. Chart bada karo aur candles saaf dikhao."
            return
        }
        if (abs(diff) < 1.5) {
            sigTv.text = "Wait"; sigTv.setTextColor(Color.WHITE)
            infoTv.text = "Market side mein hai. Thoda ruko."
            say("Wait. Market is sideways."); return
        }
        val dir = if (diff > 0) "Buy" else "Sell"
        sigTv.text = dir
        sigTv.setTextColor(if (dir == "Buy") 0xFF2FD377.toInt() else 0xFFFF5A4D.toInt())
        showStep()
        say("Signal. $dir. ${label(tf)}. Stake " + String.format("%.2f", stake()) + " dollars.")
        left = tf
        val tick = object : Runnable {
            override fun run() {
                infoTv.text = "Andaza: $dir | ${label(tf)} | ${left}s baaki"
                if (left <= 0) { infoTv.text = "Time poora. Win ya Loss dabao."; say("Time is up. Press win or loss."); return }
                left--; ui.postDelayed(this, 1000)
            }
        }
        ui.post(tick)
    }

    private fun record(win: Boolean) {
        ui.removeCallbacksAndMessages(null)
        if (win) {
            wins++; step++
            if (step >= maxSteps) { step = 0; say("Win. Target reached. Starting again.") } else say("Win. Next step.")
        } else { losses++; step = 0; say("Loss. Back to step one.") }
        sigTv.text = if (win) "Win" else "Loss"
        sigTv.setTextColor(if (win) 0xFF2FD377.toInt() else 0xFFFF5A4D.toInt())
        infoTv.text = "Jeet $wins | Haar $losses"
        showStep()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        try { wm.removeView(bubble); wm.removeView(panel) } catch (e: Exception) {}
        vd?.release(); reader?.close(); projection?.stop(); bgThread?.quit()
        tts?.shutdown()
        super.onDestroy()
    }
}
