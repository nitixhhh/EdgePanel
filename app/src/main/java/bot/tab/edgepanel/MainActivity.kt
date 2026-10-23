package bot.tab.edgepanel

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    private val d by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * d).toInt()

    private lateinit var overlayBtn: Button
    private lateinit var dndBtn: Button
    private lateinit var notifBtn: Button
    private lateinit var startBtn: Button
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(32))
        }

        root.addView(text("Edge Panel", 30f, Color.WHITE, bold = true))
        root.addView(text(
            "Screen ke side se ek black panel nikalta hai: volume, Silent/Ring aur DND/Work/Personal modes, ek swipe me.",
            15f, 0xFFB8B8BE.toInt()
        ).apply { setPadding(0, dp(6), 0, dp(18)) })

        root.addView(section("1. Permissions do"))
        overlayBtn = button("Display over other apps — Allow karo") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        root.addView(overlayBtn)
        root.addView(hint("Isse panel har app ke upar dikh sakta hai. Zaroori hai."))

        dndBtn = button("Do Not Disturb access — Allow karo") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }
        root.addView(dndBtn)
        root.addView(hint("List me \"Edge Panel\" dhundo aur ON karo. Silent mode, DND aur Work/Personal ke liye chahiye."))

        notifBtn = button("Notification permission do") {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            root.addView(notifBtn)
            root.addView(hint("Android ko background panel ke liye ek chhoti notification chahiye."))
        }

        root.addView(section("2. Panel kis side pe?"))
        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val right = radio("Right edge", 101)
        val left = radio("Left edge", 102)
        group.addView(right); group.addView(left)
        group.check(if (Prefs.sideRight(this)) 101 else 102)
        group.setOnCheckedChangeListener { _, id ->
            Prefs.of(this).edit().putBoolean(Prefs.KEY_SIDE_RIGHT, id == 101).apply()
            if (EdgePanelService.isRunning) EdgePanelService.start(this, reload = true)
        }
        root.addView(group)

        root.addView(text("Handle ki height (upar ↔ neeche)", 14f, 0xFFB8B8BE.toInt()).apply { setPadding(0, dp(12), 0, 0) })
        root.addView(SeekBar(this).apply {
            max = 85
            progress = Prefs.position(this@MainActivity) - 10
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {}
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {
                    Prefs.of(this@MainActivity).edit().putInt(Prefs.KEY_POSITION, (s?.progress ?: 28) + 10).apply()
                    if (EdgePanelService.isRunning) EdgePanelService.start(this@MainActivity, reload = true)
                }
            })
        })

        root.addView(section("3. Start karo"))
        startBtn = button("Start") { toggleService() }.apply {
            background = round(0xFF8E7CFF.toInt())
        }
        root.addView(startBtn)
        statusText = hint("")
        root.addView(statusText)

        root.addView(Switch(this).apply {
            text = "Phone restart ke baad apne aap start ho"
            setTextColor(Color.WHITE)
            textSize = 15f
            isChecked = Prefs.startOnBoot(this@MainActivity)
            setPadding(0, dp(14), 0, dp(4))
            setOnCheckedChangeListener { _: CompoundButton, on: Boolean ->
                Prefs.of(this@MainActivity).edit().putBoolean(Prefs.KEY_BOOT, on).apply()
            }
        })

        root.addView(section("Kaise use kare"))
        root.addView(text(
            "• Edge pe patli si handle hai. Tap karo ya andar ki taraf swipe karo, panel khul jayega.\n" +
            "• Page 1: volume slider ko upar-neeche drag karo (media volume).\n" +
            "• Page 2: Silent / Vibrate / Ring, icon pe tap karo.\n" +
            "• Page 3: 🌙 DND on/off, 👤 WORK, 🪪 PERSONAL.\n" +
            "• Page badalne ke liye panel pe upar/neeche swipe karo, ya neeche ke dots pe tap karo.\n" +
            "• 3 second kuch na karo, ya bahar tap karo, to panel band ho jata hai.\n\n" +
            "WORK = Vibrate + DND (sirf priority) + media volume 30%.\n" +
            "PERSONAL = Ring + DND off + pehle wala volume wapas.\n" +
            "(Ye Android ke asli work profile nahi, sound presets hain.)",
            14f, 0xFFD0D0D6.toInt()
        ).apply { setLineSpacing(dp(3).toFloat(), 1f) })

        setContentView(ScrollView(this).apply {
            addView(root, MATCH_PARENT, WRAP_CONTENT)
            isFillViewport = true
        })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val overlay = Settings.canDrawOverlays(this)
        val dnd = (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).isNotificationPolicyAccessGranted
        val notif = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        mark(overlayBtn, overlay, "Display over other apps ✓ mil gaya", "Display over other apps — Allow karo")
        mark(dndBtn, dnd, "Do Not Disturb access ✓ mil gaya", "Do Not Disturb access — Allow karo")
        mark(notifBtn, notif, "Notifications ✓", "Notification permission do")
        val running = EdgePanelService.isRunning
        startBtn.text = if (running) "Stop panel" else "Start panel"
        startBtn.background = round(if (running) 0xFF3A3A3F.toInt() else 0xFF8E7CFF.toInt())
        statusText.text = when {
            !overlay -> "Pehle \"Display over other apps\" allow karo."
            running -> "Panel chal raha hai. Screen ke ${if (Prefs.sideRight(this)) "right" else "left"} edge pe handle dekho."
            else -> "Panel band hai."
        }
    }

    private fun toggleService() {
        if (EdgePanelService.isRunning) {
            Prefs.of(this).edit().putBoolean(Prefs.KEY_RUNNING, false).apply()
            EdgePanelService.stop(this)
        } else {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                return
            }
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
            EdgePanelService.start(this)
        }
        startBtn.postDelayed({ refresh() }, 300)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    // ---------- tiny UI helpers ----------
    private fun round(color: Int) = GradientDrawable().apply { cornerRadius = 14 * d; setColor(color) }

    private fun mark(b: Button, ok: Boolean, okText: String, todo: String) {
        b.text = if (ok) okText else todo
        b.background = round(if (ok) 0xFF1E3A2A.toInt() else 0xFF26262B.toInt())
        b.setTextColor(if (ok) 0xFF7CF0A6.toInt() else Color.WHITE)
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }

    private fun section(s: String) = text(s, 17f, Color.WHITE, bold = true).apply { setPadding(0, dp(18), 0, dp(8)) }
    private fun hint(s: String) = text(s, 13f, 0xFF8E8E95.toInt()).apply { setPadding(dp(4), dp(4), 0, dp(10)) }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setPadding(dp(16), 0, dp(16), 0)
        setTextColor(Color.WHITE)
        background = round(0xFF26262B.toInt())
        stateListAnimator = null
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(52)).apply { topMargin = dp(4) }
        setOnClickListener { onClick() }
    }

    private fun radio(label: String, id: Int) = RadioButton(this).apply {
        this.id = id; text = label; setTextColor(Color.WHITE); textSize = 15f
        setPadding(dp(4), 0, dp(20), 0)
    }
}
