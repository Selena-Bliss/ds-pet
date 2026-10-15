package com.jianyu.ds_pet.pet

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.MotionEvent
import android.webkit.WebView

@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class PetWebView(context: Context) : WebView(context) {
    @Volatile var lastRawX = 0f
        private set
    @Volatile var lastRawY = 0f
        private set

    init {
        setBackgroundColor(Color.TRANSPARENT)
        isLongClickable = false
        setOnLongClickListener { true }
        isHapticFeedbackEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        settings.apply {
            javaScriptEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            setSupportZoom(false)
            loadWithOverviewMode = false
            textZoom = 100
            useWideViewPort = false
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        lastRawX = event.rawX
        lastRawY = event.rawY
        return super.dispatchTouchEvent(event)
    }
}
