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
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Shared screen-reading engine. Each frame is OCR'd once and the tokens are handed to every feature:
 *  1. BagFeature  - item storage advisor ("Bag" tab; reads all the time so the session remembers items)
 *  2. IvFeature   - IV / PvP card ("IV" tab; only computed while that tab is open)
 * Add future features by creating a class with onTokens(...) and calling it from scanOnce().
 */
class CaptureService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var projection: MediaProjection
    private var display: VirtualDisplay? = null
    private lateinit var reader: ImageReader
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var destroyed = false
    private var panelOpen = false
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
        iv = IvFeature(this, worker)
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
        main.post(scanTask)
    }

    /** Next scan: fast while the IV tab is open and a Pokémon is changing, slower when nothing moves (saves battery). */
    private fun scheduleNext() {
        if (destroyed) return
        val delay = when {
            ivActive() -> if (::iv.isInitialized && iv.settled) 300L else 100L
            else -> 1000L
        }
        main.postDelayed(scanTask, delay)
    }

    private fun ivActive() = panelOpen && tab == Tab.IV

    private val scanTask = Runnable { scanOnce() }

    private fun scanOnce() {
        val img = try { reader.acquireLatestImage() } catch (e: Exception) { null }
        if (img == null) { scheduleNext(); return }
        val bmp: Bitmap
        try {
            val p = img.planes[0]
            val pad = (p.rowStride - p.pixelStride * img.width) / p.pixelStride
            val full = Bitmap.createBitmap(img.width + pad, img.height, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(p.buffer)
            bmp = if (pad == 0) full else Bitmap.createBitmap(full, 0, 0, img.width, img.height).also { full.recycle() }
        } finally { img.close() }
        val t0 = SystemClock.elapsedRealtime()
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { text ->
                val ocrMs = SystemClock.elapsedRealtime() - t0
                val tokens = text.textBlocks.flatMap { it.lines }.mapNotNull { l ->
                    l.boundingBox?.let { Token(l.text, Box.of(it)) }
                }
                bag.onTokens(tokens, screenH) // always runs, so the session remembers items even with the panel closed
                if (ivActive()) {
                    // heavy IV work off the main thread; the bitmap is released when it's done
                    worker.execute {
                        try { iv.onFrame(tokens, bmp, screenH, SystemClock.elapsedRealtime(), ocrMs) }
                        catch (e: Exception) { /* a bad frame must never stop the scan loop */ }
                        finally { main.post { frameDone(bmp) } }
                    }
                } else frameDone(bmp)
            }
            .addOnFailureListener { frameDone(bmp) }
    }

    private fun frameDone(bmp: Bitmap) { bmp.recycle(); scheduleNext() }

    private enum class Tab { BAG, IV }
    private var tab: Tab? = null

    /**
     * One floating window: a draggable 🔍 bubble plus a panel with two tabs (🎒 Bag, 📊 IV).
     * Drag the bubble anywhere; tap it to open or close the panel. A feature's info shows only when its tab is open.
     */
    private fun setupMenu() {
        val m = resources.displayMetrics
        val dp = m.density
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = m.widthPixels - (72 * dp).toInt(); y = (160 * dp).toInt() }

        val content = FrameLayout(this)
        val scroll = MaxHeightScrollView(this, (m.heightPixels * 0.55).toInt()).apply { addView(content) }
        lateinit var bagBtn: Button
        lateinit var ivBtn: Button
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 16)
            setBackgroundColor(0xEE202020.toInt()); visibility = View.GONE
            minimumWidth = (260 * dp).toInt()
        }
        fun select(t: Tab) {
            tab = t
            content.removeAllViews()
            content.addView(if (t == Tab.BAG) bag.panel else iv.panel)
            bagBtn.alpha = if (t == Tab.BAG) 1f else 0.5f
            ivBtn.alpha = if (t == Tab.IV) 1f else 0.5f
        }
        bagBtn = Button(this).apply { text = "🎒 Bag"; setOnClickListener { select(Tab.BAG) } }
        ivBtn = Button(this).apply { text = "📊 IV"; setOnClickListener { select(Tab.IV) } }
        val stopBtn = Button(this).apply { text = "■ Stop"; setOnClickListener { stopSelf() } }
        panel.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(bagBtn); addView(ivBtn); addView(stopBtn)
        })
        panel.addView(scroll)

        val bubble = Button(this).apply { text = "🔍" }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(bubble); addView(panel) }

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragging = false
        bubble.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; dragging = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        lp.x = (startX + dx).toInt().coerceIn(0, (m.widthPixels - box.width).coerceAtLeast(0))
                        lp.y = (startY + dy).toInt().coerceIn(0, (m.heightPixels - bubble.height).coerceAtLeast(0))
                        wm.updateViewLayout(box, lp)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        val open = panel.visibility != View.VISIBLE
                        panel.visibility = if (open) View.VISIBLE else View.GONE
                        panelOpen = open
                        if (open && tab == null) select(Tab.BAG)
                        // keep the opened panel on screen
                        box.post {
                            lp.x = lp.x.coerceIn(0, (m.widthPixels - box.width).coerceAtLeast(0))
                            lp.y = lp.y.coerceIn(0, (m.heightPixels - box.height).coerceAtLeast(0))
                            runCatching { wm.updateViewLayout(box, lp) }
                        }
                    }
                    true
                }
                else -> false
            }
        }
        wm.addView(box, lp)
        menuRoot = box
    }

    /** A ScrollView that never grows taller than [maxH] pixels. */
    private class MaxHeightScrollView(ctx: Context, private val maxH: Int) : ScrollView(ctx) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
        }
    }

    override fun onDestroy() {
        destroyed = true
        main.removeCallbacksAndMessages(null)
        worker.shutdown()
        menuRoot?.let { runCatching { wm.removeView(it) } }
        display?.release()
        if (::projection.isInitialized) projection.stop()
        super.onDestroy()
    }
}
