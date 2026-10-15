package com.jianyu.ds_pet.pet

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
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.webkit.WebView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import com.jianyu.ds_pet.BuildConfig
import com.jianyu.ds_pet.MainActivity
import com.jianyu.ds_pet.R

class PetOverlayService : Service() {
    lateinit var windowContext: Context
        private set
    lateinit var settings: PetSettings
        private set
    lateinit var routes: PetRoutes
        private set
    lateinit var assetLoader: WebViewAssetLoader
        private set
    val broker = CollisionBroker { windows }

    private lateinit var wm: WindowManager
    private val windows = mutableListOf<PetWindow>()
    private var paused = false
    private var failed = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = setPaused(i.action == Intent.ACTION_SCREEN_OFF)
    }

    override fun onCreate() {
        super.onCreate()
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        val pkg = WebView.getCurrentWebViewPackage()
        Log.i(TAG, "WebView ${pkg?.packageName} ${pkg?.versionName}")
        val display = checkNotNull(
            getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY),
        )
        windowContext = createDisplayContext(display)
            .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        wm = windowContext.getSystemService(WindowManager::class.java)
        settings = PetSettings(this)
        routes = PetRoutes(this, settings, PetStateStore())
        assetLoader = WebViewAssetLoader.Builder()
            .setDomain("appassets.androidplatform.net")
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        startInForeground()
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        createWindows()
        if (!failed) {
            isRunning = true
            publishState()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (failed) return START_NOT_STICKY
        when (intent?.action) {
            ACTION_TOGGLE -> setPaused(!paused)
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> if (windows.isNotEmpty()) reloadAll()
            else -> if (windows.isEmpty()) createWindows()
        }
        return START_STICKY
    }

    private fun createWindows() {
        try {
            val geo = DeskGeometry.of(windowContext)
            repeat(settings.petCount.coerceIn(1, 4)) { i ->
                val id = if (i == 0) "main" else "main$i"
                windows += PetWindow(this, wm, i, id).also { it.attach(geo) }
            }
            if (paused) windows.forEach { it.setPaused(true) }
        } catch (e: Exception) {
            Log.e(TAG, "无法添加悬浮窗", e)
            windows.forEach { runCatching { it.detach() } }
            windows.clear()
            failed = true
            stopSelf()
        }
    }

    fun reloadAll() {
        windows.forEach { it.detach() }
        windows.clear()
        createWindows()
    }

    private fun setPaused(p: Boolean) {
        paused = p
        isPaused = p
        windows.forEach { it.setPaused(p) }
        updateNotification()
        publishState()
        Log.i(TAG, if (p) "paused" else "resumed")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (windows.isEmpty()) return
        try {
            val geo = DeskGeometry.of(windowContext).toJson()
            windows.forEach { it.emit("displays", geo) }
        } catch (e: Exception) {
            Log.e(TAG, "更新桌面几何失败", e)
        }
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "桌宠", NotificationManager.IMPORTANCE_LOW))
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        fun pending(action: String, code: Int) = PendingIntent.getService(
            this,
            code,
            Intent(this, PetOverlayService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val content = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = if (paused) "桌宠已暂停" else "桌宠正在屏幕上"
        val text = if (paused) "点「暂停/继续」恢复动画" else "可轻点、拖动，长按打开菜单"
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_pet)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(content)
            .setOngoing(true)
            .addAction(0, "暂停/继续", pending(ACTION_TOGGLE, 1))
            .addAction(0, "退出", pending(ACTION_STOP, 2))
            .build()
    }

    private fun publishState() {
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName))
    }

    override fun onDestroy() {
        isRunning = false
        isPaused = false
        publishState()
        runCatching { unregisterReceiver(screenReceiver) }
        windows.forEach { runCatching { it.detach() } }
        windows.clear()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "dsh-pet"
        private const val CHANNEL = "pet"
        private const val NOTIFICATION_ID = 7340
        const val ACTION_TOGGLE = "com.jianyu.ds_pet.TOGGLE"
        const val ACTION_STOP = "com.jianyu.ds_pet.STOP"
        const val ACTION_RELOAD = "com.jianyu.ds_pet.RELOAD"
        const val ACTION_STATE = "com.jianyu.ds_pet.STATE"

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var isPaused: Boolean = false
            private set
    }
}
