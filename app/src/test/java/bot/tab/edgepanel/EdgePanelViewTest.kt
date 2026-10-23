package bot.tab.edgepanel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.AudioManager
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.io.FileOutputStream
import java.time.Duration

class FakeHost : PanelHost {
    val log = ArrayList<String>()
    var panelVisible = false
    var handleVisible = true
    override fun showPanelWindow() { if (!panelVisible) log.add("show"); panelVisible = true }
    override fun hidePanelWindow() { if (panelVisible) log.add("hide"); panelVisible = false }
    override fun setHandleShown(shown: Boolean) { if (shown != handleVisible) log.add("handle:$shown"); handleVisible = shown }
    override fun afterClose(delayMs: Long, action: () -> Unit) { }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EdgePanelViewTest {

    private lateinit var ctx: Context
    private lateinit var view: EdgePanelView
    private val d get() = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = v * d
    private var downTime = 0L
    private val scratch by lazy { Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888) }

    @Before fun setUp() {
        // DynamicAnimation keeps a per-thread frame handler bound to the first test's Choreographer; reset it.
        try {
            val f = Class.forName("androidx.dynamicanimation.animation.AnimationHandler").getDeclaredField("sAnimatorHandler")
            f.isAccessible = true
            (f.get(null) as ThreadLocal<*>).remove()
        } catch (_: Exception) { }
        ctx = ApplicationProvider.getApplicationContext()
        Prefs.of(ctx).edit().clear().putString(Prefs.KEY_THEME, "classic").commit()
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.setStreamVolume(AudioManager.STREAM_MUSIC, 5, 0)
        view = make(preview = true)
    }

    private fun make(preview: Boolean, side: Boolean = true): EdgePanelView {
        val v = EdgePanelView(ctx, SoundControl(ctx), BrightnessControl(ctx), sideRight = side, preview = preview)
        val w = if (preview) (360 * d).toInt() else v.expandedWindowW
        val h = v.expandedWindowH
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        return v
    }

    private fun makeHandle(panel: EdgePanelView): HandleView {
        val h = HandleView(ctx, panel)
        h.measure(View.MeasureSpec.makeMeasureSpec(panel.handleWindowW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(panel.handleWindowH, View.MeasureSpec.EXACTLY))
        h.layout(0, 0, panel.handleWindowW, panel.handleWindowH)
        return h
    }

    private fun frames(ms: Long, drawEach: View? = null, each: (() -> Unit)? = null) {
        var t = 0L
        while (t < ms) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(8)); t += 8
            drawEach?.draw(Canvas(scratch))
            each?.invoke()
        }
    }

    private fun ev(action: Int, x: Float, y: Float): MotionEvent =
        MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)

    /** A straight-line gesture of [steps] moves over [ms], sent to [target]. */
    private fun gesture(x0: Float, y0: Float, x1: Float, y1: Float, ms: Long, steps: Int = 12, target: View = view,
                        onMove: ((Int) -> Unit)? = null, end: Int = MotionEvent.ACTION_UP, settle: Long = 1200) {
        downTime = SystemClock.uptimeMillis()
        target.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, x0, y0))
        for (i in 1..steps) {
            // Advance the clock only (running the looper here lets animation frames jump the clock).
            ShadowSystemClock.advanceBy(Duration.ofMillis(ms / steps))
            val f = i / steps.toFloat()
            target.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, x0 + (x1 - x0) * f, y0 + (y1 - y0) * f))
            onMove?.invoke(i)
        }
        target.dispatchTouchEvent(ev(end, x1, y1))
        frames(settle)
    }

    private val cx get() = view.width - dp(47f)        // page-0 panel centre (depth 94dp)
    private val cy get() = view.height / 2f
    private val volX get() = cx + dp(20f)

    // ---------------- v2 behaviour, kept ----------------

    @Test fun slowSwipeOffTheSlidersChangesPage() {
        assertEquals(0f, view.debugPage, 0.01f)
        gesture(cx, cy - dp(100f), cx, cy - dp(190f), 400)       // top strip, slow
        assertEquals(1f, view.debugPage, 0.01f)
        gesture(view.width - dp(33f), cy, view.width - dp(33f), cy - dp(90f), 350)   // ringer page, slow
        assertEquals(2f, view.debugPage, 0.01f)
        gesture(view.width - dp(33f), cy - dp(40f), view.width - dp(33f), cy + dp(60f), 300)
        assertEquals(1f, view.debugPage, 0.01f)
    }

    @Test fun quickFlickOnASliderChangesPageAndLeavesVolumeAlone() {
        val before = view.debugVol
        gesture(volX, cy + dp(30f), volX, cy - dp(60f), 90, steps = 6)
        assertEquals(1f, view.debugPage, 0.01f)
        assertEquals(before, view.debugVol, 0.001f)
    }

    @Test fun shortFlickAlsoPages() {
        gesture(volX, cy + dp(30f), volX, cy - dp(10f), 60, steps = 3)
        assertEquals(1f, view.debugPage, 0.01f)
    }

    @Test fun slowDragOnVolumeSliderAdjustsVolumeNotPage() {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val before = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        downTime = SystemClock.uptimeMillis()
        view.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, volX, cy + dp(20f)))
        ShadowSystemClock.advanceBy(Duration.ofMillis(140))
        for (i in 1..20) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(30))
            view.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, volX, cy + dp(20f) - dp(3f) * i))
        }
        view.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, volX, cy - dp(40f)))
        frames(800)
        assertEquals(0f, view.debugPage, 0.01f)
        val after = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        assertTrue("volume should go up: $before -> $after", after > before)
    }

    @Test fun fastSwipeDownOnFirstPageBouncesBack() {
        gesture(cx, cy - dp(100f), cx, cy + dp(40f), 120)
        assertEquals(0f, view.debugPage, 0.01f)
    }

    @Test fun tapOnDotsGoesToThatPage() {
        val n = view.debugPageCount
        assertEquals(6, n)
        val y = cy + dp(104f) - dp(13f)
        val zone = (dp(94f) - dp(8f)) / n
        val x = cx + (2 - (n - 1) / 2f) * zone
        gesture(x, y, x, y, 40, steps = 1)
        assertEquals(2f, view.debugPage, 0.01f)
    }

    // ---------------- v3: bug 1, drag the handle inward to open ----------------

    @Test fun pressAndDragOnTheHandleOpensFollowingTheFinger() {
        val panel = make(preview = false)
        val host = FakeHost()
        panel.host = host
        view = panel
        val handle = makeHandle(panel)
        val x0 = handle.width - dp(4f)
        val y0 = handle.height / 2f
        val seen = ArrayList<Float>()
        gesture(x0, y0, x0 - dp(70f), y0 + dp(6f), 220, steps = 10, target = handle, onMove = { seen.add(panel.debugOpen) })
        // follows the finger: grows with every move, and is already well open before the finger lifts
        for (i in 1 until seen.size) assertTrue("progress must grow with the finger: $seen", seen[i] >= seen[i - 1])
        assertTrue("panel follows the finger during the drag: $seen", seen.last() > 0.6f)
        assertTrue(panel.debugExpanded)
        assertEquals(1f, panel.debugOpen, 0.02f)
        assertEquals("show", host.log.first())
    }

    @Test fun pressHoldThenDragAlsoOpens() {
        val panel = make(preview = false)
        panel.host = FakeHost()
        val handle = makeHandle(panel)
        val x0 = handle.width - dp(10f)
        val y0 = handle.height / 2f
        downTime = SystemClock.uptimeMillis()
        handle.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, x0, y0))
        ShadowSystemClock.advanceBy(Duration.ofMillis(400))          // hold first
        for (i in 1..8) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(25))
            handle.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, x0 - dp(6f) * i, y0 - dp(1f) * i))
        }
        handle.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, x0 - dp(48f), y0 - dp(8f)))
        frames(1200)
        assertTrue(panel.debugExpanded)
        assertEquals(1f, panel.debugOpen, 0.02f)
    }

    @Test fun leftEdgeDragOpensToo() {
        val panel = make(preview = false, side = false)
        panel.host = FakeHost()
        val handle = makeHandle(panel)
        gesture(dp(4f), handle.height / 2f, dp(74f), handle.height / 2f, 220, target = handle)
        assertEquals(1f, panel.debugOpen, 0.02f)
    }

    @Test fun cancelledDragSettlesInsteadOfDropping() {
        // If the system takes the gesture (ACTION_CANCEL), a panel pulled past ~1/3 opens rather than snapping shut.
        val panel = make(preview = false)
        panel.host = FakeHost()
        val handle = makeHandle(panel)
        val x0 = handle.width - dp(4f)
        gesture(x0, handle.height / 2f, x0 - dp(55f), handle.height / 2f, 200, target = handle, end = MotionEvent.ACTION_CANCEL)
        assertEquals(1f, panel.debugOpen, 0.02f)
        val p2 = make(preview = false)
        val host2 = FakeHost()
        p2.host = host2
        val h2 = makeHandle(p2)
        gesture(x0, h2.height / 2f, x0 - dp(12f), h2.height / 2f, 120, target = h2, end = MotionEvent.ACTION_CANCEL)
        assertEquals(0f, p2.debugOpen, 0.01f)
        assertFalse(p2.debugExpanded)
        assertFalse("panel window hidden again", host2.panelVisible)
    }

    @Test fun tapOnTheHandleStillOpens() {
        val panel = make(preview = false)
        panel.host = FakeHost()
        val handle = makeHandle(panel)
        gesture(handle.width - dp(5f), handle.height / 2f, handle.width - dp(5f), handle.height / 2f, 60, steps = 1, target = handle)
        assertEquals(1f, panel.debugOpen, 0.02f)
    }

    @Test fun handleKeepsTheSystemBackGestureAway() {
        val panel = make(preview = false)
        val handle = makeHandle(panel)
        val rects = handle.systemGestureExclusionRects
        assertEquals(1, rects.size)
        assertEquals(handle.width, rects[0].width())
        assertEquals(handle.height, rects[0].height())
        assertTrue("Android honours at most 200dp per edge", rects[0].height() <= dp(200f))
        assertTrue("touch strip is wide enough to grab", handle.width >= dp(28f))
    }

    // ---------------- v3: bug 2, closing never jumps ----------------

    private fun openFully(panel: EdgePanelView, handle: HandleView) {
        gesture(handle.width - dp(5f), handle.height / 2f, handle.width - dp(5f), handle.height / 2f, 60, steps = 1, target = handle)
        frames(600, drawEach = panel)
        assertEquals(1f, panel.debugOpen, 0.02f)
    }

    private fun assertCleanClose(panel: EdgePanelView, host: FakeHost, startClose: () -> Unit) {
        val c0 = (panel.debugBlob()[0] + panel.debugBlob()[1]) / 2f
        var prevOpen = panel.debugOpen
        var prev = panel.debugBlob()
        var frames = 0
        var hiddenWhileVisible = false
        startClose()
        frames(900, drawEach = panel) {
            val b = panel.debugBlob()
            val o = panel.debugOpen
            assertEquals("the panel's centre line never moves", c0, (b[0] + b[1]) / 2f, 0.01f)
            assertTrue("progress only goes down ($prevOpen -> $o)", o <= prevOpen + 1e-5f)
            assertTrue("top edge only moves inward", b[0] >= prev[0] - 0.01f)
            assertTrue("bottom edge only moves inward", b[1] <= prev[1] + 0.01f)
            assertTrue("depth only shrinks", b[2] <= prev[2] + 0.01f)
            if (!host.panelVisible && o > 0.001f) hiddenWhileVisible = true
            prevOpen = o; prev = b; frames++
        }
        assertFalse("panel window must not disappear before the shape is fully closed", hiddenWhileVisible)
        assertEquals(0f, panel.debugOpen, 0.0001f)
        assertFalse(panel.debugExpanded)
        // hand-over order: the handle window draws again first, then the panel window goes invisible
        val iH = host.log.lastIndexOf("handle:true")
        val iHide = host.log.lastIndexOf("hide")
        assertTrue("handle shown again (${host.log})", iH >= 0)
        assertTrue("panel window hidden after (${host.log})", iHide > iH)
        assertTrue(frames > 10)
    }

    @Test fun tapOutsideClosesInOneContinuousMotion() {
        val panel = make(preview = false)
        val host = FakeHost()
        panel.host = host
        val handle = makeHandle(panel)
        openFully(panel, handle)
        assertFalse("handle window stops drawing once the panel is up", host.handleVisible)
        assertCleanClose(panel, host) {
            downTime = SystemClock.uptimeMillis()
            panel.dispatchTouchEvent(ev(MotionEvent.ACTION_OUTSIDE, 10f, 10f))
        }
    }

    @Test fun tapOutsideWhileStillOpeningAlsoClosesCleanly() {
        val panel = make(preview = false)
        val host = FakeHost()
        panel.host = host
        val handle = makeHandle(panel)
        gesture(handle.width - dp(5f), handle.height / 2f, handle.width - dp(5f), handle.height / 2f, 60, steps = 1, target = handle, settle = 0)
        frames(120, drawEach = panel)                     // mid-spring, still moving outward
        assertTrue(panel.debugOpen in 0.2f..1.2f)
        assertCleanClose(panel, host) {
            downTime = SystemClock.uptimeMillis()
            panel.dispatchTouchEvent(ev(MotionEvent.ACTION_OUTSIDE, 10f, 10f))
        }
    }

    @Test fun tapInsideWindowButOffThePanelClosesCleanly() {
        val panel = make(preview = false)
        val host = FakeHost()
        panel.host = host
        val handle = makeHandle(panel)
        openFully(panel, handle)
        assertCleanClose(panel, host) {
            downTime = SystemClock.uptimeMillis()
            panel.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, dp(20f), dp(30f)))
            panel.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, dp(20f), dp(30f)))
        }
    }

    @Test fun closedShapeIsIdenticalInBothWindows() {
        // The handle window draws the panel's own closed shape at the same screen spot, pixel for pixel.
        val panel = make(preview = false)
        panel.host = FakeHost()
        val handle = makeHandle(panel)
        val a = Bitmap.createBitmap(panel.width, panel.height, Bitmap.Config.ARGB_8888)
        panel.draw(Canvas(a))
        val b = Bitmap.createBitmap(handle.width, handle.height, Bitmap.Config.ARGB_8888)
        handle.draw(Canvas(b))
        val ox = panel.handleOffsetX.toInt()
        val oy = panel.handleOffsetY.toInt()
        var diff = 0
        var ink = 0
        for (y in 0 until b.height) for (x in 0 until b.width) {
            val pb = b.getPixel(x, y)
            val pa = a.getPixel(x + ox, y + oy)
            if (pb != 0) ink++
            if (pa != pb) diff++
        }
        assertTrue("handle drew something", ink > 50)
        assertEquals("pixels that differ", 0, diff)
    }

    // ---------------- v3: liquid glass, sounds, pages ----------------

    @Test fun glassThumbStretchesWithSpeedAndSpringsBack() {
        val rest = view.debugThumb(1)
        downTime = SystemClock.uptimeMillis()
        view.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, volX, cy + dp(20f)))
        ShadowSystemClock.advanceBy(Duration.ofMillis(140))
        var maxH = 0f
        for (i in 1..8) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(12))
            view.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, volX, cy + dp(20f) - dp(9f) * i))
            frames(8)
            maxH = maxOf(maxH, view.debugThumb(1).height())
        }
        view.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, volX, cy + dp(20f) - dp(72f)))
        assertTrue("fast drag stretches the glass: ${rest.height()} -> $maxH", maxH > rest.height() * 1.15f)
        frames(1500)
        assertEquals("springs back to its rest shape", rest.height(), view.debugThumb(1).height(), dp(0.5f))
    }

    @Test fun soundAndVibrationFollowTheSwitches() {
        val f = Feedback(ctx)
        assertTrue(f.soundOn); assertTrue(f.hapticOn)
        Prefs.of(ctx).edit().putBoolean(Prefs.KEY_SOUND, false).putBoolean(Prefs.KEY_HAPTIC, false).commit()
        f.reloadPrefs()
        assertFalse(f.soundOn); assertFalse(f.hapticOn)
        for (s in Feedback.Sfx.values()) {
            val len = ctx.resources.openRawResource(s.res).use { it.readBytes().size }
            assertTrue("${s.name} is a small sound (${len}B)", len in 1000..9000)
        }
    }

    @Test fun pagesFollowTheSetupOrder() {
        Prefs.of(ctx).edit().putString(Prefs.KEY_PAGE_ORDER, "4,0,1").putString(Prefs.KEY_PAGES_OFF, "2,3,5").commit()
        val v = make(preview = true)
        assertEquals(listOf(4, 0, 1), v.debugPages.toList())
        Prefs.of(ctx).edit().putString(Prefs.KEY_PAGES_OFF, "0,1,2,3,4,5").commit()
        assertEquals(listOf(PageType.SLIDERS), Prefs.pages(ctx).toList())
    }

    // ---------------- previews (the app's own drawing code) ----------------

    private fun setTheme(id: String) { Prefs.of(ctx).edit().putString(Prefs.KEY_THEME, id).commit(); view.applyTheme() }

    @Test fun renderPreviews() {
        val out = File(System.getProperty("previewDir") ?: "build/previews").apply { mkdirs() }
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        view.battery.set(0.78f, true)
        for (t in Themes.ALL) {
            setTheme(t.id)
            for (p in 0 until view.debugPageCount) {
                am.ringerMode = AudioManager.RINGER_MODE_NORMAL
                Prefs.of(ctx).edit().putString(Prefs.KEY_MODE, "").commit()
                when (p) {
                    1 -> am.ringerMode = AudioManager.RINGER_MODE_SILENT
                    2 -> { am.ringerMode = AudioManager.RINGER_MODE_VIBRATE; Prefs.of(ctx).edit().putString(Prefs.KEY_MODE, "work").commit() }
                }
                view.debugSetClock(-1)
                view.debugPose(p, 0.62f, 0.45f)
                when (p) {
                    3 -> { view.debugSel(6, 1f); view.debugSel(7, 1f); view.debugLabel("TORCH ON", -1, 0, 4) }
                    4 -> { view.debugPlaying(1f); view.debugLabel("Kesariya · Arijit Singh", IconRole.PLAY, 2, 4) }
                    5 -> view.debugLabel("CAMERA", -1, 0, 2)
                }
                save(view, File(out, "${t.id}_page$p.png"))
            }
            // the glass slider while being dragged fast (stretched, magnified)
            view.debugPose(0, 0.62f, 0.45f)
            view.debugGrab(1, 1f, 0.9f)
            save(view, File(out, "${t.id}_drag.png"))
            view.debugGrab(1, 0f, 0f)
            val c = make(preview = false)
            c.debugPose(0, 0.62f, 0.45f, openF = 0f, label = false)
            save(c, File(out, "${t.id}_closed.png"))
            c.debugPose(0, 0.62f, 0.45f, openF = 0.55f, label = false)
            save(c, File(out, "${t.id}_morph.png"))
        }
    }

    /** Frames of each icon animation, for the preview sheet. */
    @Test fun renderIconAnimations() {
        val out = File(System.getProperty("previewDir") ?: "build/previews").apply { mkdirs() }
        setTheme("classic")
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.ringerMode = AudioManager.RINGER_MODE_NORMAL
        val base = 1_000_000L
        val shots = listOf(
            Triple(1, IconRole.RING, 0), Triple(2, IconRole.DND, 1), Triple(1, IconRole.VIBRATE, 0), Triple(3, IconRole.TORCH_ON, 0),
        )
        for ((p, role, variant) in shots) {
            if (role == IconRole.VIBRATE) am.ringerMode = AudioManager.RINGER_MODE_VIBRATE
            if (role == IconRole.DND) { }
            view.debugPose(p, 0.62f, 0.45f, label = false)
            if (role == IconRole.TORCH_ON) view.debugSel(6, 1f)
            if (role == IconRole.DND) view.debugSel(3, 1f)
            view.debugSetClock(base)
            view.debugKick(role, variant)
            for ((i, ms) in listOf(40L, 90L, 160L, 300L).withIndex()) {
                view.debugSetClock(base + ms)
                save(view, File(out, "anim_${role}_$i.png"))
            }
            view.debugSetClock(-1)
            am.ringerMode = AudioManager.RINGER_MODE_NORMAL
        }
        // speaker waves and sun rays at three levels
        for ((i, lv) in listOf(0.08f, 0.45f, 0.95f).withIndex()) {
            view.debugPose(0, lv, lv, label = false)
            save(view, File(out, "levels_$i.png"))
        }
    }

    @Test fun renderSetupScreen() {
        val out = File(System.getProperty("previewDir") ?: "build/previews").apply { mkdirs() }
        Prefs.of(ctx).edit().putString(Prefs.KEY_THEME, "neon").commit()
        val a = org.robolectric.Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = a.window.decorView
        val w = root.width.takeIf { it > 0 } ?: 1080
        val sv = (a.findViewById<android.view.ViewGroup>(android.R.id.content)).getChildAt(0) as android.view.ViewGroup
        val content = sv.getChildAt(0)
        content.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        content.layout(0, 0, content.measuredWidth, content.measuredHeight)
        val bmp = Bitmap.createBitmap(content.measuredWidth, content.measuredHeight, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFF0E0E10.toInt())
        content.draw(c)
        FileOutputStream(File(out, "setup.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun save(v: View, f: File) {
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
