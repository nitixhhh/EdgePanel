package bot.tab.edgepanel

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.shadows.ShadowWindowManagerImpl
import java.time.Duration

/** The service side of bug 2: two fixed windows, never resized or moved while opening or closing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EdgePanelServiceTest {

    private fun frames(ms: Long) { var t = 0L; while (t < ms) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(8)); t += 8 } }

    private fun snap(v: View): List<Int> { val p = v.layoutParams as WindowManager.LayoutParams; return listOf(p.width, p.height, p.x, p.y, p.gravity) }

    @Test fun openAndCloseNeverResizeOrMoveAWindow() {
        try {
            val f = Class.forName("androidx.dynamicanimation.animation.AnimationHandler").getDeclaredField("sAnimatorHandler")
            f.isAccessible = true
            (f.get(null) as ThreadLocal<*>).remove()
        } catch (_: Exception) { }
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        Prefs.of(ctx).edit().clear().commit()
        ShadowSettings.setCanDrawOverlays(true)
        val svc = Robolectric.buildService(EdgePanelService::class.java).create().startCommand(0, 1).get()
        val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val views = Shadow.extract<ShadowWindowManagerImpl>(wm).views
        val panel = views.filterIsInstance<EdgePanelView>().firstOrNull()
        val handle = views.filterIsInstance<HandleView>().firstOrNull()
        assertNotNull(panel); assertNotNull(handle)
        panel!!; handle!!
        assertEquals("panel window above the handle window", views.indexOf(handle) + 1, views.indexOf(panel))
        assertEquals(View.INVISIBLE, panel.visibility)      // closed: invisible, so it takes no touches
        val p0 = snap(panel)
        val h0 = snap(handle)
        // same centre line
        assertEquals(p0[3] + p0[1] / 2, h0[3] + h0[1] / 2)

        // lay the views out at their window sizes (Robolectric doesn't run window layout for overlays)
        for (v in listOf(panel, handle)) {
            val lp = v.layoutParams as WindowManager.LayoutParams
            v.measure(View.MeasureSpec.makeMeasureSpec(lp.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(lp.height, View.MeasureSpec.EXACTLY))
            v.layout(0, 0, lp.width, lp.height)
        }
        val d = ctx.resources.displayMetrics.density
        val down = SystemClock.uptimeMillis()
        val x0 = handle.width - 4 * d
        val y0 = handle.height / 2f
        handle.dispatchTouchEvent(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x0, y0, 0))
        for (i in 1..10) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(20))
            handle.dispatchTouchEvent(MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x0 - 7 * d * i, y0, 0))
            assertEquals(p0, snap(panel)); assertEquals(h0, snap(handle))
        }
        handle.dispatchTouchEvent(MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x0 - 70 * d, y0, 0))
        frames(800)
        assertEquals(View.VISIBLE, panel.visibility)
        assertEquals(1f, panel.debugOpen, 0.02f)
        assertEquals(p0, snap(panel)); assertEquals(h0, snap(handle))

        panel.dispatchTouchEvent(MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_OUTSIDE, 1f, 1f, 0))
        var t = 0
        while (t < 900) {
            frames(8); t += 8
            assertEquals("panel window untouched mid-close", p0, snap(panel))
            assertEquals(h0, snap(handle))
        }
        assertEquals(0f, panel.debugOpen, 0.0001f)
        assertEquals(View.INVISIBLE, panel.visibility)
        assertTrue(handle.shown)
        svc.onDestroy()
    }
}
