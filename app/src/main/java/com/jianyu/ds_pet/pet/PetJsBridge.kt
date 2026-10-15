package com.jianyu.ds_pet.pet

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject

class PetJsBridge(private val window: PetWindow) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun rawPointer(): String = "${window.webView.lastRawX},${window.webView.lastRawY}"

    @JavascriptInterface
    fun setBounds(json: String) {
        val o = JSONObject(json)
        main.post { window.onBounds(o) }
    }

    @JavascriptInterface
    fun reportFlight(json: String) {
        val o = JSONObject(json)
        main.post { window.onFlight(o) }
    }

    @JavascriptInterface
    fun reportCollide(targetId: String, vx: Double, vy: Double) {
        main.post { window.onCollide(targetId, vx, vy) }
    }

    @JavascriptInterface
    fun showMenu(json: String) {
        val items = JSONArray(json)
        main.post { window.showMenu(items) }
    }

    @JavascriptInterface
    fun reload() {
        main.post { window.requestReload() }
    }

    @JavascriptInterface
    fun openChat(petId: String) {
        main.post { window.openChat(petId) }
    }

    @JavascriptInterface
    fun loadPos(petId: String): String = window.settings.loadPos(petId)

    @JavascriptInterface
    fun savePos(petId: String, json: String) = window.savePosDebounced(petId, json)
}
