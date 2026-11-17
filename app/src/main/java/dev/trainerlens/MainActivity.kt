package dev.trainerlens

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.DynamicColors

/** The setup screen: four permissions to allow, then Start. Material 3, light / dark, wallpaper colours on Android 12+. */
class MainActivity : AppCompatActivity() {
    private val badges = ArrayList<TextView>()
    private val buttons = ArrayList<MaterialButton>()
    private val buttonLabels = ArrayList<String>()
    private lateinit var startHint: TextView

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            if (r.resultCode == RESULT_OK && r.data != null) {
                startForegroundService(
                    Intent(this, CaptureService::class.java).putExtra("code", r.resultCode).putExtra("data", r.data)
                )
                finish() // go to Pokémon GO; the overlay stays
            }
        }
    private val notifLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<TextView>(R.id.version).text = "Version ${BuildConfig.VERSION_NAME} · free and open source"
        findViewById<TextView>(R.id.howto).text = HOWTO
        findViewById<TextView>(R.id.note).text = NOTE
        startHint = findViewById(R.id.startHint)

        val actions = listOf<() -> Unit>(
            { if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
            { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) },
            { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) },
            { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) },
        )
        val badgeIds = listOf(R.id.badge1, R.id.badge2, R.id.badge3, R.id.badge4)
        val stepIds = listOf(R.id.step1, R.id.step2, R.id.step3, R.id.step4)
        for (i in 0 until 4) {
            badges += findViewById<TextView>(badgeIds[i])
            val b = findViewById<MaterialButton>(stepIds[i])
            buttonLabels += b.text.toString()
            b.setOnClickListener { actions[i]() }
            buttons += b
        }
        findViewById<MaterialButton>(R.id.start).setOnClickListener { start() }
        findViewById<MaterialButton>(R.id.linkGithub).setOnClickListener { open("https://github.com/PremKishore27/trainer-lens") }
        findViewById<MaterialButton>(R.id.linkIssue).setOnClickListener { open("https://github.com/PremKishore27/trainer-lens/issues/new/choose") }
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun open(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: ActivityNotFoundException) { }
    }

    /** Steps 1, 2 and 4 can be checked. Step 3 (restricted settings) can't be detected; it is done once step 2 is. */
    private fun refresh() {
        val notif = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val overlay = Settings.canDrawOverlays(this)
        val battery = (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
        val done = listOf(notif, overlay, overlay, battery)
        for (i in 0 until 4) {
            badges[i].text = if (done[i]) "✓" else "${i + 1}"
            buttons[i].text = if (done[i]) (if (i == 2) "Not needed" else "Done") else buttonLabels[i]
            buttons[i].isEnabled = !done[i]
        }
        startHint.text = if (overlay) "Android asks to record the screen: choose \"Entire screen\"."
        else "First allow step 2 (display over other apps), then tap Start."
    }

    private fun start() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private companion object {
        const val HOWTO = "Open Pokémon GO and drag the 🔍 bubble anywhere. Tap it to open the window.\n\n" +
            "• IV tab: open a Pokémon from your Pokémon list. For exact IVs tap ☰ → Appraise.\n" +
            "• Bag tab: tap the Poké Ball → Items and scroll slowly.\n" +
            "• Drag the top bar to move the window, and drag its bottom edge to resize it.\n" +
            "• The window tells you where to go if you're on the wrong screen."
        const val NOTE = "• IV values are estimates from CP, HP and stardust; appraise for exact IVs.\n" +
            "• Mega, buddy-boosted and some special Pokémon can be misjudged. English game language only.\n" +
            "• Text recognition can misread numbers. Bag advice is a suggestion – double-check before discarding.\n" +
            "• Pokémon stats are loaded online from pogoapi.net; without internet the IV card can't work.\n" +
            "• Screen images are processed on your phone and not stored or uploaded. Google's ML Kit library may send anonymous diagnostics.\n" +
            "• Unofficial app, not affiliated with Niantic, Scopely or The Pokémon Company. It only reads your screen; use at your own risk."
    }
}
