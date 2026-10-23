package bot.tab.edgepanel

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The whole edge panel in one view: the thin handle, the gooey black blob that morphs out of the
 * screen edge, the three pages (volume, ringer, modes), the page dots and the floating label.
 */
@SuppressLint("ViewConstructor")
class EdgePanelView(
    context: Context,
    private val sound: SoundControl,
    val sideRight: Boolean,
) : View(context) {

    // ---------- sizes ----------
    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    private val handleDepth = dp(5f)
    private val handleHalf = dp(34f)
    private val handleS = dp(12f)
    private val panelDepth = dp(76f)
    private val panelS = dp(44f)
    private val pageHalf = floatArrayOf(dp(104f), dp(92f), dp(92f))
    private val labelArea = dp(170f)

    val collapsedWindowW = dp(22f).toInt()
    val collapsedWindowH = (2 * (handleHalf + handleS) + dp(16f)).toInt()
    private val expandedWindowW = (panelDepth + dp(14f) + labelArea).toInt()
    private val expandedWindowH = (2 * (pageHalf.max() + panelS) + dp(40f)).toInt()

    /** Set by the service; asks the overlay window to change size. */
    var windowSizer: ((Int, Int) -> Unit)? = null

    // ---------- state ----------
    private var progress = 0f          // 0 = handle, 1 = open (springs past 1)
    private var expanded = false
    private var animating: ValueAnimator? = null
    private var page = Prefs.of(context).getInt(Prefs.KEY_PAGE, 0).coerceIn(0, 2)
    private var prevPage = page
    private var pageT = 1f
    private var halfNow = pageHalf[page]
    private var halfFrom = halfNow
    private var pageAnim: ValueAnimator? = null

    private var volFrac = 0f           // shown fill 0..1
    private var volTarget = 0f
    private var draggingVolume = false

    private var labelText = ""
    private var labelColor = Color.WHITE
    private var labelY = 0f
    private var labelAlpha = 0f
    private var labelAnim: ValueAnimator? = null

    private val main = Handler(Looper.getMainLooper())
    private val idleCollapse = Runnable { if (!draggingVolume) collapse() }
    private val labelHide = Runnable { fadeLabel(0f, 260) }

    // ---------- paints ----------
    private val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.FILL }
    private val handleLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x88FFFFFF.toInt() }
    private val pillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2C2C2E.toInt() }
    private val pillFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        textSize = dp(18f)
        letterSpacing = 0.06f
        setShadowLayer(dp(6f), 0f, dp(1.5f), 0xAA000000.toInt())
    }

    private val blob = Path()
    private val pillPath = Path()
    private val fillRectPath = Path()
    private val fillPath = Path()
    private val tmpRect = RectF()

    // ---------- icons ----------
    private fun icon(id: Int): Drawable = context.getDrawable(id)!!.mutate()
    private val icBellOff = icon(R.drawable.ic_bell_off)
    private val icVibrate = icon(R.drawable.ic_vibrate)
    private val icBell = icon(R.drawable.ic_bell)
    private val icMoon = icon(R.drawable.ic_moon)
    private val icPerson = icon(R.drawable.ic_person)
    private val icBadge = icon(R.drawable.ic_badge)
    private val icVolUp = icon(R.drawable.ic_volume_up)
    private val icVolDown = icon(R.drawable.ic_volume_down)
    private val icVolOff = icon(R.drawable.ic_volume_off)

    private companion object {
        const val RED = 0xFFFF3B30.toInt()
        const val ORANGE = 0xFFFF9F0A.toInt()
        const val PURPLE_MOON = 0xFF8E7CFF.toInt()
        const val PURPLE_WORK = 0xFFC15CF2.toInt()
        const val TEAL = 0xFF22C7D6.toInt()
        const val GREY = 0xFF5E5E63.toInt()
        const val IDLE_MS = 3000L
    }

    init {
        refreshState(animate = false)
        isHapticFeedbackEnabled = true
    }

    // ---------- geometry helpers (mirrored for the left edge) ----------
    private val edgeX get() = if (sideRight) width.toFloat() else 0f
    /** x at a distance [dist] in from the screen edge. */
    private fun inX(dist: Float) = if (sideRight) width - dist else dist
    private val cy get() = height / 2f

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun clamp01(v: Float) = v.coerceIn(0f, 1f)

    private val depthNow get() = lerp(handleDepth, panelDepth, progress).coerceAtLeast(handleDepth * 0.6f)
    private val sNow get() = lerp(handleS, panelS, min(progress, 1.1f))
    private val halfShown get() = lerp(handleHalf, halfNow, min(progress, 1.06f).coerceAtLeast(0f))
    private val contentAlpha get() = clamp01((progress - 0.55f) / 0.4f)

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
        val k1 = 0.50f   // how long the curve hugs the edge (concave fillet)
        val k2 = 0.80f   // how round the convex corner is
        blob.rewind()
        blob.moveTo(outside, top0 - dp(1f))
        blob.lineTo(e, top0)
        blob.cubicTo(e, top0 + s * k1, inner, top1 - s * k2, inner, top1)
        blob.lineTo(inner, bot1)
        blob.cubicTo(inner, bot1 + s * k2, e, bot0 - s * k1, e, bot0)
        blob.lineTo(outside, bot0 + dp(1f))
        blob.close()
    }

    // ---------- drawing ----------
    override fun onDraw(canvas: Canvas) {
        if (width == 0) return
        val depth = depthNow
        val half = halfShown
        buildBlob(depth, half, sNow)
        blobPaint.alpha = (lerp(215f, 255f, clamp01(progress * 2f))).toInt()
        canvas.drawPath(blob, blobPaint)

        // The subtle line inside the handle, fading out as it opens.
        val lineA = clamp01(1f - progress * 3f)
        if (lineA > 0f) {
            handleLinePaint.alpha = (0x88 * lineA).toInt()
            val x = inX(depth * 0.5f)
            tmpRect.set(x - dp(1f), cy - handleHalf * 0.7f, x + dp(1f), cy + handleHalf * 0.7f)
            canvas.drawRoundRect(tmpRect, dp(1f), dp(1f), handleLinePaint)
        }

        val a = contentAlpha
        if (a > 0f) {
            val cx = inX(depth / 2f)
            if (pageT < 1f) {
                val dir = if (page > prevPage) 1f else -1f
                drawPage(canvas, prevPage, cx, -dir * dp(40f) * pageT, a * (1f - pageT))
                drawPage(canvas, page, cx, dir * dp(40f) * (1f - pageT), a * pageT)
            } else {
                drawPage(canvas, page, cx, 0f, a)
            }
            drawDots(canvas, cx, cy + half - dp(13f), a)
        }

        if (labelAlpha > 0f && labelText.isNotEmpty()) {
            labelPaint.alpha = (255 * labelAlpha * clamp01(progress * 1.5f)).toInt()
            labelPaint.color = labelColor
            labelPaint.alpha = (255 * labelAlpha * clamp01(progress * 1.5f)).toInt()
            val gap = dp(14f) + (1f - labelAlpha) * dp(10f)
            val tw = labelPaint.measureText(labelText)
            val x = if (sideRight) inX(depth) - gap - tw else inX(depth) + gap
            canvas.drawText(labelText, x, labelY + dp(6.5f), labelPaint)
        }

        // Smooth volume fill catch-up.
        if (!draggingVolume && abs(volFrac - volTarget) > 0.002f) {
            volFrac = lerp(volFrac, volTarget, 0.25f)
            postInvalidateOnAnimation()
        }
    }

    private fun contentCy() = cy - dp(8f)

    private fun drawPage(canvas: Canvas, p: Int, cx: Float, dy: Float, alpha: Float) {
        if (alpha <= 0.01f) return
        canvas.save()
        canvas.translate(0f, dy)
        when (p) {
            0 -> drawVolume(canvas, cx, alpha)
            1 -> drawRinger(canvas, cx, alpha)
            else -> drawModes(canvas, cx, alpha)
        }
        canvas.restore()
    }

    private fun pillRect(cx: Float, r: RectF) {
        val pw = panelDepth - dp(16f)
        val ph = dp(170f)
        val c = contentCy()
        r.set(cx - pw / 2, c - ph / 2, cx + pw / 2, c + ph / 2)
    }

    private fun drawVolume(canvas: Canvas, cx: Float, alpha: Float) {
        pillRect(cx, tmpRect)
        val radius = tmpRect.width() * 0.42f
        pillPath.rewind()
        pillPath.addRoundRect(tmpRect, radius, radius, Path.Direction.CW)
        pillBgPaint.alpha = (255 * alpha).toInt()
        canvas.drawPath(pillPath, pillBgPaint)

        val fillTop = tmpRect.bottom - tmpRect.height() * clamp01(volFrac)
        fillRectPath.rewind()
        fillRectPath.addRect(tmpRect.left - 1, fillTop, tmpRect.right + 1, tmpRect.bottom + 1, Path.Direction.CW)
        fillPath.rewind()
        fillPath.op(pillPath, fillRectPath, Path.Op.INTERSECT)
        pillFillPaint.alpha = (255 * alpha).toInt()
        canvas.drawPath(fillPath, pillFillPaint)

        val iconCy = tmpRect.bottom - dp(24f)
        val ic = when {
            volTarget <= 0.001f -> icVolOff
            volTarget < 0.5f -> icVolDown
            else -> icVolUp
        }
        val onWhite = fillTop < iconCy
        drawIcon(canvas, ic, cx, iconCy, dp(24f), if (onWhite) 0xFF3A3A3C.toInt() else Color.WHITE, alpha)
    }

    private fun slotY(i: Int) = contentCy() + (i - 1) * dp(54f)

    private fun drawRinger(canvas: Canvas, cx: Float, alpha: Float) {
        val mode = sound.ringerMode
        val items = arrayOf(
            Triple(icBellOff, RED, AudioManager.RINGER_MODE_SILENT),
            Triple(icVibrate, ORANGE, AudioManager.RINGER_MODE_VIBRATE),
            Triple(icBell, Color.WHITE, AudioManager.RINGER_MODE_NORMAL),
        )
        items.forEachIndexed { i, (ic, col, m) ->
            val active = m == mode
            drawIcon(canvas, ic, cx, slotY(i), if (active) dp(34f) else dp(28f),
                if (active) col else GREY, alpha)
        }
    }

    private fun drawModes(canvas: Canvas, cx: Float, alpha: Float) {
        val dnd = sound.dndOn
        val mode = sound.mode
        drawIcon(canvas, icMoon, cx, slotY(0), if (dnd) dp(32f) else dp(28f), if (dnd) PURPLE_MOON else GREY, alpha)
        drawIcon(canvas, icPerson, cx, slotY(1), if (mode == "work") dp(34f) else dp(28f), PURPLE_WORK,
            alpha * if (mode == "work") 1f else 0.5f)
        drawIcon(canvas, icBadge, cx, slotY(2), if (mode == "personal") dp(34f) else dp(28f), TEAL,
            alpha * if (mode == "personal") 1f else 0.5f)
    }

    private fun drawIcon(canvas: Canvas, dr: Drawable, cx: Float, cy: Float, size: Float, color: Int, alpha: Float) {
        val h = size / 2
        dr.setBounds((cx - h).roundToInt(), (cy - h).roundToInt(), (cx + h).roundToInt(), (cy + h).roundToInt())
        dr.setTint(color)
        dr.alpha = (255 * alpha).toInt().coerceIn(0, 255)
        dr.draw(canvas)
    }

    private fun drawDots(canvas: Canvas, cx: Float, y: Float, alpha: Float) {
        val gap = dp(9f)
        for (i in 0..2) {
            dotPaint.color = if (i == page) Color.WHITE else 0xFF4A4A4E.toInt()
            dotPaint.alpha = (255 * alpha).toInt()
            canvas.drawCircle(cx + (i - 1) * gap, y, dp(2.4f), dotPaint)
        }
    }

    // ---------- state from the system ----------
    fun refreshState(animate: Boolean = true) {
        volTarget = sound.media / sound.maxMedia.toFloat()
        if (!animate) volFrac = volTarget
        invalidate()
    }

    // ---------- open / close ----------
    private val spring = Interpolator { t ->
        // Damped spring: settles at 1 with a soft overshoot (~6%).
        (1f - exp(-6.5f * t) * cos(7.2f * t)).toFloat()
    }

    fun expand() {
        if (expanded) { bumpIdle(); return }
        expanded = true
        refreshState(animate = false)
        windowSizer?.invoke(expandedWindowW, expandedWindowH)
        animateProgress(1f, 560L, spring)
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        main.postDelayed({ announcePage() }, 220)
        bumpIdle()
    }

    fun collapse() {
        if (!expanded) return
        expanded = false
        draggingVolume = false
        main.removeCallbacks(idleCollapse)
        main.removeCallbacks(labelHide)
        fadeLabel(0f, 150)
        animateProgress(0f, 260L, DecelerateInterpolator(1.6f)) {
            windowSizer?.invoke(collapsedWindowW, collapsedWindowH)
        }
    }

    fun collapseNow() {
        animating?.cancel()
        expanded = false
        progress = 0f
        labelAlpha = 0f
        windowSizer?.invoke(collapsedWindowW, collapsedWindowH)
        invalidate()
    }

    private fun animateProgress(to: Float, dur: Long, interp: Interpolator, end: (() -> Unit)? = null) {
        animating?.cancel()
        val from = progress
        animating = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = dur
            interpolator = interp
            addUpdateListener { progress = from + (to - from) * (it.animatedValue as Float); invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    progress = to
                    invalidate()
                    if (!cancelled) end?.invoke()
                }
            })
            start()
        }
    }

    private fun bumpIdle() {
        main.removeCallbacks(idleCollapse)
        main.postDelayed(idleCollapse, IDLE_MS)
    }

    // ---------- pages ----------
    private fun goToPage(p: Int) {
        val target = p.coerceIn(0, 2)
        if (target == page) return
        prevPage = page
        page = target
        Prefs.of(context).edit().putInt(Prefs.KEY_PAGE, page).apply()
        halfFrom = halfNow
        pageAnim?.cancel()
        pageAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 420
            interpolator = spring
            addUpdateListener {
                val t = it.animatedValue as Float
                pageT = clamp01(t * 1.4f)
                halfNow = lerp(halfFrom, pageHalf[page], t)
                invalidate()
            }
            start()
        }
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        main.postDelayed({ announcePage() }, 120)
    }

    private fun announcePage() {
        if (!expanded) return
        when (page) {
            1 -> when (sound.ringerMode) {
                AudioManager.RINGER_MODE_SILENT -> showLabel("SILENT", slotY(0), Color.WHITE)
                AudioManager.RINGER_MODE_VIBRATE -> showLabel("VIBRATE", slotY(1), Color.WHITE)
                else -> showLabel("RING", slotY(2), Color.WHITE)
            }
            2 -> when (sound.mode) {
                "work" -> showLabel("WORK", slotY(1), Color.WHITE)
                "personal" -> showLabel("PERSONAL", slotY(2), Color.WHITE)
                else -> if (sound.dndOn) showLabel("DND ON", slotY(0), Color.WHITE)
            }
        }
    }

    // ---------- label ----------
    private fun showLabel(text: String, y: Float, color: Int, holdMs: Long = 1500) {
        labelText = text
        labelY = y
        labelColor = color
        main.removeCallbacks(labelHide)
        fadeLabel(1f, 180)
        main.postDelayed(labelHide, holdMs)
    }

    private fun fadeLabel(to: Float, dur: Long) {
        labelAnim?.cancel()
        val from = labelAlpha
        labelAnim = ValueAnimator.ofFloat(from, to).apply {
            duration = dur
            interpolator = DecelerateInterpolator()
            addUpdateListener { labelAlpha = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    // ---------- actions ----------
    private fun tapSlot(i: Int) {
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        when (page) {
            1 -> {
                val m = arrayOf(AudioManager.RINGER_MODE_SILENT, AudioManager.RINGER_MODE_VIBRATE, AudioManager.RINGER_MODE_NORMAL)[i]
                if (sound.setRinger(m)) {
                    showLabel(arrayOf("SILENT", "VIBRATE", "RING")[i], slotY(i), Color.WHITE)
                } else {
                    needDnd(slotY(i))
                }
            }
            2 -> when (i) {
                0 -> {
                    val on = !sound.dndOn
                    if (sound.setDnd(on)) {
                        if (!on && sound.mode == "work") sound.mode = ""
                        showLabel(if (on) "DND ON" else "DND OFF", slotY(0), Color.WHITE)
                    } else needDnd(slotY(0))
                }
                1 -> { val ok = sound.applyWork(); refreshState(); if (ok) showLabel("WORK", slotY(1), Color.WHITE) else needDnd(slotY(1)) }
                2 -> { val ok = sound.applyPersonal(); refreshState(); if (ok) showLabel("PERSONAL", slotY(2), Color.WHITE) else needDnd(slotY(2)) }
            }
        }
        invalidate()
    }

    private fun needDnd(y: Float) {
        showLabel("ALLOW DND", y, 0xFFFFD60A.toInt(), 1800)
        main.postDelayed({ collapse(); sound.openDndSettings() }, 700)
    }

    private fun setVolumeFromY(y: Float) {
        pillRect(inX(depthNow / 2f), tmpRect)
        val frac = clamp01((tmpRect.bottom - y) / tmpRect.height())
        val max = sound.maxMedia
        val level = (frac * max).roundToInt()
        if (level != sound.media) {
            sound.setMedia(level)
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        volFrac = frac
        volTarget = level / max.toFloat()
        showLabel("${(volTarget * 100).roundToInt()}%", y.coerceIn(tmpRect.top, tmpRect.bottom), Color.WHITE, 900)
        invalidate()
    }

    // ---------- touch ----------
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var downOnDots = false
    private val touchSlop = dp(8f)

    private fun inward(dx: Float) = if (sideRight) -dx else dx

    private fun insideBlob(x: Float, y: Float): Boolean {
        val distFromEdge = if (sideRight) width - x else x
        val half = halfShown
        return distFromEdge <= depthNow + dp(10f) && y >= cy - half - dp(6f) && y <= cy + half + dp(6f)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            collapse(); return false
        }
        if (!expanded) return handleTouchCollapsed(e)
        if (openingGesture) {
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) openingGesture = false
            if (e.actionMasked != MotionEvent.ACTION_DOWN) return true
            openingGesture = false
        }
        if (progress < 0.5f && animating?.isRunning == true) return true // closing animation
        bumpIdle()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; moved = false
                if (!insideBlob(e.x, e.y)) { collapse(); return true }
                downOnDots = e.y > cy + halfShown - dp(28f)
                if (page == 0 && !downOnDots) {
                    pillRect(inX(depthNow / 2f), tmpRect)
                    if (e.y >= tmpRect.top - dp(6f) && e.y <= tmpRect.bottom + dp(6f)) {
                        draggingVolume = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                        setVolumeFromY(e.y)
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(e.x - downX) > touchSlop || abs(e.y - downY) > touchSlop) moved = true
                if (draggingVolume) setVolumeFromY(e.y)
            }
            MotionEvent.ACTION_UP -> {
                val dx = e.x - downX
                val dy = e.y - downY
                if (draggingVolume) {
                    draggingVolume = false
                    bumpIdle()
                    return true
                }
                when {
                    inward(dx) < -dp(36f) && abs(dx) > abs(dy) -> collapse()          // flick back to the edge
                    abs(dy) > dp(32f) && abs(dy) > abs(dx) -> goToPage(page + if (dy < 0) 1 else -1)
                    !moved && downOnDots -> goToPage((page + 1) % 3)
                    !moved && page > 0 -> {
                        val i = ((e.y - contentCy()) / dp(54f)).roundToInt() + 1
                        if (i in 0..2) tapSlot(i)
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> draggingVolume = false
        }
        return true
    }

    /** True from the touch that opened the panel until that finger lifts: its events belong to the handle. */
    private var openingGesture = false

    /** A finger that went down on the closed handle (not a leftover of the gesture that closed the panel). */
    private var handleDown = false

    private fun handleTouchCollapsed(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            val distFromEdge = if (sideRight) width - e.x else e.x
            handleDown = distFromEdge <= collapsedWindowW + dp(4f) && abs(e.y - cy) <= collapsedWindowH / 2f
        }
        if (!handleDown) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; moved = false }
            MotionEvent.ACTION_MOVE -> {
                if (inward(e.x - downX) > dp(10f)) { moved = true; openingGesture = true; handleDown = false; expand() }
                else if (abs(e.y - downY) > touchSlop) moved = true
            }
            MotionEvent.ACTION_UP -> { if (!moved) expand(); handleDown = false }
            MotionEvent.ACTION_CANCEL -> { moved = false; handleDown = false }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        main.removeCallbacksAndMessages(null)
        animating?.cancel(); pageAnim?.cancel(); labelAnim?.cancel()
        super.onDetachedFromWindow()
    }
}
