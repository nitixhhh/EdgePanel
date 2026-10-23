package bot.tab.edgepanel

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Sounds (SoundPool, tiny synthesized OGGs in res/raw) and haptics (VibrationEffect.Composition
 * primitives where the phone supports them, predefined effects or short one-shots as fallbacks).
 * Both follow the Sound / Vibration switches in the setup screen.
 */
class Feedback(private val ctx: Context) {

    enum class Sfx(val res: Int, val vol: Float) {
        TICK(R.raw.snd_tick, 0.55f), CLICK(R.raw.snd_click, 0.6f), DETENT(R.raw.snd_detent, 0.6f), SNAP(R.raw.snd_snap, 0.55f),
        RING(R.raw.snd_ring, 0.7f), VIBRATE(R.raw.snd_vibrate, 0.7f), SILENT(R.raw.snd_silent, 0.65f),
        DND_ON(R.raw.snd_dnd_on, 0.65f), DND_OFF(R.raw.snd_dnd_off, 0.65f), POP(R.raw.snd_pop, 0.6f),
        TOGGLE_ON(R.raw.snd_toggle_on, 0.6f), TOGGLE_OFF(R.raw.snd_toggle_off, 0.6f), SHUTTER(R.raw.snd_shutter, 0.7f),
        OPEN(R.raw.snd_open, 0.5f),
    }

    enum class Buzz { TICK, LOW_TICK, CLICK, SNAP, RING, VIBRATE, SILENT, DND, POP, HEAVY }

    private var pool: SoundPool? = null
    private val ids = IntArray(Sfx.values().size)
    private val loaded = BooleanArray(Sfx.values().size)
    private var lastPlay = LongArray(Sfx.values().size)

    private val vibrator: Vibrator? = try {
        if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    } catch (_: Exception) { null }

    private val effects = arrayOfNulls<VibrationEffect>(Buzz.values().size)
    /** Which route each haptic took (composition / predefined / one-shot); for the tests and the report. */
    val route = arrayOfNulls<String>(Buzz.values().size)

    var soundOn = Prefs.soundOn(ctx)
    var hapticOn = Prefs.hapticOn(ctx)

    init { buildEffects(); if (soundOn) ensurePool() }

    fun reloadPrefs() { soundOn = Prefs.soundOn(ctx); hapticOn = Prefs.hapticOn(ctx); if (soundOn) ensurePool() }

    private fun ensurePool() {
        if (pool != null) return
        val p = try {
            SoundPool.Builder().setMaxStreams(4).setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            ).build()
        } catch (_: Exception) { return }
        p.setOnLoadCompleteListener { _, sampleId, status ->
            val i = ids.indexOf(sampleId)
            if (i >= 0 && status == 0) loaded[i] = true
        }
        for (s in Sfx.values()) ids[s.ordinal] = try { p.load(ctx, s.res, 1) } catch (_: Exception) { 0 }
        pool = p
    }

    fun play(s: Sfx) {
        if (!soundOn) return
        ensurePool()
        val p = pool ?: return
        if (!loaded[s.ordinal]) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastPlay[s.ordinal] < 35) return          // a burst of ticks becomes one
        lastPlay[s.ordinal] = now
        p.play(ids[s.ordinal], s.vol, s.vol, 1, 0, 1f)
    }

    @android.annotation.SuppressLint("WrongConstant") // the values are Composition.PRIMITIVE_* passed through a vararg
    private fun primitivesOk(vararg prims: Int): Boolean {
        val v = vibrator ?: return false
        if (Build.VERSION.SDK_INT < 30) return false
        return try { v.areAllPrimitivesSupported(*prims) } catch (_: Exception) { false }
    }

    private fun buildEffects() {
        val v = vibrator
        if (v == null || !v.hasVibrator()) return
        for (b in Buzz.values()) {
            var e: VibrationEffect? = null
            if (Build.VERSION.SDK_INT >= 31) e = composition(b)
            if (e != null) route[b.ordinal] = "composition"
            if (e == null && Build.VERSION.SDK_INT >= 29) {
                e = predefined(b); if (e != null) route[b.ordinal] = "predefined"
            }
            if (e == null) {
                e = oneShot(b); route[b.ordinal] = "oneshot"
            }
            effects[b.ordinal] = e
        }
    }

    @androidx.annotation.RequiresApi(31)
    private fun composition(b: Buzz): VibrationEffect? {
        val click = VibrationEffect.Composition.PRIMITIVE_CLICK
        val tick = VibrationEffect.Composition.PRIMITIVE_TICK
        val low = VibrationEffect.Composition.PRIMITIVE_LOW_TICK
        val thud = VibrationEffect.Composition.PRIMITIVE_THUD
        val rise = VibrationEffect.Composition.PRIMITIVE_QUICK_RISE
        val fall = VibrationEffect.Composition.PRIMITIVE_QUICK_FALL
        return try {
            when (b) {
                Buzz.TICK -> if (primitivesOk(tick)) VibrationEffect.startComposition().addPrimitive(tick, 0.7f).compose() else null
                Buzz.LOW_TICK -> if (primitivesOk(low)) VibrationEffect.startComposition().addPrimitive(low, 0.9f).compose()
                    else if (primitivesOk(tick)) VibrationEffect.startComposition().addPrimitive(tick, 0.45f).compose() else null
                Buzz.CLICK -> if (primitivesOk(click)) VibrationEffect.startComposition().addPrimitive(click, 0.6f).compose() else null
                Buzz.SNAP -> if (primitivesOk(click, tick)) VibrationEffect.startComposition()
                    .addPrimitive(click, 0.5f).addPrimitive(tick, 0.35f, 40).compose() else null
                Buzz.RING -> if (primitivesOk(click, rise)) VibrationEffect.startComposition()
                    .addPrimitive(rise, 0.5f).addPrimitive(click, 0.9f, 20).compose()
                    else if (primitivesOk(click)) VibrationEffect.startComposition().addPrimitive(click, 0.9f).addPrimitive(click, 0.6f, 90).compose() else null
                Buzz.VIBRATE -> if (primitivesOk(tick)) VibrationEffect.startComposition()
                    .addPrimitive(tick, 1f).addPrimitive(tick, 1f, 50).addPrimitive(tick, 1f, 50).addPrimitive(tick, 0.8f, 50).compose() else null
                Buzz.SILENT -> if (primitivesOk(thud)) VibrationEffect.startComposition().addPrimitive(thud, 0.6f).compose()
                    else if (primitivesOk(fall)) VibrationEffect.startComposition().addPrimitive(fall, 0.7f).compose() else null
                Buzz.DND -> if (primitivesOk(fall, low)) VibrationEffect.startComposition()
                    .addPrimitive(fall, 0.6f).addPrimitive(low, 0.8f, 30).compose()
                    else if (primitivesOk(click)) VibrationEffect.startComposition().addPrimitive(click, 0.5f).compose() else null
                Buzz.POP -> if (primitivesOk(click, tick)) VibrationEffect.startComposition()
                    .addPrimitive(tick, 0.5f).addPrimitive(click, 0.8f, 30).compose() else null
                Buzz.HEAVY -> if (primitivesOk(click)) VibrationEffect.startComposition().addPrimitive(click, 1f).compose() else null
            }
        } catch (_: Exception) { null }
    }

    @androidx.annotation.RequiresApi(29)
    private fun predefined(b: Buzz): VibrationEffect? = try {
        VibrationEffect.createPredefined(when (b) {
            Buzz.TICK, Buzz.LOW_TICK -> VibrationEffect.EFFECT_TICK
            Buzz.CLICK, Buzz.SNAP, Buzz.POP -> VibrationEffect.EFFECT_CLICK
            Buzz.RING, Buzz.DND -> VibrationEffect.EFFECT_DOUBLE_CLICK
            Buzz.VIBRATE, Buzz.SILENT, Buzz.HEAVY -> VibrationEffect.EFFECT_HEAVY_CLICK
        })
    } catch (_: Exception) { null }

    private fun oneShot(b: Buzz): VibrationEffect? = try {
        val amp = if (vibrator?.hasAmplitudeControl() == true) when (b) {
            Buzz.TICK, Buzz.LOW_TICK -> 40; Buzz.CLICK, Buzz.SNAP, Buzz.POP -> 90; else -> 160
        } else VibrationEffect.DEFAULT_AMPLITUDE
        when (b) {
            Buzz.VIBRATE -> VibrationEffect.createWaveform(longArrayOf(0, 30, 45, 30, 45, 30), -1)
            Buzz.RING, Buzz.DND -> VibrationEffect.createWaveform(longArrayOf(0, 14, 70, 14), -1)
            Buzz.TICK, Buzz.LOW_TICK -> VibrationEffect.createOneShot(8, amp)
            else -> VibrationEffect.createOneShot(14, amp)
        }
    } catch (_: Exception) { null }

    fun buzz(b: Buzz) {
        if (!hapticOn) return
        val v = vibrator ?: return
        val e = effects[b.ordinal] ?: return
        try {
            if (Build.VERSION.SDK_INT >= 33) v.vibrate(e, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
            else v.vibrate(e)
        } catch (_: Exception) { }
    }

    /** Sound + haptic together. */
    fun fx(s: Sfx?, b: Buzz?) { if (b != null) buzz(b); if (s != null) play(s) }

    fun release() { try { pool?.release() } catch (_: Exception) { }; pool = null; loaded.fill(false) }
}
