package com.jianyu.ds_pet.pet

import android.graphics.PixelFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.graphics.Bitmap
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

class PetWindow(
    private val service: PetOverlayService,
    private val wm: WindowManager,
    val petIndex: Int,
    val petId: String,
) {
    val settings get() = service.settings
    val webView = PetWebView(service.windowContext)
    private val main = Handler(Looper.getMainLooper())

    private val lp = WindowManager.LayoutParams(
        1, 1,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        title = "dsh-pet:$petId"
    }

    private var pageReady = false
    private var windowHidden = false
    private var wantPaused = false
    private var holding = false
    private val pendingEmits = LinkedHashMap<String, String>()
    private var pendingBounds: IntArray? = null
    private val applyBounds = Choreographer.FrameCallback {
        val b = pendingBounds ?: return@FrameCallback
        pendingBounds = null
        lp.x = b[0]
        lp.y = b[1]
        lp.width = b[2]
        lp.height = b[3]
        if (webView.isAttachedToWindow) wm.updateViewLayout(webView, lp)
    }

    fun attach(geo: DeskGeometry, startHidden: Boolean = false) {
        pageReady = false
        pendingEmits.clear()
        if (startHidden) {
            windowHidden = true
            webView.visibility = View.GONE
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        webView.setOnApplyWindowInsetsListener { _, insets ->
            service.requestGeometryPush()
            insets
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                service.routes.handle(request) ?: service.assetLoader.shouldInterceptRequest(request.url)

            override fun onPageFinished(view: WebView, url: String) {
                pageReady = true
                val queued = pendingEmits.toMap()
                pendingEmits.clear()
                queued.forEach { (name, payload) -> deliver(name, payload) }
                if (wantPaused || windowHidden) applyHold()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    Log.e(TAG, "页面错误 ${error.errorCode} ${error.description}")
                }
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            // 没出帧时 WebView 会盖上系统自带的灰底播放键。换成全透明，快速换动画时不再闪那张模板。
            override fun getDefaultVideoPoster(): Bitmap = defaultVideoPoster()

            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                val msg = "${consoleMessage.message()} (${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})"
                when (consoleMessage.messageLevel()) {
                    ConsoleMessage.MessageLevel.ERROR -> Log.e(TAG, msg)
                    ConsoleMessage.MessageLevel.WARNING -> Log.w(TAG, msg)
                    else -> if (msg.contains("dsh-pet") || msg.contains("android")) Log.i(TAG, msg)
                }
                return true
            }
        }
        webView.addJavascriptInterface(PetJsBridge(this), "AndroidPet")
        wm.addView(webView, lp)
        webView.loadUrl(pageUrl(geo))
    }

    fun detach() {
        actionMenu?.dismiss()
        actionMenu = null
        pendingPos?.let { (id, json) -> settings.savePos(id, json) }
        pendingPos = null
        pendingEmits.clear()
        webView.setOnApplyWindowInsetsListener(null)
        Choreographer.getInstance().removeFrameCallback(applyBounds)
        main.removeCallbacksAndMessages(null)
        if (webView.isAttachedToWindow) wm.removeViewImmediate(webView)
        webView.destroy()
    }

    private fun pageUrl(geo: DeskGeometry): String {
        val work = geo.work
        val q = Uri.Builder()
            .appendQueryParameter("configUrl", "${PetRoutes.ORIGIN}/dsh-pet-7340/config")
            .appendQueryParameter("scale", geo.density.toString())
            .appendQueryParameter("petIndex", petIndex.toString())
            .appendQueryParameter("workAreaX", work.left.toString())
            .appendQueryParameter("workAreaY", work.top.toString())
            .appendQueryParameter("workAreaW", work.width().toString())
            .appendQueryParameter("workAreaH", work.height().toString())
            .appendQueryParameter("areas", JSONArray().put(DeskGeometry.rect(work)).toString())
            .appendQueryParameter("workAreas", JSONArray().put(DeskGeometry.rect(work)).toString())
            .appendQueryParameter("panels", JSONArray().put(DeskGeometry.rect(geo.panel)).toString())
            .appendQueryParameter("primaryIndex", "0")
            .appendQueryParameter("openTarget", "site")
            .appendQueryParameter("sideCrop", if (settings.compact) PetSettings.COMPACT_SIDE_CROP.toString() else "0")
            .build().encodedQuery
        return "${PetRoutes.ORIGIN}/assets/pet-web/index.html?$q"
    }

    fun onBounds(o: JSONObject) {
        val first = pendingBounds == null
        pendingBounds = intArrayOf(
            o.getDouble("x").roundToInt(),
            o.getDouble("y").roundToInt(),
            o.getDouble("width").roundToInt().coerceAtLeast(1),
            o.getDouble("height").roundToInt().coerceAtLeast(1),
        )
        if (first) Choreographer.getInstance().postFrameCallback(applyBounds)
        service.broker.update(
            petId,
            JSONObject()
                .put("x", o.getDouble("boxX"))
                .put("y", o.getDouble("boxY"))
                .put("vx", o.optDouble("vx", 0.0))
                .put("vy", o.optDouble("vy", 0.0))
                .put("size", o.getDouble("size"))
                .put("bottomPad", o.getDouble("bottomPad")),
        )
    }

    fun onFlight(o: JSONObject) = service.broker.update(petId, o)

    fun onCollide(targetId: String, vx: Double, vy: Double) = service.broker.hit(targetId, vx, vy)

    fun emit(name: String, payloadJson: String) {
        if (!pageReady) {
            if (webView.isAttachedToWindow) pendingEmits[name] = payloadJson
            return
        }
        deliver(name, payloadJson)
    }

    private fun deliver(name: String, payloadJson: String) {
        if (!webView.isAttachedToWindow) return
        webView.evaluateJavascript(
            "window.__androidBridge && window.__androidBridge.emit(${JSONObject.quote(name)}, $payloadJson)",
            null,
        )
    }

    fun showMenu(items: JSONArray) {
        if (windowHidden || !webView.isAttachedToWindow) return
        val menu = actionMenu ?: PetActionMenu(service.windowContext, wm) { leaf ->
            emit("menuAction", leaf.toString())
        }.also { actionMenu = it }
        menu.show(MenuRect(lp.x, lp.y, lp.x + lp.width, lp.y + lp.height), items)
    }

    fun setPaused(paused: Boolean) {
        wantPaused = paused
        applyHold()
    }

    fun setWindowHidden(hidden: Boolean) {
        if (hidden) actionMenu?.dismiss()
        val changed = windowHidden != hidden
        windowHidden = hidden
        if (changed) {
            webView.visibility = if (hidden) View.GONE else View.VISIBLE
            if (hidden) {
                lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            } else {
                lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            }
            if (webView.isAttachedToWindow) {
                runCatching { wm.updateViewLayout(webView, lp) }
            }
        }
        applyHold()
    }

    private fun applyHold() {
        val hold = wantPaused || windowHidden
        main.removeCallbacks(pauseWeb)
        if (!hold) {
            val wasHolding = holding
            holding = false
            webView.onResume()
            emit("paused", "false")
            // 暂停或隐藏期间 WebView 可能没吃进几何更新。恢复后强制再推一次，旋转过的位置才能对上。
            if (wasHolding && pageReady) service.onPetResumed()
            return
        }
        holding = true
        emit("paused", "true")
        if (pageReady) main.postDelayed(pauseWeb, 150)
    }

    private val pauseWeb = Runnable { webView.onPause() }

    fun requestReload() = service.reloadAll(clearPositions = true)

    fun openChat(petId: String) = service.openChat(petId.ifBlank { this.petId })

    private var actionMenu: PetActionMenu? = null
    private var pendingPos: Pair<String, String>? = null

    fun savePosDebounced(petId: String, json: String) {
        main.post {
            pendingPos = petId to json
            main.removeCallbacks(flushPos)
            main.postDelayed(flushPos, 500)
        }
    }

    private val flushPos = Runnable {
        pendingPos?.let { (id, json) -> settings.savePos(id, json) }
        pendingPos = null
    }

    companion object {
        private const val TAG = "dsh-pet"
        private var poster: Bitmap? = null

        @Synchronized
        internal fun defaultVideoPoster(): Bitmap {
            val existing = poster
            if (existing != null && !existing.isRecycled) return existing
            return Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).also {
                it.eraseColor(Color.TRANSPARENT)
                poster = it
            }
        }
    }
}
