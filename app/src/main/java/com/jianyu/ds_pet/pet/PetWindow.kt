package com.jianyu.ds_pet.pet

import android.annotation.SuppressLint
import android.graphics.PixelFormat
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlin.math.roundToInt

class PetWindow(
    private val service: PetOverlayService,
    private val wm: WindowManager,
) {
    val webView = PetWebView(service.windowContext)

    private val lp = WindowManager.LayoutParams(
        SPIKE_WIDTH,
        SPIKE_HEIGHT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        title = "dsh-pet:spike"
    }

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

    private var dragging = false
    private var grabX = 0f
    private var grabY = 0f

    @SuppressLint("ClickableViewAccessibility")
    fun attach() {
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                service.routes.handle(request) ?: service.assetLoader.shouldInterceptRequest(request.url)
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                Log.i(TAG, consoleMessage.message())
                return true
            }
        }
        webView.addJavascriptInterface(PetJsBridge(this), "AndroidPet")
        webView.setOnTouchListener { _, event ->
            trackDrag(event)
            false
        }
        val (x, y) = center()
        lp.x = x
        lp.y = y
        wm.addView(webView, lp)
        webView.loadUrl("${PetRoutes.ORIGIN}/assets/pet-web/spike.html")
        Log.i(TAG, "spike window ${lp.width}x${lp.height} at ${lp.x},${lp.y}")
    }

    fun detach() {
        Choreographer.getInstance().removeFrameCallback(applyBounds)
        webView.setOnTouchListener(null)
        if (webView.isAttachedToWindow) wm.removeViewImmediate(webView)
        webView.destroy()
    }

    fun onBounds(x: Int, y: Int, width: Int, height: Int) {
        val first = pendingBounds == null
        pendingBounds = intArrayOf(x, y, width.coerceAtLeast(1), height.coerceAtLeast(1))
        if (first) Choreographer.getInstance().postFrameCallback(applyBounds)
    }

    fun setPaused(paused: Boolean) {
        if (paused) webView.onPause() else webView.onResume()
    }

    fun recenter() {
        val (x, y) = center()
        onBounds(x, y, lp.width, lp.height)
    }

    private fun center(): Pair<Int, Int> {
        val b = wm.currentWindowMetrics.bounds
        val x = b.left + (b.width() - lp.width) / 2
        val y = b.top + (b.height() - lp.height) / 2
        return x to y
    }

    private fun trackDrag(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                grabX = event.rawX - lp.x
                grabY = event.rawY - lp.y
                dragging = true
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                val screen = wm.currentWindowMetrics.bounds
                val x = (event.rawX - grabX).roundToInt()
                    .coerceIn(screen.left - lp.width + 48, screen.right - 48)
                val y = (event.rawY - grabY).roundToInt()
                    .coerceIn(screen.top - lp.height + 48, screen.bottom - 48)
                onBounds(x, y, SPIKE_WIDTH, SPIKE_HEIGHT)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
    }

    companion object {
        const val SPIKE_WIDTH = 720
        const val SPIKE_HEIGHT = 405
        private const val TAG = "PetSpike"
    }
}
