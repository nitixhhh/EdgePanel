package bot.tab.edgepanel

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.database.ContentObserver
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
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Edge Panel v2: the handle, the gooey blob, three pages (sliders, ringer, modes), page dots and the
 * floating label, all in one view.
 *
 * Every motion is a spring (AndroidX SpringAnimation) tuned with iOS-style parameters
 * (response, dampingFraction -> stiffness = (2*pi/response)^2, dampingRatio = dampingFraction), and every
 * gesture hands its release velocity to the spring, so motion is interruptible and continuous.
 *
 * Page rule (the v1 bug was that the volume slider swallowed every vertical swipe):
 *  - a vertical swipe anywhere off the sliders changes page, and the pages follow the finger;
 *  - on a slider pill, a slow drag adjusts it; a quick flick still changes page (the slider is not touched);
 *  - the three dots at the bottom are tap targets for each page.
 */
@SuppressLint("ViewConstructor")
class EdgePanelView(
    context: Context,
    private val sound: SoundControl,
    private val bright: BrightnessControl,
    val sideRight: Boolean,
    /** Inside the setup screen: always open, no window resizing, no auto-close. */
    private val preview: Boolean = false,
) : View(context) {

    // ---------- sizes (dp) — the preview renderer uses the same numbers ----------
    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    private val handleDepth = dp(5f)
    private val handleHalf = dp(34f)
    private val handleS = dp(12f)
    private val panelS = dp(44f)
    private val pageDepth = floatArrayOf(dp(94f), dp(66f), dp(66f))
    private val pageHalf = floatArrayOf(dp(104f), dp(92f), dp(92f))
    private val pageSpacing = dp(120f)
    private val pillW = dp(26f)
    private val pillH = dp(156f)
    private val pillGap = dp(14f)
    private val slotSpacing = dp(54f)
    private val labelArea = dp(200f)
    private val touchSlop = dp(8f)

    val collapsedWindowW = dp(22f).toInt()
    val collapsedWindowH = (2 * (handleHalf + handleS) + dp(16f)).toInt()
    val expandedWindowW = (pageDepth.max() + dp(16f) + labelArea).toInt()
    val expandedWindowH = (2 * (pageHalf.max() + panelS) + dp(72f)).toInt()

    /** Set by the service; asks the overlay window to change size. */
    var windowSizer: ((Int, Int) -> Unit)? = null

    // ---------- springs ----------
    /** iOS spring parameters -> SpringForce. response in seconds, dampingFraction 0..1. */
    private fun iosForce(response: Float, dampingFraction: Float): SpringForce {
        val w = (2.0 * PI / response).toFloat()
        return SpringForce().setStiffness(w * w).setDampingRatio(dampingFraction)
    }

    private inner class Spring(response: Float, damping: Float, minChange: Float, initial: Float = 0f) {
        private val holder = FloatValueHolder(initial)
        val anim: SpringAnimation = SpringAnimation(holder).apply {
            spring = iosForce(response, damping)
            setMinimumVisibleChange(minChange)
            addUpdateListener { _, v, _ -> value = v; invalidate() }
        }
        var value = initial
            private set
        var target = initial
            private set
        val running get() = anim.isRunning

        fun retune(response: Float, damping: Float) {
            val w = (2.0 * PI / response).toFloat()
            anim.spring.setStiffness(w * w).setDampingRatio(damping)
        }

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
            if (!anim.isRunning && abs(value - to) < 0.0005f && velocity.isNaN()) { set(to); return }
            anim.animateToFinalPosition(to)
        }

        fun stop() { if (anim.isRunning) anim.cancel(); holder.value = value }
    }

    // open/close: response 0.42 / damping 0.78 opening (a soft overshoot), critically damped on close
    private val open = Spring(0.42f, 0.78f, 0.001f, 0f)
    private val page = Spring(0.38f, 0.86f, 0.001f, Prefs.of(context).getInt(Prefs.KEY_PAGE, 0).coerceIn(0, 2).toFloat())
    private val vol = Spring(0.30f, 0.92f, 0.001f)
    private val bri = Spring(0.30f, 0.92f, 0.001f)
    private val grabVol = Spring(0.25f, 0.62f, 0.002f)
    private val grabBri = Spring(0.25f, 0.62f, 0.002f)
    private val stretchVol = Spring(0.30f, 0.70f, 0.3f)
    private val stretchBri = Spring(0.30f, 0.70f, 0.3f)
    private val labelA = Spring(0.28f, 1.0f, 0.002f)
    private val labelY = Spring(0.22f, 0.85f, 0.3f)
    /** Selection pop for the six tappable icons: ringer 0..2, modes 3..5. */
    private val sel = Array(6) { Spring(0.32f, 0.58f, 0.002f) }

    private var expanded = false
    private var closing = false

    // ---------- label ----------
    private var labelText = ""
    private var labelIcon = -1
    private var labelColor = Color.WHITE
    private val percent = Array(101) { "$it%" }

    private val main = Handler(Looper.getMainLooper())
    private val idleCollapse = Runnable { if (g == G.NONE) collapse() }
    private val labelHide = Runnable { labelA.to(0f) }

    // ---------- theme ----------
    private var theme: PanelTheme = Themes.byId(Prefs.theme(context))
    private var fillA = 0
    private var fillB = 0
    private var glow = 0
    private var dotOn = 0
    private var knockColor = Color.BLACK
    private var iconPaths: Array<Array<Path>> = emptyArray()
    private var iconKinds: Array<IntArray> = emptyArray()
    private val gradShaders = arrayOfNulls<Shader>(IconRole.COUNT)

    // ---------- paints (allocated once; onDraw allocates nothing) ----------
    private val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val edgeGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val handleLinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val capsulePaint = Paint(Paint.ANTI_ALIAS_FLAG)
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
    private val fillRect = Path()
    private val fillPath = Path()
    private val tmpRect = RectF()
    private val sheenMatrix = Matrix()
    private var fillShader: Shader? = null

    // ---------- haptics ----------
    private val vibrator: Vibrator? = try {
        if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    } catch (_: Exception) { null }
    private val tickEffect: VibrationEffect? =
        if (Build.VERSION.SDK_INT >= 29) VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK) else null

    private fun tick() {
        val v = vibrator
        if (Build.VERSION.SDK_INT >= 29 && v != null && v.hasVibrator() && tickEffect != null) {
            try { v.vibrate(tickEffect); return } catch (_: Exception) { }
        }
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.KEY_THEME || (key != null && key.startsWith(Prefs.KEY_ACCENT_PREFIX))) applyTheme()
    }

    private val brightObserver = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) { if (g != G.SLIDER || sliderWhich != BRI) refreshState() }
    }

    private companion object {
        const val IDLE_MS = 3500L
        /** On a pill, the first 100 ms decide: a fast start is a page swipe, anything else drives the slider. */
        const val PROMOTE_MS = 100L
        const val FLICK_DP = 22f
        const val BRI = 0
        const val VOL = 1
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
        } else {
            fillA = theme.fillA; fillB = theme.fillB; glow = theme.glow; dotOn = theme.dotOn
        }
        knockColor = theme.panelBottom
        iconPaths = Array(IconRole.COUNT) { r -> Array(theme.icons[r].size) { i -> theme.icons[r][i].toPath() } }
        iconKinds = Array(IconRole.COUNT) { r -> IntArray(theme.icons[r].size) { i -> theme.icons[r][i].kind } }
        for (r in 0 until IconRole.COUNT) {
            val c = roleColor(r)
            val other = if (c == theme.fillA) theme.fillB else theme.fillA
            gradShaders[r] = if (theme.gradientIcons) LinearGradient(2f, 2f, 22f, 22f, c, other, Shader.TileMode.CLAMP) else null
        }
        fillShader = LinearGradient(0f, 0f, 0f, pillH, fillB, fillA, Shader.TileMode.CLAMP)
        trackPaint.color = theme.track
        if (Build.VERSION.SDK_INT >= 28) {
            if (Color.alpha(glow) > 0) fillPaint.setShadowLayer(dp(9f), 0f, 0f, glow) else fillPaint.clearShadowLayer()
            if (Color.alpha(theme.shadow) > 0) blobPaint.setShadowLayer(dp(14f), 0f, dp(3f), theme.shadow) else blobPaint.clearShadowLayer()
        }
        edgePaint.strokeWidth = dp(max(theme.strokeW, 0.01f))
        edgeGlowPaint.strokeWidth = dp(6f)
        labelPaint.textSize = if (theme.labelCapsule) dp(14f) else dp(18f)
        if (theme.labelCapsule) labelPaint.clearShadowLayer() else labelPaint.setShadowLayer(dp(6f), 0f, dp(1.5f), 0xAA000000.toInt())
        buildShaders()
        invalidate()
    }

    private fun roleColor(r: Int): Int = when (r) {
        IconRole.SILENT -> theme.silent
        IconRole.VIBRATE -> theme.vibrate
        IconRole.RING -> theme.ring
        IconRole.DND -> theme.dnd
        IconRole.WORK -> theme.work
        IconRole.PERSONAL -> theme.personal
        else -> theme.fillA
    }

    private fun buildShaders() {
        if (height == 0) return
        val c = height / 2f
        val ext = pageHalf.max() + panelS
        blobPaint.shader = if (theme.panelTop == theme.panelBottom) null
        else LinearGradient(0f, c - ext, 0f, c + ext, theme.panelTop, theme.panelBottom, Shader.TileMode.CLAMP)
        blobPaint.color = theme.panelBottom
        edgePaint.shader = if (theme.stroke2 != theme.stroke && Color.alpha(theme.stroke2) > 0)
            LinearGradient(0f, c - ext, 0f, c + ext, theme.stroke, theme.stroke2, Shader.TileMode.CLAMP) else null
        edgePaint.color = theme.stroke
        edgeGlowPaint.shader = edgePaint.shader
        edgeGlowPaint.color = theme.stroke
        sheenPaint.shader = RadialGradient(0f, 0f, 1f, 0x2EFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
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

    // ---------- geometry ----------
    private val edgeX get() = if (sideRight) width.toFloat() else 0f
    private fun inX(dist: Float) = if (sideRight) width - dist else dist
    private val cy get() = height / 2f
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun clamp01(v: Float) = v.coerceIn(0f, 1f)
    private val progress get() = open.value

    private fun pageInterp(arr: FloatArray): Float {
        val p = page.value.coerceIn(0f, 2f)
        val i = min(p.toInt(), 1)
        return lerp(arr[i], arr[i + 1], p - i)
    }

    private val depthOpen get() = pageInterp(pageDepth)
    private val halfOpen get() = pageInterp(pageHalf)
    private val depthNow get() = lerp(handleDepth, depthOpen, progress).coerceAtLeast(handleDepth * 0.6f)
    private val sNow get() = lerp(handleS, panelS, min(progress, 1.1f).coerceAtLeast(0f))
    private val halfNow get() = lerp(handleHalf, halfOpen, min(progress, 1.06f).coerceAtLeast(0f))
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

    // ---------- drawing ----------
    override fun onDraw(canvas: Canvas) {
        if (width == 0) return
        val depth = depthNow
        val half = halfNow
        buildBlob(depth, half, sNow)

        val pa = (theme.panelAlpha * lerp(0.84f, 1f, clamp01(progress * 2f))).toInt()
        blobPaint.alpha = pa
        canvas.drawPath(blob, blobPaint)

        // Glass sheen: a soft highlight near the top inner corner, like light on frosted glass.
        if (theme.id == "glass" || theme.id == "light") {
            val r = dp(130f)
            sheenMatrix.setScale(r, r)
            sheenMatrix.postTranslate(inX(depth * 0.35f), cy - half)
            sheenPaint.shader?.setLocalMatrix(sheenMatrix)
            sheenPaint.alpha = (255 * clamp01(progress)).toInt()
            canvas.drawPath(blob, sheenPaint)
        }
        if (theme.strokeW > 0f && Color.alpha(theme.stroke) > 0) {
            val ea = clamp01(progress * 1.5f)
            if (theme.edgeGlow) {
                edgeGlowPaint.alpha = (70 * ea).toInt()
                canvas.drawPath(edge, edgeGlowPaint)
            }
            edgePaint.alpha = (Color.alpha(theme.stroke) * ea).toInt()
            canvas.drawPath(edge, edgePaint)
        }

        // The subtle line inside the closed handle.
        val lineA = clamp01(1f - progress * 3f)
        if (lineA > 0f) {
            handleLinePaint.color = if (theme.id == "light") 0x661C1C1E else if (theme.id == "neon") theme.fillA else 0x88FFFFFF.toInt()
            handleLinePaint.alpha = (Color.alpha(handleLinePaint.color) * lineA).toInt()
            val x = inX(depth * 0.5f)
            tmpRect.set(x - dp(1f), cy - handleHalf * 0.7f, x + dp(1f), cy + handleHalf * 0.7f)
            canvas.drawRoundRect(tmpRect, dp(1f), dp(1f), handleLinePaint)
        }

        val a = contentAlpha
        if (a > 0f) {
            val cx = inX(depth / 2f)
            val pf = page.value
            canvas.save()
            canvas.clipPath(blob)
            // Content grows into place with the panel (0.86 -> 1, and overshoots with the spring).
            val grow = lerp(0.86f, 1f, progress.coerceIn(0f, 1.08f))
            canvas.scale(grow, grow, cx, contentCy())
            for (p in 0..2) {
                val dist = p - pf
                val pa2 = a * clamp01(1f - abs(dist) * 1.6f)
                if (pa2 <= 0.01f) continue
                val sc = 1f - 0.07f * min(1f, abs(dist))
                canvas.save()
                canvas.translate(0f, dist * pageSpacing)
                canvas.scale(sc, sc, cx, contentCy())
                when (p) {
                    0 -> drawSliders(canvas, pa2)
                    1 -> drawRinger(canvas, cx, pa2)
                    else -> drawModes(canvas, cx, pa2)
                }
                canvas.restore()
            }
            canvas.restore()
            drawDots(canvas, cx, cy + half - dp(13f), a)
        }
        drawLabel(canvas, depth)
    }

    private fun pillCx(which: Int, cx: Float) = if (which == BRI) cx - (pillW + pillGap) / 2f else cx + (pillW + pillGap) / 2f

    /** The pill's rect in view space (page 0 at rest). */
    private fun pillRect(which: Int, r: RectF) {
        val cx = pillCx(which, inX(depthNow / 2f))
        val grab = if (which == VOL) grabVol.value else grabBri.value
        val st = if (which == VOL) stretchVol.value else stretchBri.value
        val w = pillW * (1f + 0.16f * grab) * (1f - min(0.25f, abs(st) / pillH * 0.6f))
        val c = pillCy()
        var top = c - pillH / 2f
        var bot = c + pillH / 2f
        if (st > 0f) top -= st else bot -= st
        r.set(cx - w / 2f, top, cx + w / 2f, bot)
    }

    private fun drawSliders(canvas: Canvas, alpha: Float) {
        drawPill(canvas, BRI, bri.value, IconRole.SUN, alpha)
        val v = vol.value
        drawPill(canvas, VOL, v, if (v <= 0.004f) IconRole.VOLMUTE else IconRole.VOL, alpha)
    }

    private fun drawPill(canvas: Canvas, which: Int, value: Float, icon: Int, alpha: Float) {
        pillRect(which, tmpRect)
        val w = tmpRect.width()
        val h = tmpRect.height()
        val left = tmpRect.left
        val top = tmpRect.top
        canvas.save()
        canvas.translate(left, top)
        tmpRect.set(0f, 0f, w, h)
        val radius = w / 2f
        pillPath.rewind()
        pillPath.addRoundRect(tmpRect, radius, radius, Path.Direction.CW)
        trackPaint.color = theme.track
        trackPaint.alpha = (Color.alpha(theme.track) * alpha).toInt()
        canvas.drawPath(pillPath, trackPaint)

        val f = clamp01(value)
        val fillTop = h - h * f
        if (f > 0.001f) {
            fillRect.rewind()
            fillRect.addRect(-1f, fillTop, w + 1f, h + 1f, Path.Direction.CW)
            fillPath.rewind()
            fillPath.op(pillPath, fillRect, Path.Op.INTERSECT)
            fillPaint.shader = fillShader
            fillPaint.color = Color.WHITE
            fillPaint.alpha = (255 * alpha).toInt()
            canvas.drawPath(fillPath, fillPaint)
        }
        // Icon inside the pill; its colour crossfades as the fill passes over it.
        val isz = w * 0.6f
        val icy = h - pillW * 0.62f
        val cover = clamp01((icy + isz / 2f - fillTop) / isz)
        val col = blend(theme.iconOnTrack, theme.iconOnFill, cover)
        drawIcon(canvas, icon, w / 2f, icy, isz, col, alpha, glowOk = false, gradient = false)
        canvas.restore()
    }

    private fun slotY(i: Int) = contentCy() + (i - 1) * slotSpacing

    private fun drawSlot(canvas: Canvas, role: Int, idx: Int, cx: Float, alpha: Float, dimRoleColor: Boolean) {
        val s = sel[idx].value
        val size = lerp(dp(26f), dp(33f), s)
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
        drawIcon(canvas, role, cx, slotY(idx % 3), size, col, a, glowOk = active, gradient = active)
    }

    private fun drawRinger(canvas: Canvas, cx: Float, alpha: Float) {
        drawSlot(canvas, IconRole.SILENT, 0, cx, alpha, false)
        drawSlot(canvas, IconRole.VIBRATE, 1, cx, alpha, false)
        drawSlot(canvas, IconRole.RING, 2, cx, alpha, false)
    }

    private fun drawModes(canvas: Canvas, cx: Float, alpha: Float) {
        val classic = theme.id == "classic"
        drawSlot(canvas, IconRole.DND, 3, cx, alpha, false)
        drawSlot(canvas, IconRole.WORK, 4, cx, alpha, classic)
        drawSlot(canvas, IconRole.PERSONAL, 5, cx, alpha, classic)
    }

    private fun drawIcon(canvas: Canvas, role: Int, cx: Float, cy: Float, size: Float, color: Int, alpha: Float,
                         glowOk: Boolean, gradient: Boolean) {
        val paths = iconPaths[role]
        val kinds = iconKinds[role]
        val a = (255 * alpha * Color.alpha(color) / 255f).toInt().coerceIn(0, 255)
        if (a == 0) return
        canvas.save()
        canvas.translate(cx - size / 2f, cy - size / 2f)
        val sc = size / 24f
        canvas.scale(sc, sc)
        val sh = if (gradient) gradShaders[role] else null
        for (i in paths.indices) {
            val p = paths[i]
            when (kinds[i]) {
                IconKind.FILL -> {
                    iconFill.shader = sh; iconFill.color = if (sh != null) Color.WHITE else color; iconFill.alpha = a
                    canvas.drawPath(p, iconFill)
                }
                IconKind.TINT -> {
                    iconFill.shader = null; iconFill.color = color; iconFill.alpha = (a * 0.22f).toInt()
                    canvas.drawPath(p, iconFill)
                }
                IconKind.STROKE -> {
                    if (theme.iconGlow && glowOk) {
                        iconGlow.color = color; iconGlow.alpha = (a * 0.28f).toInt()
                        iconGlow.strokeWidth = theme.iconStroke * 3.2f
                        canvas.drawPath(p, iconGlow)
                    }
                    iconStroke.shader = sh; iconStroke.color = if (sh != null) Color.WHITE else color; iconStroke.alpha = a
                    iconStroke.strokeWidth = theme.iconStroke
                    canvas.drawPath(p, iconStroke)
                }
                IconKind.KNOCK, IconKind.KNOCK_STROKE -> {
                    iconStroke.shader = null; iconStroke.color = knockColor; iconStroke.alpha = a
                    iconStroke.strokeWidth = if (kinds[i] == IconKind.KNOCK) theme.knock else theme.iconStroke
                    canvas.drawPath(p, iconStroke)
                }
                IconKind.KNOCK_FILL -> {
                    iconFill.shader = null; iconFill.color = knockColor; iconFill.alpha = a
                    canvas.drawPath(p, iconFill)
                }
            }
        }
        iconFill.shader = null; iconStroke.shader = null
        canvas.restore()
    }

    /** iOS-style page control: the current dot stretches into a capsule and slides with the page. */
    private fun drawDots(canvas: Canvas, cx: Float, y: Float, alpha: Float) {
        val pf = page.value.coerceIn(0f, 2f)
        val small = dp(5f)
        val extra = dp(9f)
        val gap = dp(5f)
        var total = 0f
        for (i in 0..2) total += small + extra * clamp01(1f - abs(i - pf))
        total += gap * 2
        var x = cx - total / 2f
        for (i in 0..2) {
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
            // Keep the continuous fill when it already maps to the system's step (no snapping to steps).
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
        for (i in 0..5) if (animate) sel[i].to(targets[i]) else sel[i].set(targets[i])
        invalidate()
    }

    // ---------- open / close ----------
    private fun ensureExpandedWindow() {
        if (!expanded) {
            expanded = true
            refreshState(animate = false)
            windowSizer?.invoke(expandedWindowW, expandedWindowH)
        }
    }

    private fun openTo(velocity: Float = Float.NaN) {
        ensureExpandedWindow()
        closing = false
        open.retune(0.42f, 0.78f)
        open.to(1f, velocity)
        bumpIdle()
    }

    fun expand() {
        if (expanded && !closing) { bumpIdle(); return }
        openTo()
        tick()
        main.postDelayed({ announcePage() }, 200)
    }

    fun collapse(velocity: Float = Float.NaN) {
        if (preview || !expanded || closing) return
        closing = true
        main.removeCallbacks(idleCollapse)
        main.removeCallbacks(labelHide)
        labelA.to(0f)
        open.retune(0.32f, 1.0f)
        open.to(0f, velocity)
        if (!open.running) finishCollapse()
    }

    private fun finishCollapse() {
        closing = false
        expanded = false
        windowSizer?.invoke(collapsedWindowW, collapsedWindowH)
        invalidate()
    }

    fun collapseNow() {
        if (preview) return
        open.set(0f)
        labelA.set(0f)
        expanded = false
        closing = false
        g = G.NONE
        windowSizer?.invoke(collapsedWindowW, collapsedWindowH)
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
    private fun currentPage() = page.target.roundToInt().coerceIn(0, 2)

    private fun goToPage(p: Int, velocity: Float = Float.NaN) {
        val target = p.coerceIn(0, 2)
        val changed = target != currentPage()
        page.to(target.toFloat(), velocity)
        if (changed) {
            Prefs.of(context).edit().putInt(Prefs.KEY_PAGE, target).apply()
            tick()
            labelA.to(0f)
            main.postDelayed({ announcePage() }, 160)
        }
    }

    private fun announcePage() {
        if (!expanded || closing) return
        when (currentPage()) {
            1 -> when (sound.ringerMode) {
                AudioManager.RINGER_MODE_SILENT -> showLabel("SILENT", -1, slotY(0))
                AudioManager.RINGER_MODE_VIBRATE -> showLabel("VIBRATE", -1, slotY(1))
                else -> showLabel("RING", -1, slotY(2))
            }
            2 -> when (sound.mode) {
                "work" -> showLabel("WORK", -1, slotY(1))
                "personal" -> showLabel("PERSONAL", -1, slotY(2))
                else -> if (sound.dndOn) showLabel("DND ON", -1, slotY(0))
            }
        }
    }

    // ---------- label ----------
    private fun showLabel(text: String, icon: Int, y: Float, holdMs: Long = 1500, color: Int = Color.WHITE) {
        labelText = text
        labelIcon = icon
        labelColor = color
        if (labelA.value < 0.05f) labelY.set(y) else labelY.to(y)
        labelA.to(1f)
        main.removeCallbacks(labelHide)
        main.postDelayed(labelHide, holdMs)
        invalidate()
    }

    // ---------- actions ----------
    private fun tapSlot(p: Int, i: Int) {
        tick()
        when (p) {
            1 -> {
                val m = arrayOf(AudioManager.RINGER_MODE_SILENT, AudioManager.RINGER_MODE_VIBRATE, AudioManager.RINGER_MODE_NORMAL)[i]
                if (sound.setRinger(m)) showLabel(arrayOf("SILENT", "VIBRATE", "RING")[i], -1, slotY(i))
                else needDnd(slotY(i))
            }
            2 -> when (i) {
                0 -> {
                    val on = !sound.dndOn
                    if (sound.setDnd(on)) {
                        if (!on && sound.mode == "work") sound.mode = ""
                        showLabel(if (on) "DND ON" else "DND OFF", -1, slotY(0))
                    } else needDnd(slotY(0))
                }
                1 -> { val ok = sound.applyWork(); if (ok) showLabel("WORK", -1, slotY(1)) else needDnd(slotY(1)) }
                2 -> { val ok = sound.applyPersonal(); if (ok) showLabel("PERSONAL", -1, slotY(2)) else needDnd(slotY(2)) }
            }
        }
        refreshState()
    }

    private fun needDnd(y: Float) {
        showLabel("ALLOW DND", -1, y, 1800, 0xFFFFD60A.toInt())
        main.postDelayed({ collapse(); sound.openDndSettings() }, 700)
    }

    private fun needWriteSettings(y: Float, launch: Boolean) {
        showLabel(if (launch) "ALLOW BRIGHTNESS" else "TAP TO ALLOW", IconRole.SUN, y, 1800, 0xFFFFD60A.toInt())
        if (launch) main.postDelayed({ collapse(); bright.openSettings() }, 700)
    }

    // ---------- sliders ----------
    private var lastVolLevel = -1
    private var atEdge = false
    private var briPending = -1f
    private var lastBriWrite = 0L
    private val briWriter = Runnable { flushBrightness() }

    private fun flushBrightness() {
        if (briPending >= 0f) {
            bright.setFraction(briPending)
            briPending = -1f
            lastBriWrite = SystemClock.uptimeMillis()
        }
    }

    /** Visual value follows the finger continuously; [commit] also writes it to the system. */
    private fun sliderVisual(which: Int, raw: Float, commit: Boolean) {
        val v = clamp01(raw)
        val over = raw - v
        val st = rubber(over * pillH, dp(26f))
        if (which == VOL) { vol.set(v); stretchVol.set(st) } else { bri.set(v); stretchBri.set(st) }
        val edgeNow = v <= 0f || v >= 1f
        if (edgeNow && !atEdge) tick()        // detent at the start and end of the track
        atEdge = edgeNow
        if (commit) commitSlider(which, v)
        pillRect(which, tmpRect)
        val ly = (tmpRect.bottom - tmpRect.height() * v).coerceIn(tmpRect.top + dp(14f), tmpRect.bottom - dp(14f))
        val pct = (v * 100f).roundToInt().coerceIn(0, 100)
        val icon = if (which == VOL) (if (v <= 0.004f) IconRole.VOLMUTE else IconRole.VOL) else IconRole.SUN
        showLabel(percent[pct], icon, ly, 1100)
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
    private var vt: VelocityTracker? = null

    private fun inward(dx: Float) = if (sideRight) -dx else dx

    private fun insideBlob(x: Float, y: Float): Boolean {
        val distFromEdge = if (sideRight) width - x else x
        val half = halfNow
        return distFromEdge <= depthNow + dp(12f) && y >= cy - half - dp(10f) && y <= cy + half + dp(10f)
    }

    private fun hitPill(x: Float, y: Float): Int {
        if (abs(page.value) > 0.15f) return -1
        for (w in intArrayOf(VOL, BRI)) {
            pillRect(w, tmpRect)
            if (x >= tmpRect.left - dp(7f) && x <= tmpRect.right + dp(7f) && y >= tmpRect.top - dp(8f) && y <= tmpRect.bottom + dp(8f)) return w
        }
        return -1
    }

    private val promote = Runnable { if (g == G.SLIDER_PENDING) g = G.SLIDER }

    private fun track(e: MotionEvent) {
        val t = vt ?: VelocityTracker.obtain().also { vt = it }
        // Raw screen coordinates, so a window resize mid-gesture never looks like a jump.
        val ox = e.rawX - e.x
        val oy = e.rawY - e.y
        e.offsetLocation(ox, oy)
        t.addMovement(e)
        e.offsetLocation(-ox, -oy)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            if (expanded && !preview) collapse()
            return false
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> onDown(e)
            MotionEvent.ACTION_MOVE -> { track(e); onMove(e) }
            MotionEvent.ACTION_UP -> { track(e); onUp(e, cancel = false) }
            MotionEvent.ACTION_CANCEL -> onUp(e, cancel = true)
        }
        return true
    }

    private fun onDown(e: MotionEvent) {
        vt?.recycle(); vt = null
        track(e)
        main.removeCallbacks(idleCollapse)
        main.removeCallbacks(promote)
        downX = e.x; downY = e.y; downRawX = e.rawX; downRawY = e.rawY
        downTime = SystemClock.uptimeMillis()
        moved = false
        downOnDots = false
        atEdge = false
        if (!expanded) {
            val distFromEdge = if (sideRight) width - e.x else e.x
            g = if (distFromEdge <= collapsedWindowW + dp(6f) && abs(e.y - cy) <= collapsedWindowH / 2f) G.HANDLE else G.NONE
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
        val w = hitPill(e.x, e.y)
        if (w >= 0) {
            g = G.SLIDER_PENDING
            sliderWhich = w
            sliderStart = if (w == VOL) vol.value else bri.value
            lastVolLevel = sound.media
            (if (w == VOL) grabVol else grabBri).to(1f)
            main.postDelayed(promote, PROMOTE_MS)
        } else {
            g = G.UNDECIDED
            downOnDots = e.y > cy + halfNow - dp(34f)
        }
    }

    private fun onMove(e: MotionEvent) {
        val dx = e.rawX - downRawX
        val dy = e.rawY - downRawY
        if (!moved && hypot(dx, dy) > touchSlop) moved = true
        when (g) {
            G.HANDLE -> if (moved) {
                if (inward(dx) > touchSlop && abs(dx) > abs(dy)) {
                    g = G.OPENING
                    ensureExpandedWindow()
                    closing = false
                    startProgress = open.value
                    open.stop()
                } else if (abs(dy) > touchSlop * 2) g = G.NONE
            }
            G.OPENING, G.CLOSING -> {
                val raw = startProgress + inward(dx) / pageDepth[currentPage()]
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
                val raw = startPage - dy / pageSpacing
                val v = when {
                    raw < 0f -> rubber(raw, 0.35f)
                    raw > 2f -> 2f + rubber(raw - 2f, 0.35f)
                    else -> raw
                }
                page.set(v)
                invalidate()
            }
            G.SLIDER_PENDING -> {
                val early = SystemClock.uptimeMillis() - downTime < PROMOTE_MS
                if (early && abs(dy) >= dp(FLICK_DP) && abs(dy) > abs(dx)) {
                    // Fast start on a pill: this is a page swipe, the slider is never touched.
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
                sliderVisual(sliderWhich, sliderStart - dy / pillH, commit = true)
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
        val dpPerS = d   // px per dp
        when (g) {
            G.HANDLE -> if (!cancel && !moved) expand()
            G.OPENING -> {
                val v = inward(vx) / pageDepth[currentPage()]
                if (!cancel && (open.value + v * 0.18f > 0.45f)) {
                    openTo(v); tick(); main.postDelayed({ announcePage() }, 200)
                } else collapse(v)
            }
            G.CLOSING -> {
                val v = inward(vx) / pageDepth[currentPage()]
                if (cancel || (open.value > 0.72f && v > -0.9f)) openTo(v) else collapse(v)
            }
            G.PAGING -> {
                val base = startPage.roundToInt().coerceIn(0, 2)
                val vPage = -vy / pageSpacing
                val target = when {
                    cancel -> base
                    abs(vy) > 450f * dpPerS -> base + if (vy < 0) 1 else -1                // fling
                    abs(page.value - base) > 0.28f -> base + if (page.value > base) 1 else -1   // dragged far enough
                    else -> base
                }
                goToPage(target, vPage)
            }
            G.UNDECIDED -> if (!cancel && !moved) {
                val p = currentPage()
                if (downOnDots) {
                    // Three tap zones across the dot row.
                    val cx = inX(depthNow / 2f)
                    val rel = (downX - cx) / (depthNow / 3f)
                    val tgt = when { rel < -0.5f -> 0; rel > 0.5f -> 2; else -> 1 }
                    if (tgt != p) goToPage(tgt) else goToPage((p + 1) % 3)
                } else if (p > 0 && abs(page.value - p) < 0.1f) {
                    val i = ((downY - contentCy()) / slotSpacing).roundToInt() + 1
                    if (i in 0..2) tapSlot(p, i)
                } else page.to(p.toFloat())
            } else page.to(currentPage().toFloat())
            G.SLIDER_PENDING, G.SLIDER -> {
                val w = sliderWhich
                (if (w == VOL) grabVol else grabBri).to(0f)
                (if (w == VOL) stretchVol else stretchBri).to(0f, -vy * 0.2f)
                val needPerm = w == BRI && !bright.canWrite
                val flick = g == G.SLIDER_PENDING && abs(dy) > dp(14f) && abs(vy) > 400f * dpPerS && abs(dy) > abs(dx)
                when {
                    cancel -> if (g == G.SLIDER) commitSlider(w, if (w == VOL) vol.value else bri.value)
                    flick -> {
                        // A very short flick on a pill is a page swipe too.
                        labelA.to(0f)
                        if (vy < 0) goToPage(1, -vy / pageSpacing)
                        else page.to(0f, -vy / pageSpacing)          // flick down on the first page: rubber bounce
                    }
                    needPerm -> { pillRect(w, tmpRect); needWriteSettings(tmpRect.centerY(), launch = !moved) }
                    !moved -> {
                        // Tap on the track: jump there with a spring.
                        pillRect(w, tmpRect)
                        val v = clamp01((tmpRect.bottom - downY) / tmpRect.height())
                        if (w == VOL) vol.to(v) else bri.to(v)
                        commitSlider(w, v)
                        val pct = (v * 100f).roundToInt().coerceIn(0, 100)
                        showLabel(percent[pct], if (w == VOL) IconRole.VOL else IconRole.SUN,
                            (tmpRect.bottom - tmpRect.height() * v).coerceIn(tmpRect.top + dp(14f), tmpRect.bottom - dp(14f)), 1100)
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
        bumpIdle()
    }

    // ---------- hooks for the JVM tests and preview renders (no effect on the app) ----------
    internal val debugPage get() = page.value
    internal val debugPageTarget get() = page.target
    internal val debugVol get() = vol.value
    internal val debugBri get() = bri.value
    internal val debugOpen get() = open.value
    internal val debugExpanded get() = expanded
    internal fun debugPose(p: Int, volF: Float, briF: Float, openF: Float = 1f, label: Boolean = true) {
        page.set(p.toFloat()); vol.set(volF); bri.set(briF); open.set(openF)
        expanded = openF > 0f
        refreshState(animate = false)
        vol.set(volF); bri.set(briF)
        labelA.set(0f)
        if (label) {
            if (p == 0) {
                pillRect(VOL, tmpRect)
                val ly = (tmpRect.bottom - tmpRect.height() * volF).coerceIn(tmpRect.top + dp(14f), tmpRect.bottom - dp(14f))
                showLabel(percent[(volF * 100).roundToInt()], IconRole.VOL, ly)
            } else announcePage()
            labelA.set(1f)
        }
        invalidate()
    }

    init {   // last, so every property above is initialised
        isHapticFeedbackEnabled = true
        applyTheme()
        refreshState(animate = false)
        if (preview) { expanded = true; open.set(1f) }
    }

    override fun onDetachedFromWindow() {
        main.removeCallbacksAndMessages(null)
        Prefs.of(context).unregisterOnSharedPreferenceChangeListener(prefListener)
        try { context.contentResolver.unregisterContentObserver(brightObserver) } catch (_: Exception) { }
        open.stop(); page.stop(); vol.stop(); bri.stop(); labelA.stop(); labelY.stop()
        grabVol.stop(); grabBri.stop(); stretchVol.stop(); stretchBri.stop()
        sel.forEach { it.stop() }
        vt?.recycle(); vt = null
        super.onDetachedFromWindow()
    }
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
