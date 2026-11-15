package dev.trainerlens

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Shared screen-reading engine. Each frame is OCR'd once and the tokens are handed to every feature:
 *  1. BagFeature  - item storage advisor (panel behind the bubble)
 *  2. IvFeature   - IV / PvP card shown automatically on Pokémon summary screens
 * Add future features by creating a class with onTokens(...) and calling it from scanOnce().
 */
class CaptureService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var projection: MediaProjection
    private var display: VirtualDisplay? = null
    private lateinit var reader: ImageReader
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val main = Handler(Looper.getMainLooper())
    private var busy = false
    private var screenH = 0

    private lateinit var bag: BagFeature
    private lateinit var iv: IvFeature
    private var menuRoot: View? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        val code = intent!!.getIntExtra("code", 0)
        @Suppress("DEPRECATION") val data = intent.getParcelableExtra<Intent>("data")!!
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(code, data)
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, main)
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        bag = BagFeature(this)
        iv = IvFeature(this, wm)
        SpeciesDb.ensureLoaded(SystemClock.elapsedRealtime())
        setupMenu()
        setupCapture()
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val ch = "capture"
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(ch, "Trainer Lens", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, ch).setContentTitle("Trainer Lens is reading the screen")
            .setSmallIcon(android.R.drawable.ic_menu_view).build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    private fun setupCapture() {
        val m = resources.displayMetrics
        screenH = m.heightPixels
        reader = ImageReader.newInstance(m.widthPixels, m.heightPixels, PixelFormat.RGBA_8888, 2)
        display = projection.createVirtualDisplay(
            "lens", m.widthPixels, m.heightPixels, m.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, main
        )
        main.post(object : Runnable {
            override fun run() { scanOnce(); main.postDelayed(this, 1000) }
        })
    }

    private fun scanOnce() {
        if (busy) return
        val img = reader.acquireLatestImage() ?: return
        val bmp: Bitmap
        try {
            val p = img.planes[0]
            val pad = (p.rowStride - p.pixelStride * img.width) / p.pixelStride
            val full = Bitmap.createBitmap(img.width + pad, img.height, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(p.buffer)
            bmp = Bitmap.createBitmap(full, 0, 0, img.width, img.height)
        } finally { img.close() }
        busy = true
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { text ->
                val tokens = text.textBlocks.flatMap { it.lines }.mapNotNull { l ->
                    l.boundingBox?.let { Token(l.text, it) }
                }
                bag.onTokens(tokens)
                iv.onTokens(tokens, screenH, SystemClock.elapsedRealtime())
            }
            .addOnCompleteListener { busy = false; bmp.recycle() }
    }

    /** Floating bubble; tapping it opens the panel (currently: Bag) with a Stop button. */
    private fun setupMenu() {
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; y = 300 }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24)
            setBackgroundColor(0xEE202020.toInt()); visibility = View.GONE
            addView(bag.panel)
            addView(Button(context).apply { text = "Stop"; setOnClickListener { stopSelf() } })
        }
        val bubble = Button(this).apply {
            text = "🔍"
            setOnClickListener { panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(bubble); addView(panel) }
        wm.addView(box, lp)
        menuRoot = box
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        menuRoot?.let { runCatching { wm.removeView(it) } }
        if (::iv.isInitialized) iv.detach()
        display?.release()
        if (::projection.isInitialized) projection.stop()
        super.onDestroy()
    }
}
