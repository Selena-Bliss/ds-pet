package com.jianyu.ds_pet.pet

import android.graphics.PixelFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.Menu
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.PopupMenu
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

    fun attach(geo: DeskGeometry) {
        pageReady = false
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                service.routes.handle(request) ?: service.assetLoader.shouldInterceptRequest(request.url)

            override fun onPageFinished(view: WebView, url: String) {
                pageReady = true
                if (PetOverlayService.isPaused) setPaused(true)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    Log.e(TAG, "页面错误 ${error.errorCode} ${error.description}")
                }
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
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
        pendingPos?.let { (id, json) -> settings.savePos(id, json) }
        pendingPos = null
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
            .appendQueryParameter("topMarginRatio", settings.topMarginRatio.coerceIn(0.2f, 0.5f).toString())
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
        webView.evaluateJavascript(
            "window.__androidBridge && window.__androidBridge.emit(${JSONObject.quote(name)}, $payloadJson)",
            null,
        )
    }

    fun showMenu(items: JSONArray) {
        if (!webView.isAttachedToWindow) return
        val themed = ContextThemeWrapper(service.windowContext, android.R.style.Theme_DeviceDefault_Light)
        val popup = PopupMenu(themed, webView, Gravity.TOP)
        val leaves = mutableListOf<JSONObject>()
        for (i in 0 until items.length()) {
            val node = items.getJSONObject(i)
            val children = node.optJSONArray("children")
            if (children == null || children.length() == 0) {
                popup.menu.add(0, leaves.size + 1, i, node.getString("label"))
                leaves += node
            } else {
                val sub = popup.menu.addSubMenu(0, Menu.NONE, i, node.getString("label"))
                for (j in 0 until children.length()) {
                    val leaf = children.getJSONObject(j)
                    sub.add(0, leaves.size + 1, j, leaf.getString("label"))
                    leaves += leaf
                }
            }
        }
        popup.setOnMenuItemClickListener { item ->
            leaves.getOrNull(item.itemId - 1)?.let { emit("menuAction", it.toString()) }
            true
        }
        popup.setOnDismissListener { restoreNotFocusable() }
        // 悬浮窗默认不获焦，PopupMenu 在部分系统上收不到点击。菜单期间暂时允许获焦。
        lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        try {
            wm.updateViewLayout(webView, lp)
            popup.show()
        } catch (e: RuntimeException) {
            Log.e(TAG, "菜单无法显示", e)
            restoreNotFocusable()
        }
    }

    private fun restoreNotFocusable() {
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (webView.isAttachedToWindow) {
            runCatching { wm.updateViewLayout(webView, lp) }
        }
    }

    fun setPaused(paused: Boolean) {
        main.removeCallbacks(pauseWeb)
        if (!paused) {
            webView.onResume()
            emit("paused", "false")
            return
        }
        emit("paused", "true")
        if (pageReady) main.postDelayed(pauseWeb, 150)
    }

    private val pauseWeb = Runnable { webView.onPause() }

    fun requestReload() = service.reloadAll(clearPositions = true)

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
    }
}
