package com.jianyu.ds_pet.pet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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
import androidx.webkit.WebViewAssetLoader
import com.jianyu.ds_pet.BuildConfig
import com.jianyu.ds_pet.MainActivity
import com.jianyu.ds_pet.R

class PetOverlayService : Service() {
    lateinit var windowContext: Context
        private set
    lateinit var routes: PetRoutes
        private set
    lateinit var assetLoader: WebViewAssetLoader
        private set

    private lateinit var wm: WindowManager
    private var window: PetWindow? = null
    private var paused = false

    override fun onCreate() {
        super.onCreate()
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        val pkg = WebView.getCurrentWebViewPackage()
        Log.i(TAG, "WebView ${pkg?.packageName} ${pkg?.versionName}")
        Log.i(TAG, "webm ${assets.list("pet/webm")?.joinToString().orEmpty()}")
        val display = checkNotNull(
            getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY),
        )
        windowContext = createDisplayContext(display)
            .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        wm = windowContext.getSystemService(WindowManager::class.java)
        routes = PetRoutes(this, PetSettings(this), PetStateStore())
        assetLoader = WebViewAssetLoader.Builder()
            .setDomain("appassets.androidplatform.net")
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        startInForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> setPaused(!paused)
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> ensureWindow()
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        window?.recenter()
    }

    private fun ensureWindow() {
        if (window != null) return
        try {
            window = PetWindow(this, wm).also { it.attach() }
        } catch (e: RuntimeException) {
            Log.e(TAG, "无法添加悬浮窗", e)
            stopSelf()
        }
    }

    private fun setPaused(p: Boolean) {
        paused = p
        window?.setPaused(p)
        Log.i(TAG, if (p) "paused" else "resumed")
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "桌宠", NotificationManager.IMPORTANCE_LOW))
        fun pending(intent: Intent, code: Int) = PendingIntent.getService(
            this, code, intent, PendingIntent.FLAG_IMMUTABLE,
        )
        val content = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_pet)
            .setContentTitle("透明播放验证")
            .setContentText("悬浮窗正在播放待机动画")
            .setContentIntent(content)
            .setOngoing(true)
            .addAction(0, "暂停/继续", pending(Intent(this, PetOverlayService::class.java).setAction(ACTION_TOGGLE), 1))
            .addAction(0, "退出", pending(Intent(this, PetOverlayService::class.java).setAction(ACTION_STOP), 2))
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type)
    }

    override fun onDestroy() {
        window?.detach()
        window = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "PetSpike"
        private const val CHANNEL = "pet"
        private const val NOTIFICATION_ID = 7340
        const val ACTION_TOGGLE = "com.jianyu.ds_pet.TOGGLE"
        const val ACTION_STOP = "com.jianyu.ds_pet.STOP"
    }
}
