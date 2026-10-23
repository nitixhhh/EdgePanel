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
import android.widget.FrameLayout
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
    private lateinit var writeBtn: Button
    private lateinit var preview: EdgePanelView
    private val themeCards = ArrayList<Button>()
    private lateinit var accentRow: LinearLayout
    private lateinit var startBtn: Button
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(32))
        }

        root.addView(text("Edge Panel", 30f, Color.WHITE, bold = true))
        root.addView(text("v2.0", 13f, 0xFF8E8E95.toInt()))
        root.addView(text(
            "Screen ke side se ek panel nikalta hai: volume + brightness, Silent/Ring aur DND/Work/Personal modes, ek swipe me.",
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

        writeBtn = button("Modify system settings — Allow karo") {
            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
        }
        root.addView(writeBtn)
        root.addView(hint("Brightness slider ke liye. List me Edge Panel ko \"Allow modifying system settings\" ON karo."))

        notifBtn = button("Notification permission do") {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            root.addView(notifBtn)
            root.addView(hint("Android ko background panel ke liye ek chhoti notification chahiye."))
        }

        root.addView(section("2. Style chuno"))
        val sound = SoundControl(this)
        val bright = BrightnessControl(this)
        preview = EdgePanelView(this, sound, bright, sideRight = true, preview = true)
        val stage = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF3B2A6B.toInt(), 0xFF8A3C6E.toInt(), 0xFFE08A5A.toInt())).apply { cornerRadius = 18 * d }
            clipToOutline = true
            addView(preview, FrameLayout.LayoutParams(MATCH_PARENT, preview.expandedWindowH))
        }
        root.addView(stage, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(hint("Ye asli panel hai, yahin try karo: upar/neeche swipe karo page badalne ke liye."))

        var row: LinearLayout? = null
        Themes.ALL.forEachIndexed { i, t ->
            if (i % 2 == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                root.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            }
            val b = Button(this).apply {
                text = t.name
                isAllCaps = false
                textSize = 15f
                stateListAnimator = null
                setOnClickListener {
                    Prefs.of(this@MainActivity).edit().putString(Prefs.KEY_THEME, t.id).apply()
                    refreshThemeUi()
                }
            }
            themeCards.add(b)
            row?.addView(b, LinearLayout.LayoutParams(0, dp(58), 1f).apply {
                setMargins(if (i % 2 == 0) 0 else dp(5), dp(5), if (i % 2 == 0) dp(5) else 0, dp(5))
            })
        }
        root.addView(text("Accent colour", 14f, 0xFFB8B8BE.toInt()).apply { setPadding(0, dp(10), 0, dp(6)) })
        accentRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(accentRow)

        root.addView(section("3. Panel kis side pe?"))
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

        root.addView(section("4. Start karo"))
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
            "• Edge pe patli si handle hai. Tap karo ya andar ki taraf kheencho, panel ungli ke saath khulta hai.\n" +
            "• Page badalna: panel pe kahin bhi upar/neeche swipe karo. Slider ke upar bhi ek tez flick page badal deta hai; slider ko dheere kheencho to volume/brightness badalti hai. Neeche ke 3 dots pe tap bhi kar sakte ho.\n" +
            "• Page 1: brightness ☀ (left) aur volume 🔊 (right). Track pe tap karo to wahan jump karta hai.\n" +
            "• Page 2: Silent / Vibrate / Ring, icon pe tap karo.\n" +
            "• Page 3: DND on/off, WORK, PERSONAL.\n" +
            "• Panel ko edge ki taraf wapas dhakelo, ya bahar tap karo, ya 3 sec chhod do: band ho jata hai.\n" +
            "• Brightness kheenchne par auto-brightness off ho jati hai.\n\n" +
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
        mark(writeBtn, Settings.System.canWrite(this), "Modify system settings ✓ mil gaya", "Modify system settings — Allow karo")
        preview.refreshState(animate = false)
        refreshThemeUi()
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

    private fun refreshThemeUi() {
        val cur = Prefs.theme(this)
        Themes.ALL.forEachIndexed { i, t ->
            val b = themeCards[i]
            val selected = t.id == cur
            b.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(t.panelTop, t.panelBottom)).apply {
                cornerRadius = 14 * d
                setStroke(dp(if (selected) 3 else 1), if (selected) 0xFF8E7CFF.toInt() else 0x33FFFFFF)
            }
            val dark = Color.red(t.panelBottom) + Color.green(t.panelBottom) + Color.blue(t.panelBottom) < 384
            b.setTextColor(if (dark) Color.WHITE else 0xFF1C1C1E.toInt())
            b.text = if (selected) "✓ " + t.name else t.name
        }
        accentRow.removeAllViews()
        val theme = Themes.byId(cur)
        val curAccent = Prefs.accent(this, theme.id)
        Themes.ACCENT_COLORS.forEachIndexed { i, c ->
            val col = if (c == 0) theme.fillA else c
            val selected = c == curAccent
            val v = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(col)
                    setStroke(dp(if (selected) 3 else 1), if (selected) 0xFF8E7CFF.toInt() else 0x55FFFFFF)
                }
                contentDescription = Themes.ACCENT_NAMES[i]
                setOnClickListener {
                    Prefs.of(this@MainActivity).edit().putInt(Prefs.KEY_ACCENT_PREFIX + theme.id, c).apply()
                    refreshThemeUi()
                }
            }
            accentRow.addView(v, LinearLayout.LayoutParams(dp(34), dp(34)).apply { rightMargin = dp(10) })
        }
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
