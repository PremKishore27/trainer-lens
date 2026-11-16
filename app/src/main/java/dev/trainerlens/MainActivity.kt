package dev.trainerlens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView

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
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48) }
        col.addView(TextView(this).apply { text = "Trainer Lens"; textSize = 26f })
        col.addView(TextView(this).apply {
            text = "Reads your screen and shows helpful info on top of Pokémon GO (bag advisor, IV checker). It never taps or controls the game."
            setPadding(0, 16, 0, 24)
        })
        status = TextView(this).apply { setPadding(0, 0, 0, 16) }
        col.addView(status)
        col.addView(button("1. Allow notifications") {
            if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        })
        col.addView(button("2. Allow restricted settings (Android 13+)") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })
        col.addView(hint("Opens Trainer Lens's App info page. Tap the ⋮ (three dots, top right) → \"Allow restricted settings\" → confirm. " +
            "Needed because the app wasn't installed from the Play Store; without it step 3 is greyed out. No option in the ⋮ menu? Tap step 3 once first (Android blocks it), then come back here."))
        col.addView(button("3. Allow display over other apps") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        col.addView(button("4. Don't restrict battery (recommended)") {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        })
        col.addView(button("5. ▶ Start session") { start() })
        col.addView(hint("Asks to record the screen – choose \"Entire screen\". Then switch to Pokémon GO and drag the 🔍 bubble anywhere; tap it to open the window with the IV and Bag tabs " +
            "(A− / A+ makes it smaller or larger). The window tells you where to go: for IVs open a Pokémon from your list, for the bag open Items (Poké Ball → Items). " +
            "Items you scroll past are remembered until you tap Reset or Stop."))
        col.addView(TextView(this).apply {
            setPadding(0, 40, 0, 0); textSize = 12f
            text = """Please note
• IV values are estimates calculated from CP, HP and stardust. Without an appraisal you get a range, not an exact value.
• Shadow, purified, mega and other special forms, Pokémon above level 40, and Pokémon with unusual power-up costs can be misjudged.
• PvP rank only compares IV spreads of the same species (level cap 50). It does not say whether the species itself is good.
• Text recognition can misread numbers. English game language only. Bag advice is a suggestion – double-check before discarding.
• Base stats are loaded online from pogoapi.net and kept in memory only; without internet the IV card can't work.
• Screen images are processed on your phone and not stored or uploaded. Google's ML Kit library may send anonymous diagnostics.
• Unofficial app, not affiliated with Niantic, Scopely or The Pokémon Company. It only reads your screen; use at your own risk."""
        })
        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun hint(t: String) = TextView(this).apply { text = t; textSize = 12f; alpha = 0.7f; setPadding(8, 0, 8, 16) }

    private fun button(label: String, action: () -> Unit) = Button(this).apply { text = label; setOnClickListener { action() } }

    private fun refresh() {
        val notif = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val overlay = Settings.canDrawOverlays(this)
        val battery = (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
        fun m(b: Boolean) = if (b) "✅" else "⬜"
        status.text = "${m(notif)} Notifications   ${m(overlay)} Display over apps   ${m(battery)} Battery"
    }

    private fun start() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }
}
