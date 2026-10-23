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

    private fun make(preview: Boolean): EdgePanelView {
        val v = EdgePanelView(ctx, SoundControl(ctx), BrightnessControl(ctx), sideRight = true, preview = preview)
        val w = (360 * ctx.resources.displayMetrics.density).toInt()
        val h = v.expandedWindowH
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        return v
    }

    private fun frames(ms: Long) {
        var t = 0L
        while (t < ms) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(8)); t += 8 }
    }

    private fun ev(action: Int, x: Float, y: Float): MotionEvent =
        MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)

    /** A straight-line gesture of [steps] moves over [ms]. */
    private fun gesture(x0: Float, y0: Float, x1: Float, y1: Float, ms: Long, steps: Int = 12) {
        downTime = SystemClock.uptimeMillis()
        view.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, x0, y0))
        for (i in 1..steps) {
            // Advance the clock only (running the looper here lets animation frames jump the clock).
            ShadowSystemClock.advanceBy(Duration.ofMillis(ms / steps))
            val f = i / steps.toFloat()
            view.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, x0 + (x1 - x0) * f, y0 + (y1 - y0) * f))
            if (System.getProperty("epDebug") != null) println("move $i page=${view.debugPage} vol=${view.debugVol}")
        }
        view.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, x1, y1))
        if (System.getProperty("epDebug") != null) println("up page=${view.debugPage} target=${view.debugPageTarget}")
        frames(1200)
        if (System.getProperty("epDebug") != null) println("settled page=${view.debugPage}")
    }

    private val cx get() = view.width - dp(47f)        // page-0 panel centre (depth 94dp)
    private val cy get() = view.height / 2f
    private val volX get() = cx + dp(20f)

    @Test fun slowSwipeOffTheSlidersChangesPage() {
        assertEquals(0f, view.debugPage, 0.01f)
        gesture(cx, cy - dp(100f), cx, cy - dp(190f), 400)       // top strip, slow
        assertEquals(1f, view.debugPage, 0.01f)
        gesture(view.width - dp(33f), cy, view.width - dp(33f), cy - dp(90f), 350)   // ringer page, slow
        assertEquals(2f, view.debugPage, 0.01f)
        gesture(view.width - dp(33f), cy - dp(40f), view.width - dp(33f), cy + dp(60f), 300)
        assertEquals(1f, view.debugPage, 0.01f)
    }

    @Test fun quickFlickOnAPillChangesPageAndLeavesVolumeAlone() {
        val before = view.debugVol
        gesture(volX, cy + dp(30f), volX, cy - dp(60f), 90, steps = 6)
        assertEquals(1f, view.debugPage, 0.01f)
        assertEquals(before, view.debugVol, 0.001f)
    }

    @Test fun shortFlickAlsoPages() {
        gesture(volX, cy + dp(30f), volX, cy - dp(10f), 60, steps = 3)
        assertEquals(1f, view.debugPage, 0.01f)
    }

    @Test fun slowDragOnVolumePillAdjustsVolumeNotPage() {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val before = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        // press, hold briefly, then drag up 60dp over 600ms
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
        val y = cy + dp(104f) - dp(13f)
        gesture(cx + dp(25f), y, cx + dp(25f), y, 40, steps = 1)
        assertEquals(2f, view.debugPage, 0.01f)
    }

    @Test fun handleOpensFollowingTheFinger() {
        val v = make(preview = false)
        var size: Pair<Int, Int>? = null
        v.windowSizer = { w, h -> size = w to h }
        view = v
        val x0 = v.width - dp(3f)
        gesture(x0, cy, x0 - dp(70f), cy, 220)
        assertTrue(v.debugExpanded)
        assertEquals(1f, v.debugOpen, 0.02f)
        assertEquals(v.expandedWindowW, size?.first)
    }

    /** Renders every theme on every page with the real drawing code, for the preview sheet. */
    @Test fun renderPreviews() {
        val out = File(System.getProperty("previewDir") ?: "build/previews").apply { mkdirs() }
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        for (t in Themes.ALL) {
            Prefs.of(ctx).edit().putString(Prefs.KEY_THEME, t.id).commit()
            view.applyTheme()
            for (p in 0..2) {
                when (p) {
                    1 -> am.ringerMode = AudioManager.RINGER_MODE_SILENT
                    2 -> { am.ringerMode = AudioManager.RINGER_MODE_VIBRATE; Prefs.of(ctx).edit().putString(Prefs.KEY_MODE, "work").commit() }
                }
                view.debugPose(p, 0.62f, 0.45f)
                save(view, File(out, "${t.id}_page$p.png"))
                Prefs.of(ctx).edit().putString(Prefs.KEY_MODE, "").commit()
            }
            // mid-way between page 0 and 1 (finger dragging), and half-open
            view.debugPose(0, 0.62f, 0.45f, label = false)
            view.debugPose(0, 0.62f, 0.45f, openF = 1f, label = false)
            save(view, File(out, "${t.id}_open.png"))
            val c = make(preview = false)
            c.debugPose(0, 0.62f, 0.45f, openF = 0f, label = false)
            save(c, File(out, "${t.id}_closed.png"))
            c.debugPose(0, 0.62f, 0.45f, openF = 0.55f, label = false)
            save(c, File(out, "${t.id}_morph.png"))
        }
    }

    @Test fun renderSetupScreen() {
        val out = File(System.getProperty("previewDir") ?: "build/previews").apply { mkdirs() }
        Prefs.of(ctx).edit().putString(Prefs.KEY_THEME, "neon").commit()
        val a = org.robolectric.Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = a.window.decorView
        val w = root.width.takeIf { it > 0 } ?: 1080
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val sv = (a.findViewById<android.view.ViewGroup>(android.R.id.content)).getChildAt(0) as android.view.ViewGroup
        val content = sv.getChildAt(0)
        content.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        content.layout(0, 0, content.measuredWidth, content.measuredHeight)
        val bmp = Bitmap.createBitmap(content.measuredWidth, content.measuredHeight, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFF111114.toInt())
        content.draw(c)
        FileOutputStream(File(out, "setup.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun save(v: View, f: File) {
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
