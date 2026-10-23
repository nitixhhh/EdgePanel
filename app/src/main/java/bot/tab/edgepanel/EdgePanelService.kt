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
    private var params: WindowManager.LayoutParams? = null
    private lateinit var sound: SoundControl
    private lateinit var bright: BrightnessControl

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { panel?.refreshState() }
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

    @android.annotation.SuppressLint("RtlHardcoded") // the panel sits on a physical screen edge
    private fun addPanel() {
        val view = EdgePanelView(this, sound, bright, Prefs.sideRight(this))
        val lp = WindowManager.LayoutParams(
            view.collapsedWindowW, view.collapsedWindowH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or (if (view.sideRight) Gravity.RIGHT else Gravity.LEFT)
        lp.x = 0
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        lp.title = "EdgePanel"
        params = lp
        view.windowSizer = { wPx, hPx -> resizeWindow(wPx, hPx) }
        panel = view
        positionWindow(lp, lp.height)
        wm.addView(view, lp)

        val f = IntentFilter().apply {
            addAction("android.media.VOLUME_CHANGED_ACTION")
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(stateReceiver, f, Context.RECEIVER_EXPORTED)
        else registerReceiver(stateReceiver, f)
    }

    private fun positionWindow(lp: WindowManager.LayoutParams, h: Int) {
        val screenH = resources.displayMetrics.heightPixels
        val center = (screenH * Prefs.position(this) / 100f).toInt()
        lp.y = (center - h / 2).coerceIn(-h / 2, screenH - h / 2)
    }

    private fun resizeWindow(wPx: Int, hPx: Int) {
        val v = panel ?: return
        val lp = params ?: return
        if (lp.width == wPx && lp.height == hPx) return
        lp.width = wPx
        lp.height = hPx
        positionWindow(lp, hPx)
        try { wm.updateViewLayout(v, lp) } catch (_: Exception) { }
    }

    private fun removePanel() {
        panel?.let {
            try { unregisterReceiver(stateReceiver) } catch (_: Exception) { }
            try { wm.removeViewImmediate(it) } catch (_: Exception) { }
        }
        panel = null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val v = panel ?: return
        val lp = params ?: return
        v.collapseNow()
        positionWindow(lp, lp.height)
        try { wm.updateViewLayout(v, lp) } catch (_: Exception) { }
    }

    override fun onDestroy() {
        removePanel()
        isRunning = false
        super.onDestroy()
    }
}
