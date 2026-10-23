package bot.tab.edgepanel

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What the panel asks of the windows it lives in (the service owns them).
 *
 * v3 uses TWO fixed-size overlay windows and never resizes or moves either one:
 *  - a small "handle" window (always there, touchable) that draws the closed handle and catches the drag,
 *  - the big panel window, whose root view is made VISIBLE while the panel is open and INVISIBLE when closed
 *    (an invisible window takes no touches, so nothing behind it is blocked).
 * v2 resized one window with WindowManager.updateViewLayout at the start of the open and the end of the close;
 * the new window frame and the new buffer land on different frames, which is the one-frame "jump" on close.
 */
interface PanelHost {
    fun showPanelWindow()
    fun hidePanelWindow()
    fun setHandleShown(shown: Boolean)
    /** Runs an intent-like action after the panel has closed (settings pages, apps). */
    fun afterClose(delayMs: Long, action: () -> Unit) { android.os.Handler(Looper.getMainLooper()).postDelayed(action, delayMs) }
}

/**
 * Edge Panel v3: the gooey blob, six kinds of page (sliders, ringer, modes, quick toggles, music + battery,
 * apps), liquid-glass sliders, animated icons, page dots and the floating label, all in one view.
 *
 * Every motion is a spring (AndroidX SpringAnimation) tuned with iOS-style parameters
 * (response, dampingFraction -> stiffness = (2*pi/response)^2, dampingRatio = dampingFraction), and every
 * gesture hands its release velocity to the spring, so motion is interruptible and continuous.
 */
@SuppressLint("ViewConstructor")
class EdgePanelView(
    context: Context,
    private val sound: SoundControl,
    private val bright: BrightnessControl,
    val sideRight: Boolean,
    /** Inside the setup screen: always open, no windows, no auto-close. */
    private val preview: Boolean = false,
) : View(context) {

    // ---------- sizes (dp) ----------
    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d
    private fun evenPx(v: Float) = (v.roundToInt() + 1) / 2 * 2

    private val handleDepth = dp(5f)
    private val handleHalf = dp(34f)
    private val handleS = dp(12f)
    private val panelS = dp(44f)
    /** Per page type: SLIDERS, RINGER, MODES, QUICK, MEDIA, APPS. */
    private val typeDepth = floatArrayOf(dp(94f), dp(66f), dp(66f), dp(66f), dp(70f), dp(66f))
    private val typeHalf = floatArrayOf(dp(104f), dp(92f), dp(92f), dp(118f), dp(118f), dp(118f))
    private val pageSpacing = dp(130f)
    private val labelArea = dp(236f)
    private val touchSlop = dp(8f)
    private val openSlop = dp(2f)

    // liquid-glass sliders
    private val trackW = dp(6f)
    private val thumbW = dp(26f)
    private val thumbH = dp(38f)
    private val colGap = dp(40f)       // centre-to-centre of the two slider columns
    private val trackUp = dp(76f)      // track top above pillCy
    private val trackDown = dp(48f)    // track bottom below pillCy
    private val thumbInset = dp(12f)   // thumb centre stays this far inside the track ends
    private val travel get() = trackUp + trackDown - 2 * thumbInset

    /** The handle window (touch strip): wide enough to grab, visually still a thin line. */
    val handleWindowW = evenPx(dp(30f))
    val handleWindowH = evenPx(2 * (handleHalf + handleS) + dp(28f))
    val expandedWindowW = evenPx(typeDepth.max() + dp(16f) + labelArea)
    val expandedWindowH = evenPx(2 * (typeHalf.max() + panelS) + dp(72f))
    /** Handle-window pixel (0,0) in panel-window coordinates; both windows share one centre line and one edge. */
    val handleOffsetX get() = if (sideRight) (expandedWindowW - handleWindowW).toFloat() else 0f
    val handleOffsetY get() = ((expandedWindowH - handleWindowH) / 2).toFloat()

    var host: PanelHost? = null

    // ---------- the system bits ----------
    val feedback = Feedback(context)
    private val torch = TorchControl(context)
    private val media = MediaControl(context)
    val battery = BatteryInfo(context)
    private val conn = Connectivity(context)
    private val appSlots = AppSlots(context)
    private var apps: List<AppSlots.Slot> = emptyList()

    // ---------- springs ----------
    private inner class Spring(response: Float, damping: Float, minChange: Float, initial: Float = 0f) {
        private val holder = FloatValueHolder(initial)
        val anim: SpringAnimation = SpringAnimation(holder).apply {
            spring = SpringForce().setStiffness(stiff(response)).setDampingRatio(damping)
            setMinimumVisibleChange(minChange)
            addUpdateListener { _, v, _ -> value = v; invalidate() }
        }
        var value = initial
            private set
        var target = initial
            private set
        val running get() = anim.isRunning

        fun retune(response: Float, damping: Float) { anim.spring.setStiffness(stiff(response)).setDampingRatio(damping) }

        /** Set the value directly (finger is driving it). Cancels any motion. */
        fun set(v: Float) {
            if (anim.isRunning) anim.cancel()
            value = v; target = v; holder.value = v
        }

        /** Spring to [to]; [velocity] (units/s) is the gesture's hand-off velocity. */
        fun to(to: Float, velocity: Float = Float.NaN) {
            target = to
            if (!anim.isRunning) holder.value = value
            if (!velocity.isNaN()) anim.setStartVelocity(velocity)
            if (!anim.isRunning && abs(value - to) < 0.0005f && (velocity.isNaN() || velocity == 0f)) { set(to); return }
            anim.animateToFinalPosition(to)
        }

        fun stop() { if (anim.isRunning) anim.cancel(); holder.value = value }
    }

    private fun stiff(response: Float): Float { val w = (2.0 * PI / response).toFloat(); return w * w }

    private var pages: IntArray = Prefs.pages(context)
    private val pageCount get() = pages.size

    // open: response 0.42 / damping 0.78 (a soft overshoot); close: critically damped (no bounce, no reversal)
    private val open = Spring(0.42f, 0.78f, 0.001f, 0f)
    private val page = Spring(0.38f, 0.86f, 0.001f, Prefs.of(context).getInt(Prefs.KEY_PAGE, 0).coerceIn(0, pageCount - 1).toFloat())
    private val vol = Spring(0.30f, 0.92f, 0.001f)
    private val bri = Spring(0.30f, 0.92f, 0.001f)
    private val grabVol = Spring(0.25f, 0.62f, 0.002f)
    private val grabBri = Spring(0.25f, 0.62f, 0.002f)
    /** Overscroll past the track ends (px, rubber-banded). */
    private val overVol = Spring(0.30f, 0.70f, 0.3f)
    private val overBri = Spring(0.30f, 0.70f, 0.3f)
    /** Liquid squish from drag velocity, -1..1; jiggles back on release. */
    private val squishVol = Spring(0.20f, 0.50f, 0.002f)
    private val squishBri = Spring(0.20f, 0.50f, 0.002f)
    private val labelA = Spring(0.28f, 1.0f, 0.002f)
    private val labelY = Spring(0.22f, 0.85f, 0.3f)
    /** Selection state per slot: ringer 0..2, modes 3..5, quick 6..9, media 10..13, apps 14..17. */
    private val sel = Array(18) { Spring(0.32f, 0.58f, 0.002f) }
    /** Press-down scale per slot (iOS-style: shrinks under the finger, springs back). */
    private val press = Array(18) { Spring(0.22f, 0.62f, 0.002f) }
    private val playS = Spring(0.30f, 0.80f, 0.002f)
    private val battFill = Spring(0.55f, 0.85f, 0.001f)

    private var expanded = false
    private var closing = false
    private var handleShown = true

    // ---------- label ----------
    private var labelText = ""
    private var labelIcon = -1
    private var labelColor = Color.WHITE
    private val percent = Array(101) { "$it%" }

    private val main = Handler(Looper.getMainLooper())
    private val idleCollapse = Runnable { if (g == G.NONE) collapse() }
    private val labelHide = Runnable { labelA.to(0f) }
    private var afterHidden: (() -> Unit)? = null
    private val hidePanel = Runnable {
        if (!expanded && !preview) {
            host?.hidePanelWindow()
            afterHidden?.let { a -> afterHidden = null; main.postDelayed(a, 160) }   // a frame or more after it's gone
        }
    }
    private val hideHandle = Runnable { pendingHideHandle = false; if (expanded) { handleShown = false; host?.setHandleShown(false) } }

    // ---------- icon animations (time-driven, no allocation) ----------
    private val animStart = LongArray(IconRole.COUNT) { -1L }
    private val animVariant = IntArray(IconRole.COUNT)
    private var debugClock = -1L
    private fun now() = if (debugClock >= 0) debugClock else SystemClock.uptimeMillis()
    private var animating = false
    private fun kick(role: Int, variant: Int = 0) { animStart[role] = now(); animVariant[role] = variant; invalidate() }

    // ---------- theme ----------
    private var theme: PanelTheme = Themes.byId(Prefs.theme(context))
    private var fillA = 0
    private var fillB = 0
    private var glow = 0
    private var dotOn = 0
    private var glassDot = 0
    private var knockColor = Color.BLACK
    private var torchCol = 0
    private var linkCol = 0
    private var iconPaths: Array<Array<Path>> = emptyArray()
    private var iconKinds: Array<IntArray> = emptyArray()
    /** Per role, per layer: 1 = a speaker wave, 2 = the sun's rays, 3 = torch beams, 0 = body. */
    private var iconTag: Array<IntArray> = emptyArray()
    private val gradShaders = arrayOfNulls<Shader>(IconRole.COUNT)

    // ---------- paints (allocated once; onDraw allocates nothing) ----------
    private val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val lensBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val edgeGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val handleLinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val capsulePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glassBody = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glassRim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glassSpec = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glassCaustic = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val glassShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glassDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val burstPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val battPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val smallText = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.BOLD); textAlign = Paint.Align.CENTER
    }
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val iconFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val iconStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val iconGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        letterSpacing = 0.06f
    }

    private val blob = Path()
    private val edge = Path()
    private val pillPath = Path()
    private val thumbPath = Path()
    private val fillPath = Path()
    private val boltPath = Path()
    private val tmpRect = RectF()
    private val tmpRect2 = RectF()
    private val sheenMatrix = Matrix()
    private var fillShader: Shader? = null
    private val battText = Array(101) { "$it%" }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when {
            key == Prefs.KEY_THEME || (key != null && key.startsWith(Prefs.KEY_ACCENT_PREFIX)) -> applyTheme()
            key == Prefs.KEY_SOUND || key == Prefs.KEY_HAPTIC -> feedback.reloadPrefs()
            key == Prefs.KEY_PAGE_ORDER || key == Prefs.KEY_PAGES_OFF -> reloadPages()
            key == Prefs.KEY_APP1 || key == Prefs.KEY_APP2 -> loadApps()
        }
    }

    private val brightObserver = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) { if (g != G.SLIDER || sliderWhich != BRI) refreshState() }
    }

    private companion object {
        const val IDLE_MS = 3500L
        /** On a slider, the first 100 ms decide: a fast start is a page swipe, anything else drives the slider. */
        const val PROMOTE_MS = 100L
        const val FLICK_DP = 22f
        const val BRI = 0
        const val VOL = 1
        // sel / press slot indices
        const val S_RINGER = 0
        const val S_MODES = 3
        const val S_QUICK = 6
        const val S_MEDIA = 10
        const val S_APPS = 14
        const val BLUE = 0xFF0A84FF.toInt()
    }

    // ---------- theme ----------
    fun applyTheme() {
        theme = Themes.byId(Prefs.theme(context))
        val accent = Prefs.accent(context, theme.id)
        if (accent != 0) {
            fillA = accent
            fillB = blend(accent, Color.WHITE, 0.28f)
            glow = (accent and 0x00FFFFFF) or (0x99 shl 24)
            dotOn = accent
            glassDot = accent
        } else {
            fillA = theme.fillA; fillB = theme.fillB; glow = theme.glow; dotOn = theme.dotOn
            glassDot = if (theme.id == "neon") 0xFFFF2BD6.toInt() else BLUE
        }
        knockColor = theme.panelBottom
        torchCol = if (theme.id == "light") 0xFFE6A000.toInt() else 0xFFFFD60A.toInt()
        linkCol = if (theme.id == "neon") 0xFF00E5FF.toInt() else BLUE
        val extra = when (theme.id) { "classic" -> ExtraIcons.fill; "light" -> ExtraIcons.light; else -> ExtraIcons.line }
        val all = Array(IconRole.COUNT) { r -> if (r < IconRole.THEMED) theme.icons[r] else extra[r - IconRole.THEMED] }
        iconPaths = Array(IconRole.COUNT) { r -> Array(all[r].size) { i -> all[r][i].toPath() } }
        iconKinds = Array(IconRole.COUNT) { r -> IntArray(all[r].size) { i -> all[r][i].kind } }
        iconTag = Array(IconRole.COUNT) { r ->
            IntArray(all[r].size) { i ->
                val c = all[r][i].cmds
                val x0 = if (c.size >= 3) c[1] else 0f
                val y0 = if (c.size >= 3) c[2] else 0f
                val k = all[r][i].kind
                when {
                    r == IconRole.VOL && x0 >= 13f && (k == IconKind.STROKE) -> 1
                    r == IconRole.SUN && y0 < 5.5f && k == IconKind.STROKE -> 2
                    r == IconRole.TORCH_ON && y0 < 3f -> 3
                    else -> 0
                }
            }
        }
        for (r in 0 until IconRole.COUNT) {
            val c = roleColor(r)
            val other = if (c == theme.fillA) theme.fillB else theme.fillA
            gradShaders[r] = if (theme.gradientIcons) LinearGradient(2f, 2f, 22f, 22f, c, other, Shader.TileMode.CLAMP) else null
        }
        fillShader = null
        trackPaint.color = theme.track
        if (Build.VERSION.SDK_INT >= 28) {
            if (Color.alpha(glow) > 0) fillPaint.setShadowLayer(dp(7f), 0f, 0f, glow) else fillPaint.clearShadowLayer()
            if (Color.alpha(theme.shadow) > 0) blobPaint.setShadowLayer(dp(14f), 0f, dp(3f), theme.shadow) else blobPaint.clearShadowLayer()
        }
        edgePaint.strokeWidth = dp(max(theme.strokeW, 0.01f))
        edgeGlowPaint.strokeWidth = dp(6f)
        labelPaint.textSize = if (theme.labelCapsule) dp(14f) else dp(18f)
        if (theme.labelCapsule) labelPaint.clearShadowLayer() else labelPaint.setShadowLayer(dp(6f), 0f, dp(1.5f), 0xAA000000.toInt())
        smallText.textSize = dp(10.5f)
        buildGlass()
        buildShaders()
        invalidate()
    }

    private val isLight get() = theme.id == "light"

    /** The liquid-glass thumb's paints. Its shaders live in a unit box and are mapped per frame (no allocation). */
    private fun buildGlass() {
        val light = isLight
        glassBody.shader = LinearGradient(0f, 0f, 0f, 1f,
            if (light) 0xC8FFFFFF.toInt() else 0x4DFFFFFF, if (light) 0x8CFFFFFF.toInt() else 0x14FFFFFF, Shader.TileMode.CLAMP)
        val rimTop = if (light) 0xFFFFFFFF.toInt() else 0xE6FFFFFF.toInt()
        val rimMid = if (light) 0x1A000000 else 0x26FFFFFF
        val rimBot = if (light) 0x40000000 else if (theme.id == "neon") 0xCCFF2BD6.toInt() else 0x8CFFFFFF.toInt()
        glassRim.shader = LinearGradient(0f, 0f, 0f, 1f, intArrayOf(rimTop, rimMid, rimBot), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        glassRim.strokeWidth = dp(1.1f)
        glassSpec.shader = LinearGradient(0f, 0f, 0f, 1f, if (light) 0xFFFFFFFF.toInt() else 0xB3FFFFFF.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP)
        glassCaustic.color = if (light) 0x99FFFFFF.toInt() else 0x66FFFFFF
        glassCaustic.strokeWidth = dp(1.6f)
        glassShadow.color = if (light) 0x30000000 else 0x80000000.toInt()
        if (Build.VERSION.SDK_INT >= 28) glassShadow.setShadowLayer(dp(7f), 0f, dp(2.5f), if (light) 0x40000000 else 0x99000000.toInt())
        glassDotPaint.color = glassDot
        if (Build.VERSION.SDK_INT >= 28) glassDotPaint.setShadowLayer(dp(3f), 0f, 0f, (glassDot and 0x00FFFFFF) or (0x99 shl 24))
    }

    private fun roleColor(r: Int): Int = when (r) {
        IconRole.SILENT -> theme.silent
        IconRole.VIBRATE -> theme.vibrate
        IconRole.RING -> theme.ring
        IconRole.DND -> theme.dnd
        IconRole.WORK -> theme.work
        IconRole.PERSONAL -> theme.personal
        IconRole.TORCH, IconRole.TORCH_ON -> torchCol
        IconRole.WIFI, IconRole.BT -> linkCol
        IconRole.SHOT, IconRole.PLAY, IconRole.PAUSE, IconRole.NEXT, IconRole.PREV, IconRole.CAMERA, IconRole.CALC -> theme.ring
        else -> theme.fillA
    }

    private var shadersBuilt = false
    private fun buildShaders() {
        shadersBuilt = true
        val c = cy
        val ext = typeHalf.max() + panelS
        blobPaint.shader = if (theme.panelTop == theme.panelBottom) null
        else LinearGradient(0f, c - ext, 0f, c + ext, theme.panelTop, theme.panelBottom, Shader.TileMode.CLAMP)
        blobPaint.color = theme.panelBottom
        lensBgPaint.shader = blobPaint.shader
        lensBgPaint.color = theme.panelBottom
        edgePaint.shader = if (theme.stroke2 != theme.stroke && Color.alpha(theme.stroke2) > 0)
            LinearGradient(0f, c - ext, 0f, c + ext, theme.stroke, theme.stroke2, Shader.TileMode.CLAMP) else null
        edgePaint.color = theme.stroke
        edgeGlowPaint.shader = edgePaint.shader
        edgeGlowPaint.color = theme.stroke
        sheenPaint.shader = RadialGradient(0f, 0f, 1f, 0x2EFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        val pc = pillCy()
        fillShader = LinearGradient(0f, pc - trackUp, 0f, pc + trackDown, fillB, fillA, Shader.TileMode.CLAMP)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        buildShaders()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Prefs.of(context).registerOnSharedPreferenceChangeListener(prefListener)
        try {
            context.contentResolver.registerContentObserver(Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS), false, brightObserver)
        } catch (_: Exception) { }
    }

    private fun reloadPages() {
        val cur = pages.getOrNull(currentPage())
        pages = Prefs.pages(context)
        val idx = pages.indexOf(cur ?: -1).let { if (it < 0) 0 else it }
        page.set(idx.toFloat())
        invalidate()
    }

    private fun loadApps() { apps = appSlots.slots(dp(30f).toInt()); invalidate() }

    // ---------- geometry ----------
    private val vw get() = if (width > 0) width.toFloat() else expandedWindowW.toFloat()
    private val vh get() = if (height > 0) height.toFloat() else expandedWindowH.toFloat()
    private val edgeX get() = if (sideRight) vw else 0f
    private fun inX(dist: Float) = if (sideRight) vw - dist else dist
    private val cy get() = vh / 2f
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun clamp01(v: Float) = v.coerceIn(0f, 1f)
    private val progress get() = open.value

    private fun typeHalfOf(t: Int): Float = if (t == PageType.APPS && apps.size <= 3) dp(92f) else typeHalf[t]

    private fun pageInterpDepth(): Float {
        val p = page.value.coerceIn(0f, (pageCount - 1).toFloat())
        val i = min(p.toInt(), max(0, pageCount - 2))
        if (pageCount == 1) return typeDepth[pages[0]]
        return lerp(typeDepth[pages[i]], typeDepth[pages[i + 1]], p - i)
    }

    private fun pageInterpHalf(): Float {
        val p = page.value.coerceIn(0f, (pageCount - 1).toFloat())
        val i = min(p.toInt(), max(0, pageCount - 2))
        if (pageCount == 1) return typeHalfOf(pages[0])
        return lerp(typeHalfOf(pages[i]), typeHalfOf(pages[i + 1]), p - i)
    }

    private val depthNow get() = lerp(handleDepth, pageInterpDepth(), progress).coerceAtLeast(handleDepth * 0.6f)
    private val sNow get() = lerp(handleS, panelS, min(progress, 1.1f).coerceAtLeast(0f))
    private val halfNow get() = lerp(handleHalf, pageInterpHalf(), min(progress, 1.06f).coerceAtLeast(0f))
    private val contentAlpha get() = clamp01((progress - 0.62f) / 0.33f)
    private fun contentCy() = cy - dp(8f)
    private fun pillCy() = cy - dp(10f)

    /** iOS rubber band: past the edge the content moves less and less. */
    private fun rubber(over: Float, dim: Float): Float {
        val a = abs(over)
        return sign(over) * (1f - 1f / (a * 0.55f / dim + 1f)) * dim
    }

    /**
     * The blob: two cubic S-curves that leave the screen edge tangentially (the concave "gooey"
     * fillet) and arrive tangentially on the flat inner side (the convex rounded corner).
     */
    private fun buildBlob(depth: Float, half: Float, s: Float) {
        val e = edgeX
        val outside = if (sideRight) e + dp(2f) else e - dp(2f)
        val inner = inX(depth)
        val c = cy
        val top0 = c - half - s
        val top1 = c - half
        val bot1 = c + half
        val bot0 = c + half + s
        val k1 = 0.50f
        val k2 = 0.80f
        blob.rewind()
        blob.moveTo(outside, top0 - dp(1f))
        blob.lineTo(e, top0)
        blob.cubicTo(e, top0 + s * k1, inner, top1 - s * k2, inner, top1)
        blob.lineTo(inner, bot1)
        blob.cubicTo(inner, bot1 + s * k2, e, bot0 - s * k1, e, bot0)
        blob.lineTo(outside, bot0 + dp(1f))
        blob.close()
        edge.rewind()
        edge.moveTo(e, top0)
        edge.cubicTo(e, top0 + s * k1, inner, top1 - s * k2, inner, top1)
        edge.lineTo(inner, bot1)
        edge.cubicTo(inner, bot1 + s * k2, e, bot0 - s * k1, e, bot0)
    }

    /** The closed handle, drawn exactly as the panel draws itself at progress 0 (the handle window uses this). */
    fun drawHandle(canvas: Canvas) {
        if (!shadersBuilt) buildShaders()
        buildBlob(handleDepth, handleHalf, handleS)
        drawBlobAndLine(canvas, 0f, handleDepth)
    }

    private fun drawBlobAndLine(canvas: Canvas, prog: Float, depth: Float) {
        val pa = (theme.panelAlpha * lerp(0.84f, 1f, clamp01(prog * 2f))).toInt()
        blobPaint.alpha = pa
        canvas.drawPath(blob, blobPaint)
        val half = halfNow
        if (theme.id == "glass" || theme.id == "light") {
            val r = dp(130f)
            sheenMatrix.setScale(r, r)
            sheenMatrix.postTranslate(inX(depth * 0.35f), cy - (if (prog <= 0f) handleHalf else half))
            sheenPaint.shader?.setLocalMatrix(sheenMatrix)
            sheenPaint.alpha = (255 * clamp01(prog)).toInt()
            if (sheenPaint.alpha > 0) canvas.drawPath(blob, sheenPaint)
        }
        if (theme.strokeW > 0f && Color.alpha(theme.stroke) > 0) {
            val ea = clamp01(prog * 1.5f)
            if (ea > 0f) {
                if (theme.edgeGlow) { edgeGlowPaint.alpha = (70 * ea).toInt(); canvas.drawPath(edge, edgeGlowPaint) }
                edgePaint.alpha = (Color.alpha(theme.stroke) * ea).toInt()
                canvas.drawPath(edge, edgePaint)
            }
        }
        // The subtle line inside the closed handle.
        val lineA = clamp01(1f - prog * 3f)
        if (lineA > 0f) {
            handleLinePaint.color = if (isLight) 0x661C1C1E else if (theme.id == "neon") theme.fillA else 0x88FFFFFF.toInt()
            handleLinePaint.alpha = (Color.alpha(handleLinePaint.color) * lineA).toInt()
            val x = inX(depth * 0.5f)
            tmpRect.set(x - dp(1f), cy - handleHalf * 0.7f, x + dp(1f), cy + handleHalf * 0.7f)
            canvas.drawRoundRect(tmpRect, dp(1f), dp(1f), handleLinePaint)
        }
    }

    // ---------- drawing ----------
    override fun onDraw(canvas: Canvas) {
        if (width == 0) return
        animating = false
        val prog = progress
        val depth = depthNow
        val half = halfNow
        buildBlob(depth, half, sNow)
        drawBlobAndLine(canvas, prog, depth)

        val a = contentAlpha
        if (a > 0f) {
            val cx = inX(depth / 2f)
            val pf = page.value
            canvas.save()
            canvas.clipPath(blob)
            // Content grows into place with the panel (0.86 -> 1, and overshoots with the spring).
            val grow = lerp(0.86f, 1f, prog.coerceIn(0f, 1.08f))
            canvas.scale(grow, grow, cx, contentCy())
            for (p in 0 until pageCount) {
                val dist = p - pf
                val pa2 = a * clamp01(1f - abs(dist) * 1.6f)
                if (pa2 <= 0.01f) continue
                val sc = 1f - 0.07f * min(1f, abs(dist))
                canvas.save()
                canvas.translate(0f, dist * pageSpacing)
                canvas.scale(sc, sc, cx, contentCy())
                when (pages[p]) {
                    PageType.SLIDERS -> drawSliders(canvas, cx, pa2)
                    PageType.RINGER -> drawRinger(canvas, cx, pa2)
                    PageType.MODES -> drawModes(canvas, cx, pa2)
                    PageType.QUICK -> drawQuick(canvas, cx, pa2)
                    PageType.MEDIA -> drawMedia(canvas, cx, pa2)
                    PageType.APPS -> drawApps(canvas, cx, pa2)
                }
                canvas.restore()
            }
            canvas.restore()
            if (pageCount > 1) drawDots(canvas, cx, cy + half - dp(13f), a)
        }
        drawLabel(canvas, depth)
        if (expanded && handleShown && prog > 0.001f && !pendingHideHandle) {
            // The panel window is on screen now; let the handle window stop drawing a frame or two later
            // (the two overlap for a moment rather than leave a gap).
            pendingHideHandle = true
            main.postDelayed(hideHandle, 34)
        }
        if (animating) postInvalidateOnAnimation()
    }

    private var pendingHideHandle = false

    // ---------- liquid-glass sliders ----------
    private fun colCx(which: Int, cx: Float) = if (which == BRI) cx - colGap / 2f else cx + colGap / 2f
    private fun trackTop() = pillCy() - trackUp
    private fun trackBot() = pillCy() + trackDown
    private fun thumbCyFor(v: Float) = trackBot() - thumbInset - travel * v
    private fun sliderIconCy() = pillCy() + dp(72f)

    /** The thumb's rect (view space, page 0 at rest), with grab growth, velocity squish and overscroll. */
    private fun thumbRect(which: Int, r: RectF) {
        val cx = colCx(which, inX(depthNow / 2f))
        val v = clamp01(if (which == VOL) vol.value else bri.value)
        val grab = if (which == VOL) grabVol.value else grabBri.value
        val over = if (which == VOL) overVol.value else overBri.value
        val sq = (if (which == VOL) squishVol.value else squishBri.value).coerceIn(-1.2f, 1.2f)
        val stretch = 1f + 0.30f * abs(sq) + abs(over) / thumbH * 0.5f
        val scale = 1f + 0.16f * grab
        val h = thumbH * scale * stretch
        val w = thumbW * scale / sqrt(stretch)          // keeps the area: stretches long, squeezes thin
        val c = thumbCyFor(v) - over - sq * dp(3f)      // leans into the motion a touch
        r.set(cx - w / 2f, c - h / 2f, cx + w / 2f, c + h / 2f)
    }

    private fun drawSliders(canvas: Canvas, cx: Float, alpha: Float) {
        drawGlassSlider(canvas, BRI, cx, bri.value, alpha)
        drawGlassSlider(canvas, VOL, cx, vol.value, alpha)
    }

    private fun drawTrack(canvas: Canvas, x: Float, value: Float, alpha: Float) {
        val top = trackTop()
        val bot = trackBot()
        tmpRect2.set(x - trackW / 2f, top, x + trackW / 2f, bot)
        trackPaint.color = theme.track
        trackPaint.alpha = (Color.alpha(theme.track) * alpha).toInt()
        canvas.drawRoundRect(tmpRect2, trackW / 2f, trackW / 2f, trackPaint)
        val fillTop = thumbCyFor(clamp01(value))
        if (fillTop < bot - 0.5f) {
            tmpRect2.set(x - trackW / 2f, fillTop, x + trackW / 2f, bot)
            fillPaint.shader = fillShader
            fillPaint.color = Color.WHITE
            fillPaint.alpha = (255 * alpha).toInt()
            canvas.drawRoundRect(tmpRect2, trackW / 2f, trackW / 2f, fillPaint)
        }
    }

    private fun drawGlassSlider(canvas: Canvas, which: Int, cx: Float, value: Float, alpha: Float) {
        val x = colCx(which, cx)
        drawTrack(canvas, x, value, alpha)

        // Icon under the track: speaker waves grow with volume, the sun's rays grow with brightness.
        val v = clamp01(value)
        val icon = if (which == VOL) (if (v <= 0.004f) IconRole.VOLMUTE else IconRole.VOL) else IconRole.SUN
        drawIcon(canvas, icon, x, sliderIconCy(), dp(22f), iconCol(), alpha, glowOk = false, gradient = false, level = v)

        // The glass thumb.
        thumbRect(which, tmpRect)
        val grab = if (which == VOL) grabVol.value else grabBri.value
        val r = min(tmpRect.width(), tmpRect.height()) / 2f
        thumbPath.rewind()
        thumbPath.addRoundRect(tmpRect, r, r, Path.Direction.CW)
        val a255 = (255 * alpha).toInt()

        // soft shadow, only outside the glass (the glass itself stays clear)
        canvas.save()
        canvas.clipOutPath(thumbPath)
        glassShadow.alpha = (Color.alpha(glassShadow.color) * alpha).toInt()
        canvas.drawPath(thumbPath, glassShadow)
        canvas.restore()
        // refraction: inside the glass the track is seen magnified (a lens), over the panel colour
        canvas.save()
        canvas.clipPath(thumbPath)
        lensBgPaint.alpha = (theme.panelAlpha * alpha).toInt()
        canvas.drawRect(tmpRect, lensBgPaint)
        val mag = 1.28f + 0.12f * grab
        canvas.save()
        canvas.scale(mag, mag, x, tmpRect.centerY())
        drawTrack(canvas, x, value, alpha)
        canvas.restore()
        // glass body
        sheenMatrix.setScale(1f, tmpRect.height()); sheenMatrix.postTranslate(0f, tmpRect.top)
        glassBody.shader?.setLocalMatrix(sheenMatrix)
        glassBody.alpha = (a255 * lerp(1f, 0.75f, grab)).toInt()
        canvas.drawRect(tmpRect, glassBody)
        // specular highlight across the top
        val hw = tmpRect.width() * 0.62f
        val hh = tmpRect.height() * 0.24f
        tmpRect2.set(x - hw / 2f, tmpRect.top + dp(2.2f), x + hw / 2f, tmpRect.top + dp(2.2f) + hh)
        sheenMatrix.setScale(1f, hh); sheenMatrix.postTranslate(0f, tmpRect2.top)
        glassSpec.shader?.setLocalMatrix(sheenMatrix)
        glassSpec.alpha = a255
        canvas.drawRoundRect(tmpRect2, hw / 2f, hh / 2f, glassSpec)
        // caustic: a thin bright arc along the inner bottom edge, where light bends through the glass
        glassCaustic.alpha = (Color.alpha(glassCaustic.color) * alpha).toInt()
        tmpRect2.set(tmpRect.left + dp(3f), tmpRect.bottom - r * 2f + dp(2.5f), tmpRect.right - dp(3f), tmpRect.bottom - dp(2.5f))
        canvas.drawArc(tmpRect2, 35f, 110f, false, glassCaustic)
        canvas.restore()
        // refraction-like rim
        sheenMatrix.setScale(1f, tmpRect.height()); sheenMatrix.postTranslate(0f, tmpRect.top)
        glassRim.shader?.setLocalMatrix(sheenMatrix)
        glassRim.alpha = a255
        tmpRect.inset(glassRim.strokeWidth / 2f, glassRim.strokeWidth / 2f)
        canvas.drawRoundRect(tmpRect, r, r, glassRim)
        // the little dot in the middle
        glassDotPaint.alpha = a255
        canvas.drawCircle(x, tmpRect.centerY(), dp(3.2f) * (1f + 0.15f * grab), glassDotPaint)
    }

    private fun iconCol(): Int = if (isLight) 0xFF3A3A3E.toInt() else if (theme.id == "neon") 0xFFB8F6FF.toInt() else 0xFFE5E5EA.toInt()

    // ---------- slots ----------
    private fun slotSpacing(n: Int) = if (n >= 4) dp(50f) else dp(54f)
    private fun slotY(i: Int, n: Int) = contentCy() + (i - (n - 1) / 2f) * slotSpacing(n)

    /** A toggle slot: grey when off, its own colour when on (v2 behaviour), with press and pop. */
    private fun drawToggle(canvas: Canvas, role: Int, idx: Int, y: Float, cx: Float, alpha: Float, dimRoleColor: Boolean) {
        val s = sel[idx].value
        val size = lerp(dp(26f), dp(33f), s) * (1f - 0.12f * press[idx].value)
        val active = s > 0.5f
        val rc = roleColor(role)
        val col: Int
        val a: Float
        if (dimRoleColor) {           // Classic: Work/Personal keep their colour, dimmed when off (like the reference)
            col = rc; a = alpha * lerp(0.45f, 1f, clamp01(s))
        } else {
            col = blend(theme.inactive, rc, clamp01(s))
            a = alpha * lerp(theme.inactiveAlpha, 1f, clamp01(s))
        }
        drawIcon(canvas, role, cx, y, size, col, a, glowOk = active, gradient = active)
    }

    /** An action slot (music keys, screenshot, apps): always in the theme's foreground colour. */
    private fun drawAction(canvas: Canvas, role: Int, idx: Int, y: Float, cx: Float, alpha: Float, size: Float = dp(28f)) {
        val sz = size * (1f - 0.14f * press[idx].value)
        drawIcon(canvas, role, cx, y, sz, roleColor(role), alpha, glowOk = true, gradient = true)
    }

    private fun drawRinger(canvas: Canvas, cx: Float, alpha: Float) {
        drawToggle(canvas, IconRole.SILENT, S_RINGER, slotY(0, 3), cx, alpha, false)
        drawToggle(canvas, IconRole.VIBRATE, S_RINGER + 1, slotY(1, 3), cx, alpha, false)
        drawToggle(canvas, IconRole.RING, S_RINGER + 2, slotY(2, 3), cx, alpha, false)
    }

    private fun drawModes(canvas: Canvas, cx: Float, alpha: Float) {
        val classic = theme.id == "classic"
        drawToggle(canvas, IconRole.DND, S_MODES, slotY(0, 3), cx, alpha, false)
        drawToggle(canvas, IconRole.WORK, S_MODES + 1, slotY(1, 3), cx, alpha, classic)
        drawToggle(canvas, IconRole.PERSONAL, S_MODES + 2, slotY(2, 3), cx, alpha, classic)
    }

    private fun drawQuick(canvas: Canvas, cx: Float, alpha: Float) {
        drawToggle(canvas, if (sel[S_QUICK].value > 0.5f) IconRole.TORCH_ON else IconRole.TORCH, S_QUICK, slotY(0, 4), cx, alpha, false)
        drawToggle(canvas, IconRole.WIFI, S_QUICK + 1, slotY(1, 4), cx, alpha, false)
        drawToggle(canvas, IconRole.BT, S_QUICK + 2, slotY(2, 4), cx, alpha, false)
        drawAction(canvas, IconRole.SHOT, S_QUICK + 3, slotY(3, 4), cx, alpha, dp(27f))
    }

    private fun drawMedia(canvas: Canvas, cx: Float, alpha: Float) {
        drawBattery(canvas, cx, slotY(0, 4), alpha)
        drawAction(canvas, IconRole.PREV, S_MEDIA + 1, slotY(1, 4), cx, alpha, dp(24f))
        // play <-> pause morph: one shrinks and fades while the other grows in
        val ps = clamp01(playS.value)
        val y = slotY(2, 4)
        val base = dp(31f) * (1f - 0.14f * press[S_MEDIA + 2].value)
        if (ps < 0.98f) drawIcon(canvas, IconRole.PLAY, cx, y, base * lerp(1f, 0.6f, ps), roleColor(IconRole.PLAY), alpha * (1f - ps), true, true)
        if (ps > 0.02f) drawIcon(canvas, IconRole.PAUSE, cx, y, base * lerp(0.6f, 1f, ps), roleColor(IconRole.PAUSE), alpha * ps, true, true)
        drawAction(canvas, IconRole.NEXT, S_MEDIA + 3, slotY(3, 4), cx, alpha, dp(24f))
    }

    private fun drawApps(canvas: Canvas, cx: Float, alpha: Float) {
        val n = apps.size.coerceAtMost(4)
        for (i in 0 until n) {
            val s = apps[i]
            val y = slotY(i, n)
            val idx = S_APPS + i
            when (s.kind) {
                AppSlots.CAMERA -> drawAction(canvas, IconRole.CAMERA, idx, y, cx, alpha)
                AppSlots.CALC -> drawAction(canvas, IconRole.CALC, idx, y, cx, alpha)
                else -> {
                    val b = s.icon
                    val pop = popScale(-1, idx)
                    val sz = dp(30f) * (1f - 0.14f * press[idx].value) * pop
                    if (b != null) {
                        tmpRect.set(cx - sz / 2f, y - sz / 2f, cx + sz / 2f, y + sz / 2f)
                        bmpPaint.alpha = (255 * alpha).toInt()
                        canvas.drawBitmap(b, null, tmpRect, bmpPaint)
                    }
                }
            }
        }
    }

    private val appPopStart = LongArray(4) { -1L }
    private fun popScale(role: Int, idx: Int): Float {
        val st = if (role >= 0) animStart[role] else appPopStart.getOrNull(idx - S_APPS) ?: -1L
        if (st < 0) return 1f
        val t = (now() - st) / 1000f
        if (t > 0.6f) return 1f
        animating = true
        return 1f + 0.22f * exp(-t / 0.11f) * sin(2f * PI.toFloat() * 2.6f * t)
    }

    // ---------- battery ----------
    private fun drawBattery(canvas: Canvas, cx: Float, y: Float, alpha: Float) {
        val w = dp(22f)
        val h = dp(11.5f)
        val by = y - dp(5f)
        val fg = iconCol()
        val lvl = clamp01(battFill.value)
        val col = when {
            battery.charging -> 0xFF30D158.toInt()
            battery.percent <= 20 -> 0xFFFF453A.toInt()
            else -> if (theme.id == "neon") fillA else fg
        }
        // shell
        battPaint.style = Paint.Style.STROKE
        battPaint.strokeWidth = dp(1.3f)
        battPaint.color = fg
        battPaint.alpha = (110 * alpha).toInt()
        tmpRect.set(cx - w / 2f, by - h / 2f, cx + w / 2f, by + h / 2f)
        canvas.drawRoundRect(tmpRect, dp(3.2f), dp(3.2f), battPaint)
        battPaint.style = Paint.Style.FILL
        tmpRect2.set(tmpRect.right + dp(1.2f), by - dp(2.2f), tmpRect.right + dp(2.6f), by + dp(2.2f))
        canvas.drawRoundRect(tmpRect2, dp(1f), dp(1f), battPaint)
        // animated fill
        val inset = dp(2.1f)
        val fw = (w - 2 * inset) * lvl
        if (fw > 0.5f) {
            battPaint.color = col
            battPaint.alpha = (255 * alpha).toInt()
            tmpRect2.set(tmpRect.left + inset, tmpRect.top + inset, tmpRect.left + inset + fw, tmpRect.bottom - inset)
            canvas.drawRoundRect(tmpRect2, dp(1.6f), dp(1.6f), battPaint)
        }
        if (battery.charging) {
            // bolt, breathing gently while charging
            val t = (now() % 1600L) / 1600f
            animating = animating || expanded || preview
            val br = 0.75f + 0.25f * sin(2f * PI.toFloat() * t)
            canvas.save()
            val bs = h * 1.25f / 24f
            canvas.translate(cx - 12f * bs, by - 12f * bs)
            canvas.scale(bs, bs)
            battPaint.color = if (isLight) Color.WHITE else knockColor.let { if (Color.alpha(it) == 0) Color.BLACK else it }
            battPaint.alpha = (255 * alpha).toInt()
            battPaint.style = Paint.Style.STROKE
            battPaint.strokeWidth = 3.2f
            battPaint.strokeJoin = Paint.Join.ROUND
            canvas.drawPath(boltPath, battPaint)
            battPaint.style = Paint.Style.FILL
            battPaint.color = if (isLight) 0xFF1C1C1E.toInt() else Color.WHITE
            battPaint.alpha = (255 * alpha * br).toInt()
            canvas.drawPath(boltPath, battPaint)
            canvas.restore()
        }
        smallText.color = fg
        smallText.alpha = (230 * alpha).toInt()
        canvas.drawText(battText[battery.percent.coerceIn(0, 100)], cx, by + h / 2f + dp(13f), smallText)
    }

    // ---------- icons ----------
    /** Damped spring step response 0 -> 1 (for icon motion that isn't driven by a SpringAnimation). */
    private fun springStep(t: Float, response: Float, damping: Float): Float {
        val w0 = 2f * PI.toFloat() / response
        val wd = w0 * sqrt(1f - damping * damping)
        val e = exp(-damping * w0 * t)
        return 1f - e * (cos(wd * t) + damping * w0 / wd * sin(wd * t))
    }

    private fun drawIcon(canvas: Canvas, role: Int, cx: Float, cy: Float, size: Float, color: Int, alpha: Float,
                         glowOk: Boolean, gradient: Boolean, level: Float = -1f) {
        if (role >= iconPaths.size) return
        val paths = iconPaths[role]
        val kinds = iconKinds[role]
        val tags = iconTag[role]
        var a = (255 * alpha * Color.alpha(color) / 255f).toInt().coerceIn(0, 255)
        if (a == 0) return
        canvas.save()
        canvas.translate(cx - size / 2f, cy - size / 2f)
        val sc = size / 24f
        canvas.scale(sc, sc)

        // ---- per-icon motion (all in 24x24 icon space) ----
        var beams = 1f
        var burst = -1f
        val st = animStart[role]
        if (st >= 0) {
            val t = (now() - st) / 1000f
            val twoPi = 2f * PI.toFloat()
            var live = true
            when (role) {
                IconRole.RING -> if (t < 1.1f) canvas.rotate(20f * exp(-t / 0.28f) * sin(twoPi * 6.5f * t), 12f, 3.2f) else live = false
                IconRole.SILENT -> if (t < 0.6f) {
                    canvas.rotate(9f * exp(-t / 0.16f) * sin(twoPi * 8f * t), 12f, 3.2f)
                    a = (a * (1f - 0.5f * sin(PI.toFloat() * min(t / 0.5f, 1f)))).toInt()
                } else live = false
                IconRole.VIBRATE -> if (t < 0.8f) {
                    val e = exp(-t / 0.25f)
                    canvas.translate(1.6f * e * sin(twoPi * 17f * t), 0f)
                    canvas.rotate(5f * e * sin(twoPi * 11f * t), 12f, 12f)
                } else live = false
                IconRole.DND -> if (t < 1.0f) {
                    if (animVariant[role] == 1) {            // turning on: the moon swings in
                        val s = springStep(t, 0.5f, 0.62f)
                        canvas.rotate(-110f * (1f - s), 12f, 12f)
                        val k = 0.55f + 0.45f * s
                        canvas.scale(k, k, 12f, 12f)
                    } else canvas.rotate(14f * exp(-t / 0.15f) * sin(twoPi * 4f * t), 12f, 12f)
                } else live = false
                IconRole.WORK, IconRole.PERSONAL, IconRole.BT, IconRole.CAMERA, IconRole.CALC, IconRole.PLAY, IconRole.PAUSE -> if (t < 0.6f) {
                    val k = popScale(role, -1)
                    canvas.scale(k, k, 12f, 12f)
                    if (animVariant[role] == 1) burst = t / 0.6f
                } else live = false
                IconRole.TORCH_ON -> if (t < 0.7f) beams = springStep(t, 0.35f, 0.55f) else live = false
                IconRole.TORCH -> if (t < 0.4f) canvas.rotate(6f * exp(-t / 0.1f) * sin(twoPi * 6f * t), 12f, 14f) else live = false
                IconRole.WIFI -> if (t >= 0.9f) live = false
                IconRole.SHOT -> if (t < 0.45f) { val k = 1f - 0.22f * sin(PI.toFloat() * min(t / 0.3f, 1f)); canvas.scale(k, k, 12f, 12f) } else live = false
                IconRole.NEXT -> if (t < 0.5f) canvas.translate(3f * exp(-t / 0.1f) * sin(twoPi * 2f * t + 0.6f), 0f) else live = false
                IconRole.PREV -> if (t < 0.5f) canvas.translate(-3f * exp(-t / 0.1f) * sin(twoPi * 2f * t + 0.6f), 0f) else live = false
                else -> live = false
            }
            if (live) animating = true else animStart[role] = -1L
        }
        val wifiT = if (role == IconRole.WIFI && animStart[role] >= 0) (now() - animStart[role]) / 1000f else -1f

        val sh = if (gradient) gradShaders[role] else null
        var waveIdx = 0
        var waves = 0
        if (level >= 0f) for (tg in tags) if (tg == 1) waves++
        var arcIdx = 0
        for (i in paths.indices) {
            val p = paths[i]
            var la = a
            val tag = tags[i]
            var pushed = false
            if (tag == 1 && level >= 0f) {            // speaker wave: appears and grows with the volume
                val vis = clamp01((level * waves - waveIdx) * 1.2f + 0.1f)
                waveIdx++
                if (vis <= 0.01f) continue
                la = (a * vis).toInt()
                canvas.save(); pushed = true
                val k = lerp(0.6f, 1f, vis)
                canvas.scale(k, k, 11.5f, 12f)
            } else if (tag == 2 && level >= 0f) {     // sun rays are drawn below, sized by the brightness
                continue
            } else if (tag == 3) {                    // torch beams spring out of the lamp
                if (beams <= 0.01f) continue
                canvas.save(); pushed = true
                canvas.scale(beams, beams, 12f, 7f)
            } else if (role == IconRole.SUN && level >= 0f) {
                canvas.save(); pushed = true
                val k = lerp(0.86f, 1.04f, level)
                canvas.scale(k, k, 12f, 12f)
            }
            if (wifiT >= 0f && role == IconRole.WIFI && kinds[i] == IconKind.STROKE) {
                // a ripple runs outwards through the arcs (innermost first)
                val order = 2 - arcIdx
                arcIdx++
                val ph = clamp01((wifiT - order * 0.12f) / 0.35f)
                la = (a * (1f - 0.6f * sin(PI.toFloat() * ph))).toInt()
            }
            when (kinds[i]) {
                IconKind.FILL -> {
                    iconFill.shader = sh; iconFill.color = if (sh != null) Color.WHITE else color; iconFill.alpha = la
                    canvas.drawPath(p, iconFill)
                }
                IconKind.TINT -> {
                    iconFill.shader = null; iconFill.color = color; iconFill.alpha = (la * 0.22f).toInt()
                    canvas.drawPath(p, iconFill)
                }
                IconKind.STROKE -> {
                    if (theme.iconGlow && glowOk) {
                        iconGlow.color = color; iconGlow.alpha = (la * 0.28f).toInt()
                        iconGlow.strokeWidth = theme.iconStroke * 3.2f
                        canvas.drawPath(p, iconGlow)
                    }
                    iconStroke.shader = sh; iconStroke.color = if (sh != null) Color.WHITE else color; iconStroke.alpha = la
                    iconStroke.strokeWidth = theme.iconStroke
                    canvas.drawPath(p, iconStroke)
                }
                IconKind.KNOCK, IconKind.KNOCK_STROKE -> {
                    iconStroke.shader = null; iconStroke.color = knockColor; iconStroke.alpha = la
                    iconStroke.strokeWidth = if (kinds[i] == IconKind.KNOCK) theme.knock else theme.iconStroke
                    canvas.drawPath(p, iconStroke)
                }
                IconKind.KNOCK_FILL -> {
                    iconFill.shader = null; iconFill.color = knockColor; iconFill.alpha = la
                    canvas.drawPath(p, iconFill)
                }
            }
            if (pushed) canvas.restore()
        }
        if (role == IconRole.SUN && level >= 0f) drawRays(canvas, level, color, a, sh)
        if (burst >= 0f) {
            // a ring that pops out of the icon and fades
            burstPaint.color = color
            burstPaint.alpha = (a * 0.55f * (1f - burst)).toInt()
            burstPaint.strokeWidth = 1.4f * (1f - burst) + 0.3f
            canvas.drawCircle(12f, 12f, lerp(9f, 17f, sqrt(burst)), burstPaint)
        }
        iconFill.shader = null; iconStroke.shader = null
        canvas.restore()
    }

    /** Sun rays: they lengthen and turn a little as the brightness goes up. */
    private fun drawRays(canvas: Canvas, level: Float, color: Int, a: Int, sh: Shader?) {
        val n = if (isLight) 4 else 8
        val r0 = 7.3f
        val len = lerp(0.25f, 2.7f, level)
        val rot = level * 22.5f
        iconStroke.shader = sh; iconStroke.color = if (sh != null) Color.WHITE else color
        iconStroke.alpha = (a * lerp(0.55f, 1f, level)).toInt()
        iconStroke.strokeWidth = theme.iconStroke.coerceAtMost(2f)
        for (i in 0 until n) {
            val ang = ((i * 360f / n + rot) * PI / 180f).toFloat()
            val cs = cos(ang)
            val sn = sin(ang)
            canvas.drawLine(12f + cs * r0, 12f + sn * r0, 12f + cs * (r0 + len), 12f + sn * (r0 + len), iconStroke)
        }
    }

    /** iOS-style page control: the current dot stretches into a capsule and slides with the page. */
    private fun drawDots(canvas: Canvas, cx: Float, y: Float, alpha: Float) {
        val n = pageCount
        val pf = page.value.coerceIn(0f, (n - 1).toFloat())
        val small = if (n > 4) dp(4f) else dp(5f)
        val extra = if (n > 4) dp(7f) else dp(9f)
        val gap = if (n > 4) dp(4f) else dp(5f)
        var total = 0f
        for (i in 0 until n) total += small + extra * clamp01(1f - abs(i - pf))
        total += gap * (n - 1)
        var x = cx - total / 2f
        for (i in 0 until n) {
            val t = clamp01(1f - abs(i - pf))
            val w = small + extra * t
            dotPaint.color = blend(theme.dotOff, dotOn, t)
            dotPaint.alpha = (Color.alpha(dotPaint.color) * alpha).toInt()
            tmpRect.set(x, y - small / 2f, x + w, y + small / 2f)
            canvas.drawRoundRect(tmpRect, small / 2f, small / 2f, dotPaint)
            x += w + gap
        }
    }

    private fun drawLabel(canvas: Canvas, depth: Float) {
        val a = clamp01(labelA.value) * clamp01(progress * 1.5f)
        if (a <= 0.01f || labelText.isEmpty()) return
        val capsule = theme.labelCapsule
        val tw = labelPaint.measureText(labelText)
        val iconSz = if (capsule) dp(16f) else dp(19f)
        val iconW = if (labelIcon >= 0) iconSz + dp(6f) else 0f
        val padH = if (capsule) dp(12f) else 0f
        val w = tw + iconW + 2 * padH
        val h = dp(32f)
        val gap = dp(12f) + (1f - a) * dp(12f)
        val left = if (sideRight) inX(depth) - gap - w else inX(depth) + gap
        val y = labelY.value
        if (capsule) {
            capsulePaint.color = theme.labelBg
            capsulePaint.alpha = (Color.alpha(theme.labelBg) * a).toInt()
            tmpRect.set(left, y - h / 2f, left + w, y + h / 2f)
            canvas.drawRoundRect(tmpRect, h / 2f, h / 2f, capsulePaint)
            if (theme.edgeGlow) {
                edgePaint.alpha = (200 * a).toInt()
                canvas.drawRoundRect(tmpRect, h / 2f, h / 2f, edgePaint)
            }
        }
        val textCol = if (labelColor != Color.WHITE) labelColor else theme.labelText
        if (labelIcon >= 0) drawIcon(canvas, labelIcon, left + padH + iconSz / 2f, y, iconSz, textCol, a, glowOk = true, gradient = false)
        labelPaint.color = textCol
        labelPaint.alpha = (255 * a).toInt()
        canvas.drawText(labelText, left + padH + iconW, y + labelPaint.textSize * 0.36f, labelPaint)
    }

    // ---------- state from the system ----------
    fun refreshState(animate: Boolean = true) {
        val vf = sound.media / sound.maxMedia.toFloat()
        val bf = bright.fraction
        if (g != G.SLIDER && g != G.SLIDER_PENDING) {
            // Keep the continuous value when it already maps to the system's step (no snapping to steps).
            val volSame = (vol.target * sound.maxMedia).roundToInt() == sound.media
            val briSame = abs(bri.target - bf) < 0.012f
            if (!animate) { vol.set(vf); bri.set(bf) }
            else {
                if (!volSame) vol.to(vf)
                if (!briSame) bri.to(bf)
            }
        }
        val rm = sound.ringerMode
        val targets = floatArrayOf(
            if (rm == AudioManager.RINGER_MODE_SILENT) 1f else 0f,
            if (rm == AudioManager.RINGER_MODE_VIBRATE) 1f else 0f,
            if (rm == AudioManager.RINGER_MODE_NORMAL) 1f else 0f,
            if (sound.dndOn) 1f else 0f,
            if (sound.mode == "work") 1f else 0f,
            if (sound.mode == "personal") 1f else 0f,
        )
        for (i in targets.indices) if (animate) sel[i].to(targets[i]) else sel[i].set(targets[i])
        // The widget states cost a system call each, so they're read only when their page can be seen.
        val cur = pages.getOrNull(currentPage())
        if (!animate || cur == PageType.QUICK || preview) {
            val q = floatArrayOf(if (torch.on) 1f else 0f, if (conn.wifiOn) 1f else 0f, if (conn.btOn) 1f else 0f)
            for (i in q.indices) if (animate) sel[S_QUICK + i].to(q[i]) else sel[S_QUICK + i].set(q[i])
        }
        if (!animate || cur == PageType.MEDIA || preview) {
            val pl = if (media.playing) 1f else 0f
            if (animate) playS.to(pl) else playS.set(pl)
        }
        invalidate()
    }

    /** Battery broadcast from the service (or a fresh sticky read). */
    fun onBattery(i: Intent?) {
        battery.read(i)
        if (expanded && pages.getOrNull(currentPage()) == PageType.MEDIA) battFill.to(battery.level) else battFill.set(battery.level)
        invalidate()
    }

    // ---------- open / close ----------
    private fun ensureExpanded() {
        main.removeCallbacks(hidePanel)
        if (!expanded) {
            expanded = true
            refreshState(animate = false)
            pages = Prefs.pages(context)
            if (page.value > pageCount - 1) page.set((pageCount - 1).toFloat())
            if (pages.contains(PageType.MEDIA)) { battery.read(); battFill.set(0f) }
            host?.showPanelWindow()
        }
    }

    private fun openTo(velocity: Float = Float.NaN) {
        ensureExpanded()
        closing = false
        open.retune(0.42f, 0.78f)
        open.to(1f, velocity)
        bumpIdle()
    }

    fun expand() {
        if (expanded && !closing) { bumpIdle(); return }
        openTo()
        feedback.fx(Feedback.Sfx.OPEN, Feedback.Buzz.TICK)
        main.postDelayed({ announcePage() }, 200)
    }

    /**
     * Close. A tap outside (velocity 0) starts from rest with a critically damped spring, so the panel only
     * ever shrinks: no bounce and no reversal. A closing gesture hands over its own velocity.
     */
    fun collapse(velocity: Float = 0f) {
        if (preview || !expanded || closing) return
        closing = true
        main.removeCallbacks(idleCollapse)
        main.removeCallbacks(labelHide)
        labelA.to(0f)
        open.stop()
        open.retune(0.30f, 1.0f)
        open.to(0f, min(velocity, 0f))
        if (!open.running) finishCollapse()
    }

    private fun finishCollapse() {
        closing = false
        expanded = false
        g = G.NONE
        main.removeCallbacks(hideHandle); pendingHideHandle = false
        // The handle window takes over first, then (a couple of frames later) the panel window goes invisible.
        // Both show the identical closed shape in the identical place, so there is nothing to see.
        handleShown = true
        host?.setHandleShown(true)
        main.removeCallbacks(hidePanel)
        main.postDelayed(hidePanel, 50)
        invalidate()
    }

    fun collapseNow() {
        if (preview) return
        open.set(0f)
        labelA.set(0f)
        expanded = false
        closing = false
        g = G.NONE
        main.removeCallbacks(hideHandle); pendingHideHandle = false
        handleShown = true
        host?.setHandleShown(true)
        host?.hidePanelWindow()
        invalidate()
    }

    init {
        open.anim.addEndListener { _, canceled, value, _ ->
            if (!canceled && closing && value <= 0.01f) finishCollapse()
        }
    }

    private fun bumpIdle() {
        if (preview) return
        main.removeCallbacks(idleCollapse)
        main.postDelayed(idleCollapse, IDLE_MS)
    }

    // ---------- pages ----------
    private fun currentPage() = page.target.roundToInt().coerceIn(0, pageCount - 1)

    private fun goToPage(p: Int, velocity: Float = Float.NaN) {
        val target = p.coerceIn(0, pageCount - 1)
        val changed = target != currentPage()
        page.to(target.toFloat(), velocity)
        if (changed) {
            Prefs.of(context).edit().putInt(Prefs.KEY_PAGE, target).apply()
            feedback.fx(Feedback.Sfx.SNAP, Feedback.Buzz.SNAP)
            labelA.to(0f)
            onPageArrive(pages[target])
            main.postDelayed({ announcePage() }, 160)
        }
    }

    private fun onPageArrive(type: Int) {
        when (type) {
            PageType.MEDIA -> { battery.read(); battFill.set(0f); battFill.to(battery.level); refreshState() }
            PageType.QUICK -> refreshState()
        }
    }

    private fun announcePage() {
        if (!expanded || closing) return
        when (pages.getOrNull(currentPage())) {
            PageType.RINGER -> when (sound.ringerMode) {
                AudioManager.RINGER_MODE_SILENT -> showLabel("SILENT", -1, slotY(0, 3))
                AudioManager.RINGER_MODE_VIBRATE -> showLabel("VIBRATE", -1, slotY(1, 3))
                else -> showLabel("RING", -1, slotY(2, 3))
            }
            PageType.MODES -> when (sound.mode) {
                "work" -> showLabel("WORK", -1, slotY(1, 3))
                "personal" -> showLabel("PERSONAL", -1, slotY(2, 3))
                else -> if (sound.dndOn) showLabel("DND ON", -1, slotY(0, 3))
            }
            PageType.MEDIA -> {
                val t = media.title
                if (t != null) showLabel(t, IconRole.PLAY, slotY(2, 4), 2200)
                else showLabel(battery.percent.toString() + "%" + if (battery.charging) " · CHARGING" else "", -1, slotY(0, 4), 1600)
            }
        }
    }

    // ---------- label ----------
    private fun showLabel(text: String, icon: Int, y: Float, holdMs: Long = 1500, color: Int = Color.WHITE) {
        var t = text
        val maxW = labelArea - dp(icon.let { if (it >= 0) 52f else 30f })
        if (labelPaint.measureText(t) > maxW) {
            val n = labelPaint.breakText(t, true, maxW - labelPaint.measureText("…"), null)
            t = t.substring(0, n.coerceAtLeast(1)).trimEnd() + "…"
        }
        labelText = t
        labelIcon = icon
        labelColor = color
        if (labelA.value < 0.05f) labelY.set(y) else labelY.to(y)
        labelA.to(1f)
        main.removeCallbacks(labelHide)
        main.postDelayed(labelHide, holdMs)
        invalidate()
    }

    // ---------- actions ----------
    private fun slotCount(type: Int) = when (type) {
        PageType.RINGER, PageType.MODES -> 3
        PageType.QUICK, PageType.MEDIA -> 4
        PageType.APPS -> apps.size.coerceIn(1, 4)
        else -> 0
    }

    private fun slotBase(type: Int) = when (type) {
        PageType.RINGER -> S_RINGER; PageType.MODES -> S_MODES; PageType.QUICK -> S_QUICK
        PageType.MEDIA -> S_MEDIA; PageType.APPS -> S_APPS; else -> -1
    }

    private fun slotAt(type: Int, y: Float): Int {
        val n = slotCount(type)
        if (n == 0) return -1
        val i = ((y - contentCy()) / slotSpacing(n) + (n - 1) / 2f).roundToInt()
        return if (i in 0 until n) i else -1
    }

    private fun tapSlot(type: Int, i: Int) {
        when (type) {
            PageType.RINGER -> {
                val m = arrayOf(AudioManager.RINGER_MODE_SILENT, AudioManager.RINGER_MODE_VIBRATE, AudioManager.RINGER_MODE_NORMAL)[i]
                if (sound.setRinger(m)) {
                    showLabel(arrayOf("SILENT", "VIBRATE", "RING")[i], -1, slotY(i, 3))
                    when (i) {
                        0 -> { kick(IconRole.SILENT); feedback.fx(Feedback.Sfx.SILENT, Feedback.Buzz.SILENT) }
                        1 -> { kick(IconRole.VIBRATE); feedback.fx(Feedback.Sfx.VIBRATE, Feedback.Buzz.VIBRATE) }
                        else -> { kick(IconRole.RING); feedback.fx(Feedback.Sfx.RING, Feedback.Buzz.RING) }
                    }
                } else { feedback.fx(Feedback.Sfx.CLICK, Feedback.Buzz.CLICK); needDnd(slotY(i, 3)) }
            }
            PageType.MODES -> when (i) {
                0 -> {
                    val on = !sound.dndOn
                    if (sound.setDnd(on)) {
                        if (!on && sound.mode == "work") sound.mode = ""
                        showLabel(if (on) "DND ON" else "DND OFF", -1, slotY(0, 3))
                        kick(IconRole.DND, if (on) 1 else 0)
                        feedback.fx(if (on) Feedback.Sfx.DND_ON else Feedback.Sfx.DND_OFF, Feedback.Buzz.DND)
                    } else { feedback.fx(Feedback.Sfx.CLICK, Feedback.Buzz.CLICK); needDnd(slotY(0, 3)) }
                }
                1 -> { val ok = sound.applyWork(); if (ok) { showLabel("WORK", -1, slotY(1, 3)); kick(IconRole.WORK, 1); feedback.fx(Feedback.Sfx.POP, Feedback.Buzz.POP) } else needDnd(slotY(1, 3)) }
                2 -> { val ok = sound.applyPersonal(); if (ok) { showLabel("PERSONAL", -1, slotY(2, 3)); kick(IconRole.PERSONAL, 1); feedback.fx(Feedback.Sfx.POP, Feedback.Buzz.POP) } else needDnd(slotY(2, 3)) }
            }
            PageType.QUICK -> when (i) {
                0 -> {
                    if (torch.toggle()) {
                        val on = torch.on
                        kick(if (on) IconRole.TORCH_ON else IconRole.TORCH)
                        showLabel(if (on) "TORCH ON" else "TORCH OFF", -1, slotY(0, 4))
                        feedback.fx(if (on) Feedback.Sfx.TOGGLE_ON else Feedback.Sfx.TOGGLE_OFF, Feedback.Buzz.CLICK)
                    } else {
                        feedback.fx(Feedback.Sfx.CLICK, Feedback.Buzz.CLICK)
                        showLabel(if (torch.available) "CAMERA BUSY" else "NO FLASH", -1, slotY(0, 4), 1500, 0xFFFFD60A.toInt())
                    }
                }
                1 -> { kick(IconRole.WIFI); feedback.fx(Feedback.Sfx.CLICK, Feedback.Buzz.CLICK); showLabel("WI-FI", -1, slotY(1, 4), 900); closeThen(220) { conn.openWifi() } }
                2 -> { kick(IconRole.BT, 1); feedback.fx(Feedback.Sfx.CLICK, Feedback.Buzz.CLICK); showLabel("BLUETOOTH", -1, slotY(2, 4), 900); closeThen(220) { conn.openBluetooth() } }
                3 -> {
                    kick(IconRole.SHOT)
                    if (Build.VERSION.SDK_INT < 28) {
                        feedback.fx(Feedback.Sfx.CLICK, Feedback.Buzz.CLICK)
                        showLabel("ANDROID 9+ ONLY", -1, slotY(3, 4), 1600, 0xFFFFD60A.toInt())
                    } else if (ShotService.ready) {
                        feedback.buzz(Feedback.Buzz.CLICK)
                        // close first and shoot only after the panel window is hidden, so the panel isn't in the picture
                        if (!preview) {
                            afterHidden = { if (ShotService.take()) feedback.play(Feedback.Sfx.SHUTTER) }
                            main.postDelayed({ collapse() }, 90)
                        }
                    } else {
                        feedback.fx(Feedback.Sfx.CLICK, Feedback.Buzz.CLICK)
                        showLabel("ALLOW SCREENSHOT", IconRole.SHOT, slotY(3, 4), 1800, 0xFFFFD60A.toInt())
                        closeThen(700) { ShotService.openSettings(context) }
                    }
                }
            }
            PageType.MEDIA -> when (i) {
                0 -> { battery.read(); battFill.set(0f); battFill.to(battery.level); feedback.fx(Feedback.Sfx.TICK, Feedback.Buzz.TICK)
                    showLabel(battery.percent.toString() + "%" + if (battery.charging) " · CHARGING" else "", -1, slotY(0, 4)) }
                1 -> { media.prev(); kick(IconRole.PREV); feedback.fx(Feedback.Sfx.CLICK, Feedback.Buzz.CLICK); mediaLabelLater("PREVIOUS") }
                2 -> {
                    val wasPlaying = playS.target > 0.5f
                    media.playPause()
                    playS.to(if (wasPlaying) 0f else 1f)
                    kick(if (wasPlaying) IconRole.PLAY else IconRole.PAUSE)
                    feedback.fx(Feedback.Sfx.CLICK, Feedback.Buzz.CLICK)
                    showLabel(if (wasPlaying) "PAUSE" else "PLAY", -1, slotY(2, 4), 900)
                    main.postDelayed({ refreshState() }, 600)
                }
                3 -> { media.next(); kick(IconRole.NEXT); feedback.fx(Feedback.Sfx.CLICK, Feedback.Buzz.CLICK); mediaLabelLater("NEXT") }
            }
            PageType.APPS -> {
                val s = apps.getOrNull(i) ?: return
                when (s.kind) { AppSlots.CAMERA -> kick(IconRole.CAMERA); AppSlots.CALC -> kick(IconRole.CALC); else -> appPopStart[i] = now() }
                invalidate()
                feedback.fx(Feedback.Sfx.POP, Feedback.Buzz.POP)
                showLabel(s.label, -1, slotY(i, apps.size.coerceAtMost(4)), 900)
                closeThen(260) { appSlots.launch(s) }
            }
        }
        refreshState()
    }

    private fun mediaLabelLater(word: String) {
        showLabel(word, -1, slotY(if (word == "NEXT") 3 else 1, 4), 900)
        main.postDelayed({ if (expanded && !closing) media.title?.let { showLabel(it, IconRole.PLAY, slotY(2, 4), 2000) } }, 700)
    }

    /** Close the panel, then run [action] (opening another app or a settings panel). */
    private fun closeThen(delay: Long, action: () -> Unit) {
        main.postDelayed({ collapse() }, delay / 2)
        val h = host
        if (h != null) h.afterClose(delay, action) else if (!preview) main.postDelayed(action, delay)
    }

    private fun needDnd(y: Float) {
        showLabel("ALLOW DND", -1, y, 1800, 0xFFFFD60A.toInt())
        if (!preview) main.postDelayed({ collapse(); sound.openDndSettings() }, 700)
    }

    private fun needWriteSettings(y: Float, launch: Boolean) {
        showLabel(if (launch) "ALLOW BRIGHTNESS" else "TAP TO ALLOW", IconRole.SUN, y, 1800, 0xFFFFD60A.toInt())
        if (launch && !preview) main.postDelayed({ collapse(); bright.openSettings() }, 700)
    }

    // ---------- sliders ----------
    private var lastVolLevel = -1
    private var atEdge = false
    private var briPending = -1f
    private var lastBriWrite = 0L
    private val briWriter = Runnable { flushBrightness() }
    private var lastDetent = -1

    private fun flushBrightness() {
        if (briPending >= 0f) {
            bright.setFraction(briPending)
            briPending = -1f
            lastBriWrite = SystemClock.uptimeMillis()
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun sliderLabelY(which: Int, v: Float): Float = thumbCyFor(v).coerceIn(trackTop() + dp(14f), trackBot() - dp(14f))

    /** Visual value follows the finger continuously; [commit] also writes it to the system. */
    private fun sliderVisual(which: Int, raw: Float, commit: Boolean, velocity: Float) {
        val v = clamp01(raw)
        val over = raw - v
        val st = rubber(over * travel, dp(22f))
        if (which == VOL) { vol.set(v); overVol.set(st) } else { bri.set(v); overBri.set(st) }
        // liquid squish: the glass stretches with the drag speed and springs back when it slows down
        val sq = (velocity / (1400f * d)).coerceIn(-1f, 1f)
        (if (which == VOL) squishVol else squishBri).to(sq)
        val edgeNow = v <= 0f || v >= 1f
        if (edgeNow && !atEdge) feedback.fx(Feedback.Sfx.DETENT, Feedback.Buzz.TICK)   // detent at both ends
        atEdge = edgeNow
        // a very light haptic tick every 10% (no sound: a sound per step would be noisy)
        val det = (v * 10f).toInt()
        if (det != lastDetent) { if (lastDetent >= 0 && !edgeNow) feedback.buzz(Feedback.Buzz.LOW_TICK); lastDetent = det }
        if (commit) commitSlider(which, v)
        val pct = (v * 100f).roundToInt().coerceIn(0, 100)
        val icon = if (which == VOL) (if (v <= 0.004f) IconRole.VOLMUTE else IconRole.VOL) else IconRole.SUN
        showLabel(percent[pct], icon, sliderLabelY(which, v), 1100)
    }

    private fun commitSlider(which: Int, v: Float) {
        if (which == VOL) {
            val level = (v * sound.maxMedia).roundToInt()
            if (level != lastVolLevel) { sound.setMedia(level); lastVolLevel = level }
        } else {
            if (!bright.canWrite) return
            briPending = v
            val since = SystemClock.uptimeMillis() - lastBriWrite
            main.removeCallbacks(briWriter)
            if (since >= 40) flushBrightness() else main.postDelayed(briWriter, 40 - since)
        }
    }

    // ---------- touch ----------
    private enum class G { NONE, HANDLE, OPENING, UNDECIDED, PAGING, CLOSING, SLIDER_PENDING, SLIDER }

    private var g = G.NONE
    private var sliderWhich = VOL
    private var downX = 0f
    private var downY = 0f
    private var downRawX = 0f
    private var downRawY = 0f
    private var downTime = 0L
    private var moved = false
    private var downOnDots = false
    private var startProgress = 0f
    private var startPage = 0f
    private var sliderStart = 0f
    private var pressIdx = -1
    private var vt: VelocityTracker? = null

    private fun inward(dx: Float) = if (sideRight) -dx else dx

    private fun insideBlob(x: Float, y: Float): Boolean {
        val distFromEdge = if (sideRight) vw - x else x
        val half = halfNow
        return distFromEdge <= depthNow + dp(12f) && y >= cy - half - dp(10f) && y <= cy + half + dp(10f)
    }

    private fun insideHandleZone(x: Float, y: Float): Boolean {
        val distFromEdge = if (sideRight) vw - x else x
        return distFromEdge <= handleWindowW && abs(y - cy) <= handleWindowH / 2f
    }

    private fun hitSlider(x: Float, y: Float): Int {
        val p = currentPage()
        if (pages.getOrNull(p) != PageType.SLIDERS || abs(page.value - p) > 0.15f) return -1
        val cx = inX(depthNow / 2f)
        for (w in intArrayOf(VOL, BRI)) {
            val x0 = colCx(w, cx)
            if (abs(x - x0) <= thumbW / 2f + dp(6f) && y >= trackTop() - dp(16f) && y <= sliderIconCy() + dp(12f)) return w
        }
        return -1
    }

    private val promote = Runnable { if (g == G.SLIDER_PENDING) g = G.SLIDER }

    private fun track(e: MotionEvent) {
        val t = vt ?: VelocityTracker.obtain().also { vt = it }
        // Raw screen coordinates (both windows' events land in one tracker, in one space).
        val ox = e.rawX - e.x
        val oy = e.rawY - e.y
        e.offsetLocation(ox, oy)
        t.addMovement(e)
        e.offsetLocation(-ox, -oy)
    }

    /**
     * Touches from the handle window. Its pixel (0,0) sits at (handleOffsetX, handleOffsetY) in this view,
     * so the event is shifted into this view's space and runs through the same gesture code.
     */
    fun onHandleTouch(e: MotionEvent): Boolean {
        e.offsetLocation(handleOffsetX, handleOffsetY)
        try { gesture(e, fromHandle = true) } finally { e.offsetLocation(-handleOffsetX, -handleOffsetY) }
        return true
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            if (expanded && !preview) collapse()
            return false
        }
        gesture(e, fromHandle = false)
        return true
    }

    private fun gesture(e: MotionEvent, fromHandle: Boolean) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> onDown(e, fromHandle)
            MotionEvent.ACTION_MOVE -> { track(e); onMove(e) }
            MotionEvent.ACTION_UP -> { track(e); onUp(e, cancel = false) }
            // The system can take a gesture away mid-way (for example its own back gesture): settle, don't drop.
            MotionEvent.ACTION_CANCEL -> onUp(e, cancel = true)
        }
    }

    private fun releasePress() { if (pressIdx >= 0) { press[pressIdx].to(0f); pressIdx = -1 } }

    private fun onDown(e: MotionEvent, fromHandle: Boolean) {
        vt?.recycle(); vt = null
        track(e)
        main.removeCallbacks(idleCollapse)
        main.removeCallbacks(promote)
        downX = e.x; downY = e.y; downRawX = e.rawX; downRawY = e.rawY
        downTime = SystemClock.uptimeMillis()
        moved = false
        downOnDots = false
        atEdge = false
        lastDetent = -1
        releasePress()
        if (!expanded) {
            if (fromHandle || insideHandleZone(e.x, e.y)) {
                // Tracking starts on the press. The panel window is shown now (it draws the same closed
                // handle, so nothing changes on screen) and is ready the moment the finger moves inward.
                g = G.HANDLE
                main.removeCallbacks(hidePanel)
                if (!preview) host?.showPanelWindow()
                open.stop()
                startProgress = open.value
            } else g = G.NONE
            return
        }
        if (closing) {
            if (insideBlob(e.x, e.y)) openTo() else { g = G.NONE; return }   // catch it mid-close
        }
        if (!insideBlob(e.x, e.y)) { if (!preview) collapse(); g = G.NONE; return }
        if (preview) parent?.requestDisallowInterceptTouchEvent(true)
        page.stop()                       // grab: the pages stop under the finger
        startPage = page.value
        startProgress = open.value
        val w = hitSlider(e.x, e.y)
        if (w >= 0) {
            g = G.SLIDER_PENDING
            sliderWhich = w
            sliderStart = if (w == VOL) vol.value else bri.value
            lastVolLevel = sound.media
            (if (w == VOL) grabVol else grabBri).to(1f)
            main.postDelayed(promote, PROMOTE_MS)
        } else {
            g = G.UNDECIDED
            downOnDots = pageCount > 1 && e.y > cy + halfNow - dp(30f)
            val p = currentPage()
            if (!downOnDots && abs(page.value - p) < 0.1f) {
                val type = pages[p]
                val s = slotAt(type, e.y)
                if (s >= 0 && slotBase(type) >= 0) { pressIdx = slotBase(type) + s; press[pressIdx].to(1f) }
            }
        }
    }

    private fun pageDepthNow() = typeDepth[pages[currentPage()]]

    private fun scheduleHide() {
        if (expanded || preview) return
        main.removeCallbacks(hidePanel)
        main.postDelayed(hidePanel, 50)
    }

    private fun onMove(e: MotionEvent) {
        val dx = e.rawX - downRawX
        val dy = e.rawY - downRawY
        if (!moved && hypot(dx, dy) > touchSlop) { moved = true; releasePress() }
        when (g) {
            G.HANDLE -> {
                if (inward(dx) > openSlop && abs(dx) >= abs(dy) * 0.7f) {
                    // Pulled inward: the panel follows the finger from here on.
                    g = G.OPENING
                    ensureExpanded()
                    closing = false
                    open.stop()
                    startProgress = open.value
                    downRawX += if (sideRight) -openSlop else openSlop
                    feedback.buzz(Feedback.Buzz.TICK)
                    onMove(e)
                } else if (abs(dy) > dp(16f) && abs(dy) > abs(dx) * 1.5f) {
                    g = G.NONE                       // a vertical slide along the edge isn't ours
                    scheduleHide()
                }
            }
            G.OPENING, G.CLOSING -> {
                val raw = startProgress + inward(dx) / pageDepthNow()
                val p = if (raw > 1f) 1f + rubber(raw - 1f, 0.25f) else raw.coerceAtLeast(0f)
                open.set(p)
                invalidate()
            }
            G.UNDECIDED -> if (moved) {
                g = if (abs(dx) > abs(dy) * 1.2f && inward(dx) < 0f) { startProgress = open.value; G.CLOSING }
                else if (abs(dy) >= abs(dx) * 0.8f) G.PAGING
                else G.UNDECIDED
                if (g == G.PAGING) { downRawY = e.rawY; downRawX = e.rawX; labelA.to(0f) }
            }
            G.PAGING -> {
                val n1 = (pageCount - 1).toFloat()
                val raw = startPage - dy / pageSpacing
                val v = when {
                    raw < 0f -> rubber(raw, 0.35f)
                    raw > n1 -> n1 + rubber(raw - n1, 0.35f)
                    else -> raw
                }
                page.set(v)
                invalidate()
            }
            G.SLIDER_PENDING -> {
                val early = SystemClock.uptimeMillis() - downTime < PROMOTE_MS
                if (early && abs(dy) >= dp(FLICK_DP) && abs(dy) > abs(dx)) {
                    // Fast start on a slider: this is a page swipe, the slider is never touched.
                    main.removeCallbacks(promote)
                    (if (sliderWhich == VOL) grabVol else grabBri).to(0f)
                    g = G.PAGING
                    labelA.to(0f)
                    onMove(e)
                } else if (!early) {
                    main.removeCallbacks(promote)
                    g = G.SLIDER
                    onMove(e)
                }
            }
            G.SLIDER -> {
                if (sliderWhich == BRI && !bright.canWrite) return
                var vy = 0f
                vt?.let { it.computeCurrentVelocity(1000); vy = it.yVelocity }
                sliderVisual(sliderWhich, sliderStart - dy / travel, commit = true, velocity = -vy)
            }
            G.NONE -> {}
        }
    }

    private fun onUp(e: MotionEvent, cancel: Boolean) {
        main.removeCallbacks(promote)
        val t = vt
        var vx = 0f
        var vy = 0f
        if (t != null && !cancel) { t.computeCurrentVelocity(1000); vx = t.xVelocity; vy = t.yVelocity }
        vt?.recycle(); vt = null
        val dx = e.rawX - downRawX
        val dy = e.rawY - downRawY
        val pressed = pressIdx
        releasePress()
        when (g) {
            G.HANDLE -> if (!cancel && !moved) expand() else scheduleHide()
            G.OPENING -> {
                val v = inward(vx) / pageDepthNow()
                val openIt = if (cancel) open.value > 0.35f else open.value + v * 0.18f > 0.4f
                if (openIt) {
                    openTo(if (cancel) 0f else v)
                    feedback.fx(Feedback.Sfx.OPEN, Feedback.Buzz.TICK)
                    main.postDelayed({ announcePage() }, 200)
                } else collapse(v)
            }
            G.CLOSING -> {
                val v = inward(vx) / pageDepthNow()
                if (cancel || (open.value > 0.72f && v > -0.9f)) openTo(v) else collapse(v)
            }
            G.PAGING -> {
                val base = startPage.roundToInt().coerceIn(0, pageCount - 1)
                val vPage = -vy / pageSpacing
                val target = when {
                    cancel -> base
                    abs(vy) > 450f * d -> base + if (vy < 0) 1 else -1                // fling
                    abs(page.value - base) > 0.28f -> base + if (page.value > base) 1 else -1   // dragged far enough
                    else -> base
                }
                goToPage(target, vPage)
            }
            G.UNDECIDED -> if (!cancel && !moved) {
                val p = currentPage()
                if (downOnDots) {
                    val n = pageCount
                    val cx = inX(depthNow / 2f)
                    val zone = (depthNow - dp(8f)) / n
                    val tgt = ((downX - cx) / zone + (n - 1) / 2f).roundToInt().coerceIn(0, n - 1)
                    if (tgt != p) goToPage(tgt) else goToPage((p + 1) % n)
                } else if (abs(page.value - p) < 0.1f) {
                    val type = pages[p]
                    val i = slotAt(type, downY)
                    if (i >= 0) tapSlot(type, i) else page.to(p.toFloat())
                    if (pressed >= 0) press[pressed].to(0f, -6f)    // springs back past rest: a little pop
                } else page.to(p.toFloat())
            } else page.to(currentPage().toFloat())
            G.SLIDER_PENDING, G.SLIDER -> {
                val w = sliderWhich
                (if (w == VOL) grabVol else grabBri).to(0f)
                (if (w == VOL) overVol else overBri).to(0f, -vy * 0.2f)
                (if (w == VOL) squishVol else squishBri).to(0f)
                val needPerm = w == BRI && !bright.canWrite
                val flick = g == G.SLIDER_PENDING && abs(dy) > dp(14f) && abs(vy) > 400f * d && abs(dy) > abs(dx)
                when {
                    cancel -> if (g == G.SLIDER) commitSlider(w, if (w == VOL) vol.value else bri.value)
                    flick -> {
                        // A very short flick on a slider is a page swipe too.
                        labelA.to(0f)
                        val cur = currentPage()
                        if (vy < 0 && cur < pageCount - 1) goToPage(cur + 1, -vy / pageSpacing)
                        else if (vy > 0 && cur > 0) goToPage(cur - 1, -vy / pageSpacing)
                        else page.to(cur.toFloat(), -vy / pageSpacing)          // at an end: rubber bounce
                    }
                    needPerm -> needWriteSettings(sliderLabelY(w, bri.value), launch = !moved)
                    !moved -> {
                        // Tap on the track: the glass jumps there with a spring.
                        val v = clamp01((trackBot() - thumbInset - downY) / travel)
                        if (w == VOL) vol.to(v) else bri.to(v)
                        commitSlider(w, v)
                        feedback.fx(Feedback.Sfx.TICK, Feedback.Buzz.TICK)
                        val pct = (v * 100f).roundToInt().coerceIn(0, 100)
                        showLabel(percent[pct], if (w == VOL) IconRole.VOL else IconRole.SUN, sliderLabelY(w, v), 1100)
                    }
                    else -> {
                        val v = if (w == VOL) vol.value else bri.value
                        commitSlider(w, v)
                        if (w == BRI) { main.removeCallbacks(briWriter); flushBrightness() }
                    }
                }
            }
            G.NONE -> {}
        }
        g = G.NONE
        if (expanded) bumpIdle()
    }

    // ---------- hooks for the JVM tests and preview renders (no effect on the app) ----------
    internal val debugPage get() = page.value
    internal val debugPageTarget get() = page.target
    internal val debugVol get() = vol.value
    internal val debugBri get() = bri.value
    internal val debugOpen get() = open.value
    internal val debugExpanded get() = expanded
    internal val debugClosing get() = closing
    internal val debugPageCount get() = pageCount
    internal val debugPages get() = pages.copyOf()
    internal val debugHandleShown get() = handleShown
    /** The blob's outer extent (top, bottom, depth) right now. */
    internal fun debugBlob(): FloatArray = floatArrayOf(cy - halfNow - sNow, cy + halfNow + sNow, depthNow)
    internal fun debugThumb(which: Int): RectF { val r = RectF(); thumbRect(which, r); return r }
    internal fun debugSquish(which: Int) = if (which == 1) squishVol.value else squishBri.value
    internal fun debugSetClock(t: Long) { debugClock = t }
    internal fun debugKick(role: Int, variant: Int = 0) = kick(role, variant)
    internal fun debugPose(p: Int, volF: Float, briF: Float, openF: Float = 1f, label: Boolean = true) {
        page.set(p.toFloat()); vol.set(volF); bri.set(briF); open.set(openF)
        expanded = openF > 0f
        refreshState(animate = false)
        vol.set(volF); bri.set(briF)
        battFill.set(battery.level)
        labelA.set(0f)
        if (label) {
            if (pages.getOrNull(p) == PageType.SLIDERS) {
                showLabel(percent[(volF * 100).roundToInt()], IconRole.VOL, sliderLabelY(VOL, volF))
            } else announcePage()
            labelA.set(1f)
        }
        invalidate()
    }
    internal fun debugGrab(which: Int, grab: Float, squish: Float) {
        (if (which == 1) grabVol else grabBri).set(grab)
        (if (which == 1) squishVol else squishBri).set(squish)
        invalidate()
    }
    internal fun debugLabel(text: String, icon: Int, slot: Int, n: Int) { showLabel(text, icon, slotY(slot, n)); labelA.set(1f) }
    internal fun debugSel(idx: Int, v: Float) { sel[idx].set(v); invalidate() }
    internal fun debugPlaying(v: Float) { playS.set(v); invalidate() }

    init {   // last, so every property above is initialised
        isHapticFeedbackEnabled = true
        // A charging bolt, 24x24 units
        boltPath.moveTo(13.4f, 2.6f); boltPath.lineTo(6.2f, 13.2f); boltPath.lineTo(11.4f, 13.2f)
        boltPath.lineTo(10.4f, 21.4f); boltPath.lineTo(17.8f, 10.6f); boltPath.lineTo(12.5f, 10.6f); boltPath.close()
        torch.onChange = { refreshState() }
        apps = appSlots.slots(dp(30f).toInt())
        battery.read()
        battFill.set(battery.level)
        applyTheme()
        refreshState(animate = false)
        if (preview) { expanded = true; open.set(1f); handleShown = false }
    }

    override fun onDetachedFromWindow() {
        main.removeCallbacksAndMessages(null)
        Prefs.of(context).unregisterOnSharedPreferenceChangeListener(prefListener)
        try { context.contentResolver.unregisterContentObserver(brightObserver) } catch (_: Exception) { }
        open.stop(); page.stop(); vol.stop(); bri.stop(); labelA.stop(); labelY.stop()
        grabVol.stop(); grabBri.stop(); overVol.stop(); overBri.stop(); squishVol.stop(); squishBri.stop()
        playS.stop(); battFill.stop()
        sel.forEach { it.stop() }; press.forEach { it.stop() }
        vt?.recycle(); vt = null
        super.onDetachedFromWindow()
    }

    /** Called by the service when it goes away. */
    fun release() { torch.release(); feedback.release() }
}

/** Colour blend a -> b by t (0..1), alpha included. */
fun blend(a: Int, b: Int, t: Float): Int {
    val u = t.coerceIn(0f, 1f)
    val ia = 1f - u
    return Color.argb(
        (Color.alpha(a) * ia + Color.alpha(b) * u).toInt(),
        (Color.red(a) * ia + Color.red(b) * u).toInt(),
        (Color.green(a) * ia + Color.green(b) * u).toInt(),
        (Color.blue(a) * ia + Color.blue(b) * u).toInt(),
    )
}

/** The handle window's view: draws the closed handle (the panel's own drawing code) and catches the drag. */
@SuppressLint("ViewConstructor")
class HandleView(context: Context, private val panel: EdgePanelView) : View(context) {
    var shown = true
        set(v) { if (field != v) { field = v; invalidate() } }

    private val exclusion = ArrayList<android.graphics.Rect>(1)
    private val exclusionRect = android.graphics.Rect()

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (Build.VERSION.SDK_INT >= 29) {
            // Tell the system's edge-back gesture to leave this strip alone (Android caps it at 200dp per edge).
            exclusionRect.set(0, 0, width, height)
            exclusion.clear()
            exclusion.add(exclusionRect)
            systemGestureExclusionRects = exclusion
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (!shown) return
        canvas.save()
        canvas.translate(-panel.handleOffsetX, -panel.handleOffsetY)
        panel.drawHandle(canvas)
        canvas.restore()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = panel.onHandleTouch(event)
}
