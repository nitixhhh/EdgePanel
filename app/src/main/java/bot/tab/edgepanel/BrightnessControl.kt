package bot.tab.edgepanel

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import android.provider.Settings
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Screen brightness through Settings.System.SCREEN_BRIGHTNESS (needs "Modify system settings").
 * The slider is perceptual: slider position p maps to raw = min + (max - min) * p^2.2, because the eye
 * sees brightness roughly logarithmically and Android's raw value is close to linear light.
 */
class BrightnessControl(private val ctx: Context) {
    private val cr = ctx.contentResolver
    private val gamma = 2.2f

    val canWrite: Boolean get() = Settings.System.canWrite(ctx)

    @android.annotation.SuppressLint("DiscouragedApi") // the brightness range is only exposed as a framework config value
    private fun sysInt(name: String, fallback: Int): Int {
        val id = Resources.getSystem().getIdentifier(name, "integer", "android")
        return if (id != 0) try { Resources.getSystem().getInteger(id) } catch (_: Exception) { fallback } else fallback
    }

    private val minRaw: Int = sysInt("config_screenBrightnessSettingMinimum", 1).coerceIn(0, 50)
    private var maxRaw: Int = max(sysInt("config_screenBrightnessSettingMaximum", 255), 255)

    val raw: Int get() = try { Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS) } catch (_: Exception) { 128 }

    val isAuto: Boolean get() = try {
        Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
    } catch (_: Exception) { false }

    /** Slider position 0..1 for the current brightness. */
    val fraction: Float get() {
        val r = raw
        if (r > maxRaw) maxRaw = r        // some phones use a 0..2047 or 0..4095 range
        val lin = ((r - minRaw).toFloat() / (maxRaw - minRaw).coerceAtLeast(1)).coerceIn(0f, 1f)
        return lin.pow(1f / gamma)
    }

    /** Sets brightness from a slider position; turns auto-brightness off first, the way iOS does on a manual drag. */
    fun setFraction(p: Float): Boolean {
        if (!canWrite) return false
        return try {
            if (isAuto) Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            val v = minRaw + ((maxRaw - minRaw) * p.coerceIn(0f, 1f).pow(gamma)).roundToInt()
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, v.coerceIn(minRaw, maxRaw))
            true
        } catch (_: Exception) { false }
    }

    fun openSettings() {
        val i = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + ctx.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(i) } catch (_: Exception) { }
    }
}
