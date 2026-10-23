package bot.tab.edgepanel

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.Settings

/** All the real system changes live here: media volume, ringer mode, DND and the Work/Personal presets. */
class SoundControl(private val ctx: Context) {
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    val maxMedia: Int get() = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    val media: Int get() = audio.getStreamVolume(AudioManager.STREAM_MUSIC)

    fun setMedia(v: Int) {
        try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, v.coerceIn(0, maxMedia), 0)
        } catch (_: SecurityException) { }
    }

    val ringerMode: Int get() = audio.ringerMode

    fun hasDndAccess(): Boolean = nm.isNotificationPolicyAccessGranted

    /** Returns false when Android refused (silent mode needs DND access on most phones). */
    fun setRinger(mode: Int): Boolean {
        if (mode == AudioManager.RINGER_MODE_SILENT && !hasDndAccess()) return false
        return try {
            audio.ringerMode = mode
            true
        } catch (_: SecurityException) { false }
    }

    val dndOn: Boolean get() = nm.currentInterruptionFilter.let {
        it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }

    fun setDnd(on: Boolean): Boolean {
        if (!hasDndAccess()) return false
        return try {
            nm.setInterruptionFilter(
                if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL
            )
            true
        } catch (_: SecurityException) { false }
    }

    var mode: String
        get() = Prefs.of(ctx).getString(Prefs.KEY_MODE, "") ?: ""
        set(v) { Prefs.of(ctx).edit().putString(Prefs.KEY_MODE, v).apply() }

    /** Work preset: vibrate + DND priority-only + media volume down to ~30%. Returns false if DND access is missing. */
    fun applyWork(): Boolean {
        if (mode != "work") Prefs.of(ctx).edit().putInt(Prefs.KEY_SAVED_MEDIA, media).apply()
        setRinger(AudioManager.RINGER_MODE_VIBRATE)
        setMedia((maxMedia * 0.3f).toInt())
        val ok = setDnd(true)
        mode = "work"
        return ok
    }

    /** Personal preset: ring + DND off + media volume back to what it was before Work. */
    fun applyPersonal(): Boolean {
        val ok = setDnd(false)
        setRinger(AudioManager.RINGER_MODE_NORMAL)
        val saved = Prefs.of(ctx).getInt(Prefs.KEY_SAVED_MEDIA, -1)
        setMedia(if (saved >= 0) saved else (maxMedia * 0.6f).toInt())
        mode = "personal"
        return ok
    }

    fun openDndSettings() {
        val i = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(i) } catch (_: Exception) { }
    }
}
