package dev.trainerlens

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.View
import android.view.WindowManager
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.TextView
import android.widget.FrameLayout
import android.widget.LinearLayout
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
            ivActive() && ::iv.isInitialized -> when {
                iv.settled -> 300L                                                 // nothing is changing
                iv.pageRecentlySeen(SystemClock.elapsedRealtime()) -> 100L          // a Pokémon page is changing / swiping
                else -> 500L                                                       // no Pokémon page on screen: don't burn battery
            }
            ivActive() -> 500L
            ::bag.isInitialized && bag.recentlySeen(SystemClock.elapsedRealtime()) -> 400L // scrolling the Items list: don't miss rows
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
            // Two bitmaps are reused for every frame (one scan runs at a time), instead of allocating ~22 MB per scan.
            val rw = img.width + pad
            val raw = rawBmp?.takeIf { it.width == rw && it.height == img.height }
                ?: Bitmap.createBitmap(rw, img.height, Bitmap.Config.ARGB_8888).also { rawBmp?.recycle(); rawBmp = it }
            raw.copyPixelsFromBuffer(p.buffer)
            bmp = if (pad == 0) raw else {
                val f = frameBmp?.takeIf { it.width == img.width && it.height == img.height }
                    ?: Bitmap.createBitmap(img.width, img.height, Bitmap.Config.ARGB_8888).also { frameBmp?.recycle(); frameBmp = it }
                Canvas(f).drawBitmap(raw, 0f, 0f, copyPaint) // a copy we can paint on, without the padding columns
                f
            }
        } finally { img.close() }
        try { maskOverlay(bmp) } catch (e: Exception) { Diag.line = "mask failed: $e" }
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
                    iv.onItemsScreen = bag.recentlySeen(SystemClock.elapsedRealtime())
                    worker.execute {
                        try { iv.onFrame(tokens, bmp, screenH, SystemClock.elapsedRealtime(), ocrMs) }
                        catch (e: Exception) { /* a bad frame must never stop the scan loop */ }
                        finally { main.post { frameDone(bmp) } }
                    }
                } else frameDone(bmp)
            }
            .addOnFailureListener { frameDone(bmp) }
    }

    private fun frameDone(@Suppress("UNUSED_PARAMETER") bmp: Bitmap) { scheduleNext() } // the bitmaps are reused, not recycled

    private enum class Tab { BAG, IV }
    private var tab: Tab? = null

    private lateinit var lp: WindowManager.LayoutParams
    private lateinit var bubbleView: View
    private lateinit var panelView: View
    private lateinit var contentFrame: FrameLayout
    private lateinit var tabIv: TextView
    private lateinit var tabBag: TextView
    private val bubblePos = IntArray(2)
    private val panelPos = IntArray(2)
    private var panelPlaced = false
    private var panelW = 0
    private var bubbleSize = 0
    private val maskPaint = Paint().apply { color = Color.BLACK }
    private var small = false
    private lateinit var sizeBtn: TextView
    private val copyPaint = Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC) }
    private var rawBmp: Bitmap? = null
    private var frameBmp: Bitmap? = null

    /**
     * One floating window with two states, both of fixed size: a 🔍 bubble, or a frosted-glass panel with an IV / Bag
     * switch. The bubble and the panel each remember their own position, so opening and closing never moves things.
     * Drag the bubble, or the top bar of the panel, to move it.
     */
    private fun setupMenu() {
        val svc = this
        val m = resources.displayMetrics
        fun dp(v: Int) = Ui.dp(svc, v)
        panelW = OverlayLayout.panelWidthPx(m.widthPixels, m.density)
        bubbleSize = dp(52)
        bubblePos[0] = m.widthPixels - bubbleSize - dp(8); bubblePos[1] = dp(160)
        // the panel opens below the CP line and above the HP / type lines, where it hides nothing the IV checker reads
        small = runCatching { getSharedPreferences("lens", MODE_PRIVATE).getBoolean("small", false) }.getOrDefault(false)
        val r0 = OverlayLayout.panelRect(m.widthPixels, m.heightPixels, m.density,
            if (small) WindowSize.SMALL else WindowSize.LARGE)
        panelPos[0] = r0.left; panelPos[1] = r0.top; panelPlaced = true

        lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = bubblePos[0]; y = bubblePos[1] }

        val bubble = TextView(this).apply {
            text = "🔍"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); gravity = Gravity.CENTER
            background = Ui.rounded(svc, Ui.GLASS, 26f, Ui.GLASS_STROKE)
        }
        bubbleView = bubble

        // top bar: IV | Bag switch, drag area, minimise, stop
        fun tabLabel(t: String) = Ui.label(svc, 13f, Ui.TEXT2, bold = true, gravity = Gravity.CENTER).apply { text = t; isClickable = true }
        tabIv = tabLabel("IV").apply { setOnClickListener { select(Tab.IV) } }
        tabBag = tabLabel("Bag").apply { setOnClickListener { select(Tab.BAG) } }
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ui.rounded(svc, Ui.CHIP, 11f)
            setPadding(dp(2), dp(2), dp(2), dp(2))
            addView(tabIv, LinearLayout.LayoutParams(dp(46), dp(Metrics.TOP_BAR - 4)))
            addView(tabBag, LinearLayout.LayoutParams(dp(46), dp(Metrics.TOP_BAR - 4)))
        }
        val dragArea = FrameLayout(this).apply {
            addView(View(svc).apply { background = Ui.rounded(svc, Ui.CHIP_ACTIVE, 2f) }, FrameLayout.LayoutParams(dp(28), dp(4), Gravity.CENTER))
        }
        sizeBtn = Ui.button(svc, "", 4) { toggleSize() }
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(seg)
            addView(dragArea, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(sizeBtn, LinearLayout.LayoutParams(dp(34), dp(Metrics.TOP_BAR)).apply { marginEnd = dp(5) })
            addView(Ui.button(svc, "–", 4) { setExpanded(false) }, LinearLayout.LayoutParams(dp(34), dp(Metrics.TOP_BAR)).apply { marginEnd = dp(5) })
            addView(Ui.button(svc, "Stop", 4) { stopSelf() }, LinearLayout.LayoutParams(dp(46), dp(Metrics.TOP_BAR)))
        }

        contentFrame = FrameLayout(this).apply {
            background = Ui.rounded(svc, Ui.WELL, 16f)
            setPadding(dp(Metrics.CONTENT_PAD), dp(Metrics.CONTENT_PAD), dp(Metrics.CONTENT_PAD), dp(Metrics.CONTENT_PAD))
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(svc, Ui.GLASS, 22f, Ui.GLASS_STROKE)
            setPadding(dp(Metrics.WINDOW_PAD), dp(Metrics.WINDOW_PAD), dp(Metrics.WINDOW_PAD), dp(Metrics.WINDOW_PAD))
            addView(topBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(Metrics.TOP_BAR)))
            addView(contentFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(Metrics.CONTENT_H)).apply { topMargin = dp(Metrics.GAP) })
            visibility = View.GONE
        }
        panelView = panel
        applySize()

        val box = FrameLayout(this).apply {
            addView(bubble, FrameLayout.LayoutParams(bubbleSize, bubbleSize))
            addView(panel, FrameLayout.LayoutParams(panelW, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        menuRoot = box
        makeDraggable(bubble, bubblePos) { setExpanded(true) }
        makeDraggable(topBar, panelPos, null)
        select(Tab.IV)
        wm.addView(box, lp)
    }

    private fun select(t: Tab) {
        tab = t
        contentFrame.removeAllViews()
        contentFrame.addView(if (t == Tab.BAG) bag.panel else iv.panel,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        styleTab(tabIv, t == Tab.IV); styleTab(tabBag, t == Tab.BAG)
    }

    private fun styleTab(v: TextView, active: Boolean) {
        v.setTextColor(if (active) Ui.TEXT else Ui.TEXT2)
        v.background = if (active) Ui.rounded(this, Ui.CHIP_ACTIVE, 9f) else null
    }

    private fun setExpanded(open: Boolean) {
        panelOpen = open
        bubbleView.visibility = if (open) View.GONE else View.VISIBLE
        panelView.visibility = if (open) View.VISIBLE else View.GONE
        applySize()
        val pos = if (open) panelPos else bubblePos
        lp.x = pos[0]; lp.y = pos[1]
        menuRoot?.let { b ->
            runCatching { wm.updateViewLayout(b, lp) }
            b.post { place(pos) } // keep it on screen once the new size is known
        }
    }

    /**
     * The panel is drawn at [WindowSize] scale, and the window is given exactly that footprint (not wrap-content), so
     * the touch area, the on-screen clamp and the black-out mask all match what is visible.
     */
    private fun applySize() {
        val s = if (small) WindowSize.SMALL else WindowSize.LARGE
        panelView.pivotX = 0f; panelView.pivotY = 0f; panelView.scaleX = s; panelView.scaleY = s
        sizeBtn.text = if (small) "A+" else "A−"
        if (panelOpen) { lp.width = (panelW * s).toInt(); lp.height = (Ui.dp(this, Metrics.PANEL_H) * s).toInt() }
        else { lp.width = bubbleSize; lp.height = bubbleSize }
    }

    private fun toggleSize() {
        small = !small
        runCatching { getSharedPreferences("lens", MODE_PRIVATE).edit().putBoolean("small", small).apply() }
        applySize()
        menuRoot?.let { b -> runCatching { wm.updateViewLayout(b, lp) }; b.post { place(panelPos) } }
    }

    /** Moves the window to [pos] (clamped to the screen) and remembers the clamped value. */
    private fun place(pos: IntArray) {
        val b = menuRoot ?: return
        val m = resources.displayMetrics
        pos[0] = pos[0].coerceIn(0, maxOf(0, m.widthPixels - b.width))
        pos[1] = pos[1].coerceIn(0, maxOf(0, m.heightPixels - b.height))
        lp.x = pos[0]; lp.y = pos[1]
        runCatching { wm.updateViewLayout(b, lp) }
    }

    private fun makeDraggable(handle: View, pos: IntArray, onTap: (() -> Unit)?) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = pos[0]; startY = pos[1]; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) moved = true
                    if (moved) { pos[0] = startX + dx.toInt(); pos[1] = startY + dy.toInt(); place(pos) }
                    true
                }
                MotionEvent.ACTION_UP -> { if (!moved) onTap?.invoke(); true }
                else -> true
            }
        }
    }

    /**
     * The overlay is part of the screen capture. Paint it black before reading the frame, so its own text ("CP 3259",
     * "Poké Ball −34", the ATK / DEF / HP bars) is never mistaken for the game's.
     */
    private fun maskOverlay(bmp: Bitmap) {
        val b = menuRoot ?: return
        if (b.width == 0 || b.height == 0 || !b.isAttachedToWindow) return
        val loc = IntArray(2)
        b.getLocationOnScreen(loc)
        val win = Box(loc[0], loc[1], loc[0] + b.width, loc[1] + b.height)
        val (rw, rh) = realDisplaySize()
        val r = OverlayMask.map(win, rw, rh, bmp.width, bmp.height, Ui.dp(this, 3))
        Canvas(bmp).drawRect(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat(), maskPaint)
        val m = resources.displayMetrics
        Diag.line = "window=${win.left},${win.top},${win.right},${win.bottom} mask=${r.left},${r.top},${r.right},${r.bottom} " +
            "bitmap=${bmp.width}x${bmp.height} realDisplay=${rw}x$rh metrics=${m.widthPixels}x${m.heightPixels} density=${m.density}"
    }

    /** Full size of the display, including the system bars (the app's display metrics can leave them out). */
    private fun realDisplaySize(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.bounds.let { it.width() to it.height() }
        else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }

    override fun onDestroy() {
        destroyed = true
        main.removeCallbacksAndMessages(null)
        worker.shutdown()
        menuRoot?.let { runCatching { wm.removeView(it) } }
        display?.release()
        rawBmp?.recycle(); frameBmp?.recycle(); rawBmp = null; frameBmp = null
        if (::projection.isInitialized) projection.stop()
        super.onDestroy()
    }
}
