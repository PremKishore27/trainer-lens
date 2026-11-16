package dev.trainerlens

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.animation.ValueAnimator
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
import android.view.animation.DecelerateInterpolator
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
            .setSmallIcon(R.drawable.ic_stat).build()
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
            panelOpen && tab == Tab.BAG && !(::bag.isInitialized && bag.recentlySeen(SystemClock.elapsedRealtime())) -> 400L // waiting for the Items screen
            ::bag.isInitialized && bag.recentlySeen(SystemClock.elapsedRealtime()) -> 60L  // scrolling the Items list: as fast as text recognition allows
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
                        catch (e: Exception) { iv.reportError(e) } // a bad frame must never stop the scan loop, but it must not hide either
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
    private lateinit var win: OverlayWindow
    private val bubblePos = IntArray(2)
    private val ivPos = IntArray(2)   // the IV tab needs the band between the CP and HP lines
    private val bagPos = IntArray(2)  // the Bag tab sits low, so the top of the Items screen (bag size) stays readable
    private fun panelPos() = if (tab == Tab.BAG) bagPos else ivPos
    private var scale = OverlayScale.DEFAULT
    private val maskPaint = Paint().apply { color = Color.BLACK }
    private val copyPaint = Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC) }
    private var rawBmp: Bitmap? = null
    private var frameBmp: Bitmap? = null

    /**
     * One floating window with two states: a 🔍 bubble, or a frosted-glass panel with an IV / Bag switch. The bubble and
     * the panel each remember their own position. Drag the bubble, or the panel's top bar, to move it; drag the bottom
     * edge of the panel to resize it. The views live in [OverlayWindow].
     */
    private fun setupMenu() {
        val m = resources.displayMetrics
        val panelW = OverlayLayout.panelWidthPx(m.widthPixels, m.density)
        val bubbleSize = Ui.dp(this, 52)
        bubblePos[0] = m.widthPixels - bubbleSize - Ui.dp(this, 8); bubblePos[1] = Ui.dp(this, 160)
        win = OverlayWindow(this, panelW, bubbleSize, { select(Tab.IV) }, { select(Tab.BAG) }, { setExpanded(false) }, { stopSelf() })
        // the panel opens below the CP line and above the HP / type lines, where it hides nothing the IV checker reads
        val r0 = OverlayLayout.panelRect(m.widthPixels, m.heightPixels, m.density)
        ivPos[0] = r0.left; ivPos[1] = r0.top
        bagPos[0] = r0.left
        bagPos[1] = (m.heightPixels - win.panelHeightPx - Ui.dp(this, 110)).coerceAtLeast(r0.top)
        val saved = runCatching { getSharedPreferences("lens", MODE_PRIVATE).getFloat("scale", OverlayScale.DEFAULT) }.getOrDefault(OverlayScale.DEFAULT)
        scale = fitScale(saved)
        ivPos[1] = OverlayBand.snapY(ivPos[1], (win.panelHeightPx * scale).toInt(), realDisplaySize().second)

        lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = bubblePos[0]; y = bubblePos[1] }
        applySize()
        makeDraggable(win.bubble, { bubblePos }) { setExpanded(true) }
        makeDraggable(win.topBar, { panelPos() }, null)
        makeResizable(win.grip)
        select(Tab.IV)
        menuRoot = win.root
        wm.addView(win.root, lp)
    }

    private fun select(t: Tab) {
        tab = t
        win.showTab(if (t == Tab.BAG) bag.panel else iv.panel, t == Tab.BAG)
        if (panelOpen) { // move to this tab's own spot
            scale = fitScale(scale); applySize()
            val pos = panelPos()
            lp.x = pos[0]; lp.y = pos[1]
            menuRoot?.let { b -> runCatching { wm.updateViewLayout(b, lp) }; b.post { place(pos) } }
        }
    }

    private fun setExpanded(open: Boolean) {
        panelOpen = open
        applySize()
        val pos = if (open) panelPos() else bubblePos
        lp.x = pos[0]; lp.y = pos[1]
        menuRoot?.let { b ->
            runCatching { wm.updateViewLayout(b, lp) }
            b.post { place(pos) } // keep it on screen once the new size is known
        }
    }

    /** [s] limited to the allowed range and to the room left on screen below / right of the window's corner. */
    private val realH: Int by lazy { realDisplaySize().second }
    private var settleAnim: ValueAnimator? = null

    private fun fitScale(s: Float): Float {
        val m = resources.displayMetrics
        // on the IV tab the window must also fit the band between the CP and HP lines
        val band = if (tab == Tab.BAG) OverlayScale.MAX else OverlayBand.maxScaleAt(panelPos()[1], win.panelHeightPx, realH)
        return OverlayScale.fit(s, panelPos()[0], panelPos()[1], m.widthPixels, realH, win.panelWidthPx, win.panelHeightPx, band)
    }

    /** The window is given exactly the footprint of what is drawn, so touches, the clamp and the mask match it. */
    private fun applySize() {
        win.setOpen(panelOpen)
        win.applyScale(scale)
        val (w, h) = win.footprint(panelOpen, scale)
        lp.width = w; lp.height = h
    }

    /** Dragging the bottom strip down / up makes the window bigger / smaller (width follows height). */
    private fun makeResizable(grip: View) {
        var downY = 0f; var startScale = 1f; var startH = 1
        grip.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = e.rawY; startScale = scale; startH = lp.height.coerceAtLeast(1); true }
                MotionEvent.ACTION_MOVE -> {
                    val s = fitScale(OverlayScale.fromDrag(startScale, startH, e.rawY - downY))
                    if (s != scale) { scale = s; applySize(); menuRoot?.let { runCatching { wm.updateViewLayout(it, lp) } } }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    runCatching { getSharedPreferences("lens", MODE_PRIVATE).edit().putFloat("scale", scale).apply() }
                    menuRoot?.post { settle(panelPos()) }
                    true
                }
                else -> true
            }
        }
    }

    /** Moves the window to [pos] (clamped to the screen) and remembers the clamped value. */
    private fun place(pos: IntArray, snap: Boolean = true) {
        val b = menuRoot ?: return
        val m = resources.displayMetrics
        pos[0] = pos[0].coerceIn(0, maxOf(0, m.widthPixels - b.width))
        pos[1] = pos[1].coerceIn(0, maxOf(0, realH - b.height))
        // the IV window stays out of the CP / HP lines the checker has to read (see OverlayBand)
        if (snap && pos === ivPos && panelOpen) pos[1] = OverlayBand.snapY(pos[1], b.height, realH)
        lp.x = pos[0]; lp.y = pos[1]
        runCatching { wm.updateViewLayout(b, lp) }
    }

    /**
     * While you drag, the window follows your finger smoothly, wherever it goes. When you let go over the strip it must
     * not cover, it glides to the nearest allowed spot instead of jumping.
     */
    private fun settle(pos: IntArray) {
        val b = menuRoot ?: return
        place(pos, snap = false)
        if (pos !== ivPos || !panelOpen) return
        val target = OverlayBand.snapY(pos[1], b.height, realH)
        if (target == pos[1]) return
        settleAnim?.cancel()
        settleAnim = ValueAnimator.ofInt(pos[1], target).apply {
            duration = 200; interpolator = DecelerateInterpolator()
            addUpdateListener { a -> pos[1] = a.animatedValue as Int; lp.y = pos[1]; runCatching { wm.updateViewLayout(b, lp) } }
            start()
        }
    }

    private fun makeDraggable(handle: View, posOf: () -> IntArray, onTap: (() -> Unit)?) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { settleAnim?.cancel(); val pos = posOf(); downX = e.rawX; downY = e.rawY; startX = pos[0]; startY = pos[1]; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) moved = true
                    if (moved) { val pos = posOf(); pos[0] = startX + dx.toInt(); pos[1] = startY + dy.toInt(); place(pos, snap = false) }
                    true
                }
                MotionEvent.ACTION_UP -> { if (!moved) onTap?.invoke() else settle(posOf()); true }
                MotionEvent.ACTION_CANCEL -> { if (moved) settle(posOf()); true }
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
