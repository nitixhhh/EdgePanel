package bot.tab.edgepanel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager

class EdgePanelService : Service() {

    companion object {
        const val ACTION_STOP = "bot.tab.edgepanel.STOP"
        const val ACTION_RELOAD = "bot.tab.edgepanel.RELOAD"
        private const val CHANNEL = "edge_panel"
        private const val NOTIF_ID = 7

        @Volatile var isRunning = false
            private set

        fun start(c: Context, reload: Boolean = false) {
            val i = Intent(c, EdgePanelService::class.java)
            if (reload) i.action = ACTION_RELOAD
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, EdgePanelService::class.java))
        }
    }

    private lateinit var wm: WindowManager
    private var panel: EdgePanelView? = null
    private var handle: HandleView? = null
    private var panelLp: WindowManager.LayoutParams? = null
    private var handleLp: WindowManager.LayoutParams? = null
    private lateinit var sound: SoundControl
    private lateinit var bright: BrightnessControl
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) panel?.onBattery(intent) else panel?.refreshState()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        sound = SoundControl(this)
        bright = BrightnessControl(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        if (intent?.action == ACTION_STOP) {
            Prefs.of(this).edit().putBoolean(Prefs.KEY_RUNNING, false).apply()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        Prefs.of(this).edit().putBoolean(Prefs.KEY_RUNNING, true).apply()
        if (panel == null) {
            addPanel()
        } else if (intent?.action == ACTION_RELOAD) {
            removePanel(); addPanel()
        }
        isRunning = true
        return START_STICKY
    }

    private fun goForeground() {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, EdgePanelService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Edge Panel chal raha hai")
            .setContentText("Screen ke edge se swipe karo")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL, "Edge Panel", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Keeps the edge panel available"
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    /**
     * Two overlay windows, both a fixed size for their whole life (v3; see PanelHost):
     *  - the handle window: a thin touch strip on the edge (30dp x ~120dp), always shown;
     *  - the panel window: the full panel area, above the handle, INVISIBLE (no touches, nothing drawn) until opened.
     * Both are centred on the same line, so the closed shape is pixel-identical in either window.
     */
    @android.annotation.SuppressLint("RtlHardcoded") // the panel sits on a physical screen edge
    private fun addPanel() {
        val view = EdgePanelView(this, sound, bright, Prefs.sideRight(this))
        val h = HandleView(this, view)
        val base = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        val gravity = Gravity.TOP or (if (view.sideRight) Gravity.RIGHT else Gravity.LEFT)
        val hl = WindowManager.LayoutParams(
            view.handleWindowW, view.handleWindowH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, base, PixelFormat.TRANSLUCENT
        ).apply { this.gravity = gravity; x = 0; title = "EdgePanelHandle" }
        val pl = WindowManager.LayoutParams(
            view.expandedWindowW, view.expandedWindowH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            base or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH, PixelFormat.TRANSLUCENT
        ).apply { this.gravity = gravity; x = 0; title = "EdgePanel" }
        if (Build.VERSION.SDK_INT >= 28) {
            hl.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            pl.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        positionWindows(hl, pl)
        view.visibility = View.INVISIBLE
        view.host = object : PanelHost {
            override fun showPanelWindow() { if (view.visibility != View.VISIBLE) view.visibility = View.VISIBLE }
            override fun hidePanelWindow() { if (view.visibility != View.INVISIBLE) view.visibility = View.INVISIBLE }
            override fun setHandleShown(shown: Boolean) { h.shown = shown }
            override fun afterClose(delayMs: Long, action: () -> Unit) { main.postDelayed(action, delayMs) }
        }
        handleLp = hl; panelLp = pl; panel = view; handle = h
        wm.addView(h, hl)
        wm.addView(view, pl)          // added second, so it sits above the handle window

        val f = IntentFilter().apply {
            addAction("android.media.VOLUME_CHANGED_ACTION")
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(android.net.wifi.WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(stateReceiver, f, Context.RECEIVER_EXPORTED)
        else registerReceiver(stateReceiver, f)
    }

    /** One shared centre line: y = centre - height/2 for both (even heights, so no rounding drift). */
    private fun positionWindows(hl: WindowManager.LayoutParams, pl: WindowManager.LayoutParams) {
        val screenH = resources.displayMetrics.heightPixels
        val center = (screenH * Prefs.position(this) / 100f).toInt().coerceIn(0, screenH)
        hl.y = center - hl.height / 2
        pl.y = center - pl.height / 2
    }

    private fun removePanel() {
        panel?.let {
            try { unregisterReceiver(stateReceiver) } catch (_: Exception) { }
            try { wm.removeViewImmediate(it) } catch (_: Exception) { }
            it.release()
        }
        handle?.let { try { wm.removeViewImmediate(it) } catch (_: Exception) { } }
        panel = null
        handle = null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val v = panel ?: return
        val h = handle ?: return
        val hl = handleLp ?: return
        val pl = panelLp ?: return
        v.collapseNow()
        positionWindows(hl, pl)
        try { wm.updateViewLayout(h, hl) } catch (_: Exception) { }
        try { wm.updateViewLayout(v, pl) } catch (_: Exception) { }
    }

    override fun onDestroy() {
        removePanel()
        isRunning = false
        super.onDestroy()
    }
}
