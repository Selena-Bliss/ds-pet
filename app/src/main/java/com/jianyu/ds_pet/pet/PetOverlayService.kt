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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import android.webkit.WebView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import com.jianyu.ds_pet.BuildConfig
import com.jianyu.ds_pet.MainActivity
import com.jianyu.ds_pet.R
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class PetOverlayService : Service() {
    lateinit var windowContext: Context
        private set
    lateinit var settings: PetSettings
        private set
    lateinit var routes: PetRoutes
        private set
    lateinit var assetLoader: WebViewAssetLoader
        private set
    val broker = CollisionBroker(
        petIds = { windows.map { it.petId }.toSet() },
        collisionEnabled = { settings.petCollision },
        emitStates = { payload -> windows.forEach { it.emit("flightStates", payload) } },
        emitHit = { id, payload -> windows.firstOrNull { it.petId == id }?.emit("petHit", payload) },
    )

    private lateinit var wm: WindowManager
    private val windows = mutableListOf<PetWindow>()
    private val main = Handler(Looper.getMainLooper())
    private var displayManager: DisplayManager? = null
    private var paused = false
    private var hidden = false
    private var failed = false
    private var lastGeo: String? = null
    private var geoMismatchRetries = 0
    private lateinit var metricsContext: Context
    private val aiConfig = lazy { loadAiConfig(this) }
    private var aiExec: ExecutorService? = null
    private var scheduler: PetAiScheduler? = null

    private val pushGeoSoon = Runnable { pushGeometryNow() }
    private val pushGeoLater = Runnable { pushGeometryNow() }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = requestGeometryPush()
        override fun onDisplayRemoved(displayId: Int) = requestGeometryPush()
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) requestGeometryPush()
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val on = i.action == Intent.ACTION_SCREEN_ON
            setPaused(!on)
            scheduler?.setScreenOn(on)
        }
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
        metricsContext = windowContext
        wm = windowContext.getSystemService(WindowManager::class.java)
        settings = PetSettings(this)
        val state = PetStateStore()
        val keys = ApiKeyStore(this)
        val exec = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "pet-ai").apply { isDaemon = true }
        }
        aiExec = exec
        val holidays = PricingHolidays(this)
        val engine = PetAi(
            key = { keys.load() },
            modelName = { settings.aiModel },
            config = { aiConfig.value },
            petName = { id -> petNamed(id, keys.hasKey())?.name },
            transport = UrlConnectionTransport(),
            memory = PetMemory(File(filesDir, "memory.json")),
            sink = StateJsonSink(state),
            executor = exec,
            onError = { Log.w(TAG, it) },
            isHoliday = { holidays.isHoliday(it) },
        )
        aiEngine = engine
        routes = PetRoutes(
            ctx = this,
            settings = settings,
            state = state,
            aiEnabled = { keys.hasKey() },
            onBalance = { engine.refreshBalance(manual = true) },
            onWhisper = { id -> engine.whisperLater(id) },
        )
        scheduler = PetAiScheduler(
            clock = MainClock(main),
            enabled = { keys.hasKey() },
            balancePeriodMs = { aiConfig.value.balancePeriodSec.coerceAtLeast(1) * 1000L },
            whisperPeriodMs = { aiConfig.value.whisperPeriodSec.coerceAtLeast(1) * 1000L },
            petIds = { petNamedList(keys.hasKey()).filter { it.whisperEnabled }.map { it.id } },
            refreshBalance = { exec.execute { engine.refreshBalance(manual = false) } },
            publishWhisper = { id -> exec.execute { engine.whisper(id) } },
        )
        assetLoader = WebViewAssetLoader.Builder()
            .setDomain("appassets.androidplatform.net")
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        startInForeground()
        displayManager = getSystemService(DisplayManager::class.java).also {
            it.registerDisplayListener(displayListener, main)
        }
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
            val interactive = getSystemService(PowerManager::class.java).isInteractive
            scheduler?.start(interactive)
            if (!interactive) setPaused(true)
            isRunning = true
            isHidden = false
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
            ACTION_RELOAD -> if (windows.isNotEmpty()) {
                reloadAll(intent?.getBooleanExtra(EXTRA_CLEAR_POS, false) == true)
                if (intent?.getBooleanExtra(EXTRA_KICK_AI, false) == true) scheduler?.kick()
            }
            ACTION_KICK_AI -> scheduler?.kickBalance()
            ACTION_HIDE -> setHidden(!hidden)
            else -> if (windows.isEmpty()) createWindows()
        }
        return START_STICKY
    }

    private fun createWindows() {
        try {
            val geo = DeskGeometry.of(windowContext)
            lastGeo = geo.toJson()
            repeat(settings.petCount.coerceIn(1, 4)) { i ->
                val id = if (i == 0) "main" else "main$i"
                windows += PetWindow(this, wm, i, id).also {
                    it.attach(geo, startHidden = hidden)
                    if (paused) it.setPaused(true)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "无法添加悬浮窗", e)
            windows.forEach { runCatching { it.detach() } }
            windows.clear()
            failed = true
            stopSelf()
        }
    }

    fun reloadAll(clearPositions: Boolean = false) {
        broker.clear()
        windows.forEach { it.detach() }
        // detach 会把还没写盘的落点补上，清位置必须放在这之后。
        if (clearPositions) settings.clearPositions()
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
        requestGeometryPush()
    }

    /** 旋转、折叠、系统栏或挖孔变化。同一帧里的多次回调合成一次，稍后再读一次，躲开指标还没刷新的那一帧。 */
    fun requestGeometryPush() {
        main.removeCallbacks(pushGeoSoon)
        main.removeCallbacks(pushGeoLater)
        main.post(pushGeoSoon)
        main.postDelayed(pushGeoLater, 200)
    }

    /** 亮屏或从隐藏恢复后，丢掉上次的几何缓存，让页面按当前屏幕再摆一次。 */
    fun onPetResumed() {
        lastGeo = null
        requestGeometryPush()
    }

    private fun pushGeometryNow() {
        if (windows.isEmpty()) return
        try {
            val geo = deskForCurrentRotation()
            if (geo == null) {
                if (geoMismatchRetries++ < 10) {
                    Log.i(TAG, "屏幕尺寸还没跟上旋转，稍后重试 $geoMismatchRetries")
                    main.postDelayed(pushGeoLater, 100)
                }
                return
            }
            geoMismatchRetries = 0
            val json = geo.toJson()
            if (json == lastGeo) return
            lastGeo = json
            val workW = geo.work.right - geo.work.left
            val workH = geo.work.bottom - geo.work.top
            Log.i(TAG, "desk ${workW}x$workH density=${geo.density}")
            windows.forEach { it.emit("displays", json) }
        } catch (e: Exception) {
            Log.e(TAG, "更新桌面几何失败", e)
        }
    }

    /**
     * 长寿的 windowContext 在部分系统上旋转后仍返回旧的宽高。
     * 和显示器当前方向对不上就换一份新的窗口上下文再读；仍对不上就先不推，避免用错视口。
     */
    private fun deskForCurrentRotation(): DeskGeometry? {
        val orientation = displayOrientation()
        val cached = DeskGeometry.of(metricsContext)
        if (boundsMatchOrientation(cached.panel.right - cached.panel.left, cached.panel.bottom - cached.panel.top, orientation)) {
            return cached
        }
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY) ?: return null
        val fresh = createDisplayContext(display)
            .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        val next = DeskGeometry.of(fresh)
        val width = next.panel.right - next.panel.left
        val height = next.panel.bottom - next.panel.top
        if (!boundsMatchOrientation(width, height, orientation)) return null
        // 旧上下文会一直停在旋转前的尺寸。换到这份新的，避免每次系统栏变化都再造一个。
        metricsContext = fresh
        return next
    }

    private fun displayOrientation(): Int {
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        return when (display?.rotation) {
            Surface.ROTATION_90, Surface.ROTATION_270 -> Configuration.ORIENTATION_LANDSCAPE
            Surface.ROTATION_0, Surface.ROTATION_180 -> Configuration.ORIENTATION_PORTRAIT
            else -> resources.configuration.orientation
        }
    }

    private fun setHidden(h: Boolean) {
        hidden = h
        isHidden = h
        windows.forEach { it.setWindowHidden(h) }
        updateNotification()
        publishState()
        Log.i(TAG, if (h) "hidden" else "shown")
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
        val title = when {
            hidden -> "桌宠已隐藏"
            paused -> "桌宠已暂停"
            else -> "桌宠正在屏幕上"
        }
        val text = when {
            hidden -> "全屏游戏或视频时可先藏起来，点「显示」恢复"
            paused -> "点「暂停/继续」恢复动画"
            else -> "可轻点、拖动，长按打开菜单"
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_pet)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(content)
            .setOngoing(true)
            .addAction(0, "暂停/继续", pending(ACTION_TOGGLE, 1))
            .addAction(0, if (hidden) "显示" else "隐藏", pending(ACTION_HIDE, 3))
            .addAction(0, "退出", pending(ACTION_STOP, 2))
            .build()
    }

    private fun publishState() {
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName))
    }

    fun openChat(petId: String) {
        if (!::settings.isInitialized) return
        val id = petId.ifBlank { "main" }
        val name = petNamed(id, ApiKeyStore(this).hasKey())?.name ?: id
        val intent = Intent(this, PetChatActivity::class.java)
            .putExtra(PetChatActivity.EXTRA_PET_ID, id)
            .putExtra(PetChatActivity.EXTRA_PET_NAME, name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "无法打开对话", e)
        }
    }

    private fun petNamedList(aiEnabled: Boolean): List<ServedPet> = servedPetConfigs(
        templateName = aiConfig.value.templateName,
        petCount = settings.petCount,
        sizeDp = settings.sizeDp,
        corner = settings.corner,
        marginXDp = settings.marginXDp,
        marginYDp = settings.marginYDp,
        fixed = settings.fixed,
        aiEnabled = aiEnabled,
    )

    private fun petNamed(id: String, aiEnabled: Boolean): ServedPet? =
        petNamedList(aiEnabled).firstOrNull { it.id == id }

    override fun onDestroy() {
        scheduler?.stop()
        aiExec?.shutdownNow()
        aiEngine = null
        isRunning = false
        isPaused = false
        isHidden = false
        publishState()
        main.removeCallbacks(pushGeoSoon)
        main.removeCallbacks(pushGeoLater)
        displayManager?.let { runCatching { it.unregisterDisplayListener(displayListener) } }
        runCatching { unregisterReceiver(screenReceiver) }
        broker.clear()
        windows.forEach { runCatching { it.detach() } }
        windows.clear()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private class StateJsonSink(private val state: PetStateStore) : AiStateSink {
        override fun writeSection(name: String, json: String) {
            state.writeSection(name, JSONObject(json))
        }

        override fun writePet(petId: String, leaf: String, json: String) {
            state.writePet(petId, leaf, JSONObject(json))
        }
    }

    companion object {
        private const val TAG = "dsh-pet"
        private const val CHANNEL = "pet"
        private const val NOTIFICATION_ID = 7340
        const val ACTION_TOGGLE = "com.jianyu.ds_pet.TOGGLE"
        const val ACTION_STOP = "com.jianyu.ds_pet.STOP"
        const val ACTION_RELOAD = "com.jianyu.ds_pet.RELOAD"
        const val EXTRA_CLEAR_POS = "clearPos"
        const val ACTION_HIDE = "com.jianyu.ds_pet.HIDE"
        const val ACTION_STATE = "com.jianyu.ds_pet.STATE"
        const val EXTRA_KICK_AI = "kickAi"
        const val ACTION_KICK_AI = "com.jianyu.ds_pet.KICK_AI"

        @Volatile
        internal var aiEngine: PetAi? = null
            private set

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var isPaused: Boolean = false
            private set

        @Volatile
        var isHidden: Boolean = false
            private set
    }
}
