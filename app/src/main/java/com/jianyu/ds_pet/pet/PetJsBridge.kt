package com.jianyu.ds_pet.pet

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import org.json.JSONObject
import kotlin.math.roundToInt

class PetJsBridge(private val window: PetWindow) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun rawPointer(): String = "${window.webView.lastRawX},${window.webView.lastRawY}"

    @JavascriptInterface
    fun setBounds(json: String) {
        val o = JSONObject(json)
        val x = o.getDouble("x").roundToInt()
        val y = o.getDouble("y").roundToInt()
        val width = o.getDouble("width").roundToInt()
        val height = o.getDouble("height").roundToInt()
        main.post { window.onBounds(x, y, width, height) }
    }
}
