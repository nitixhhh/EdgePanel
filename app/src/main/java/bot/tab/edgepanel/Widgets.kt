package bot.tab.edgepanel

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import java.lang.ref.WeakReference

private fun Context.launch(i: Intent): Boolean = try {
    startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
} catch (_: Exception) { false }

/** Flashlight through CameraManager.setTorchMode (no camera permission needed for the torch). */
class TorchControl(ctx: Context) {
    private val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    private val camId: String? = try {
        cm?.cameraIdList?.firstOrNull { id ->
            val ch = cm.getCameraCharacteristics(id)
            ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: cm?.cameraIdList?.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
    } catch (_: Exception) { null }
    var on = false
        private set
    var onChange: (() -> Unit)? = null
    val available get() = camId != null
    private val cb = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == camId) { on = enabled; onChange?.invoke() }
        }
    }
    init { try { cm?.registerTorchCallback(cb, Handler(Looper.getMainLooper())) } catch (_: Exception) { } }

    /** Returns false when the torch is unavailable (no flash, or the camera is busy in another app). */
    fun toggle(): Boolean {
        val id = camId ?: return false
        return try { cm?.setTorchMode(id, !on); on = !on; true } catch (_: Exception) { false }
    }
    fun release() { try { cm?.unregisterTorchCallback(cb) } catch (_: Exception) { } }
}

/** Music keys through AudioManager, plus the track title when "Notification access" is allowed (optional). */
class MediaControl(private val ctx: Context) {
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val listener = ComponentName(ctx, MediaListener::class.java)

    val hasTitleAccess: Boolean get() = try {
        if (Build.VERSION.SDK_INT >= 27) (ctx.getSystemService(NotificationManager::class.java)).isNotificationListenerAccessGranted(listener)
        else (Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: "").contains(listener.flattenToString())
    } catch (_: Exception) { false }

    private fun controller(): MediaController? {
        if (!hasTitleAccess) return null
        return try {
            val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val list = msm.getActiveSessions(listener)
            list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
        } catch (_: Exception) { null }
    }

    val playing: Boolean get() {
        val c = controller()
        return c?.playbackState?.state?.let { it == PlaybackState.STATE_PLAYING || it == PlaybackState.STATE_BUFFERING } ?: audio.isMusicActive
    }

    /** "Title · Artist", or null when not known. */
    val title: String? get() {
        val md = controller()?.metadata ?: return null
        val t = md.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty()
        val a = md.getString(MediaMetadata.METADATA_KEY_ARTIST)?.trim().orEmpty()
        return when { t.isEmpty() -> null; a.isEmpty() -> t; else -> "$t · $a" }
    }

    private fun key(code: Int) {
        val now = SystemClock.uptimeMillis()
        try {
            audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
            audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
        } catch (_: Exception) { }
    }

    fun playPause() {
        val c = controller()
        if (c != null) {
            if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
        } else key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
    }
    fun next() { controller()?.transportControls?.skipToNext() ?: key(KeyEvent.KEYCODE_MEDIA_NEXT) }
    fun prev() { controller()?.transportControls?.skipToPrevious() ?: key(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }

    fun openListenerSettings() {
        ctx.launch(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
    }
}

/** Only exists so the media sessions (track title) are readable when the user allows Notification access. */
class MediaListener : NotificationListenerService()

/** Battery level and charging state from the sticky ACTION_BATTERY_CHANGED broadcast. */
class BatteryInfo(private val ctx: Context) {
    var level = 1f; private set
    var charging = false; private set
    var percent = 100; private set
    fun read(i: Intent? = null) {
        val it = i ?: try { ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) } catch (_: Exception) { null } ?: return
        val l = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val s = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        if (l >= 0) { level = (l / s.toFloat()).coerceIn(0f, 1f); percent = (level * 100).toInt() }
        val st = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
    }
    /** For previews and tests. */
    fun set(levelF: Float, chg: Boolean) { level = levelF; percent = (levelF * 100).toInt(); charging = chg }
}

/** Wi-Fi and Bluetooth shortcuts. Apps can't switch Wi-Fi on Android 10+, so these open the system panels. */
class Connectivity(private val ctx: Context) {
    val wifiOn: Boolean get() = try {
        (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled
    } catch (_: Exception) { false }

    // reading the adapter state; on Android 12+ it may throw, and we show it as off/unknown
    val btOn: Boolean @SuppressLint("MissingPermission") get() = try {
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.isEnabled == true
    } catch (_: Exception) { false }

    fun openWifi() {
        if (Build.VERSION.SDK_INT >= 29 && ctx.launch(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))) return
        ctx.launch(Intent(Settings.ACTION_WIFI_SETTINGS))
    }
    fun openBluetooth() { if (!ctx.launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))) ctx.launch(Intent(Settings.ACTION_SETTINGS)) }
}

/** Camera, calculator and up to two apps the user picks in the setup screen. */
class AppSlots(private val ctx: Context) {
    private val pm = ctx.packageManager
    class Slot(val kind: Int, val label: String, val pkg: String?, val icon: Bitmap?)

    companion object {
        const val CAMERA = 0
        const val CALC = 1
        const val USER = 2
        val CALC_PACKAGES = arrayOf(
            "com.google.android.calculator", "com.sec.android.app.popupcalculator", "com.miui.calculator",
            "com.coloros.calculator", "com.oneplus.calculator", "com.android.bbkcalculator", "com.vivo.calculator",
            "com.realme.calculator", "com.oplus.calculator", "com.huawei.calculator", "com.asus.calculator",
            "com.motorola.calculator", "com.android.calculator2", "com.transsion.calculator", "com.infinix.calculator",
        )
    }

    fun slots(iconPx: Int): List<Slot> {
        val out = ArrayList<Slot>()
        out.add(Slot(CAMERA, "CAMERA", null, null))
        out.add(Slot(CALC, "CALCULATOR", null, null))
        for (s in 0..1) {
            val p = Prefs.app(ctx, s) ?: continue
            try {
                val ai = pm.getApplicationInfo(p, 0)
                val label = pm.getApplicationLabel(ai).toString()
                out.add(Slot(USER, label.uppercase(), p, iconBitmap(p, iconPx)))
            } catch (_: Exception) { }
        }
        return out
    }

    fun iconBitmap(pkg: String, px: Int): Bitmap? = try {
        val d = pm.getApplicationIcon(pkg)
        val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, px, px); d.draw(Canvas(b)); b
    } catch (_: Exception) { null }

    fun launch(s: Slot): Boolean = when (s.kind) {
        CAMERA -> ctx.launch(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)) || ctx.launch(Intent(MediaStore.ACTION_IMAGE_CAPTURE))
        CALC -> openCalculator()
        else -> s.pkg?.let { p -> pm.getLaunchIntentForPackage(p)?.let { ctx.launch(it) } } ?: false
    }

    private fun openCalculator(): Boolean {
        if (ctx.launch(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR))) return true
        for (p in CALC_PACKAGES) pm.getLaunchIntentForPackage(p)?.let { if (ctx.launch(it)) return true }
        // Last resort: any launcher app whose name says calculator.
        return try {
            val q = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.MATCH_ALL)
            val hit = q.firstOrNull { it.loadLabel(pm).toString().contains("calc", ignoreCase = true) }
            hit?.let { ctx.launch(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClassName(it.activityInfo.packageName, it.activityInfo.name)) } ?: false
        } catch (_: Exception) { false }
    }
}

/**
 * Screenshot: Android only lets an app take a screenshot through an accessibility service
 * (GLOBAL_ACTION_TAKE_SCREENSHOT, Android 9+). This service does nothing else: it reads no screen content.
 */
class ShotService : AccessibilityService() {
    companion object {
        private var ref: WeakReference<ShotService>? = null
        val ready: Boolean get() = ref?.get() != null

        fun isEnabled(c: Context): Boolean {
            if (ready) return true
            val cn = ComponentName(c, ShotService::class.java).flattenToString()
            val s = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return s.split(':').any { it.equals(cn, ignoreCase = true) }
        }

        fun take(): Boolean {
            if (Build.VERSION.SDK_INT < 28) return false
            val s = ref?.get() ?: return false
            return s.performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
        }

        fun openSettings(c: Context) {
            c.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        /** Android 13+ blocks accessibility for sideloaded apps until "Allow restricted settings" in App info. */
        fun openAppInfo(c: Context) {
            c.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + c.packageName)))
        }
    }

    override fun onServiceConnected() { super.onServiceConnected(); ref = WeakReference(this) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { }
    override fun onInterrupt() { }
    override fun onUnbind(intent: Intent?): Boolean { ref = null; return super.onUnbind(intent) }
    override fun onDestroy() { ref = null; super.onDestroy() }
}
