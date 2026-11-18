package com.bravemusic.player.webview

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView

/**
 * Custom WebView that intercepts window visibility changes
 * and guarantees input focus for the Android Soft Keyboard (IME).
 */
class MusicWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN && !hasFocus()) {
            requestFocus()
        }
        return super.onTouchEvent(event)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        // Always report View.VISIBLE to Chromium to prevent background media suspension
        if (visibility != View.VISIBLE) {
            super.onWindowVisibilityChanged(View.VISIBLE)
        } else {
            super.onWindowVisibilityChanged(visibility)
        }
    }

    override fun dispatchWindowVisibilityChanged(visibility: Int) {
        if (visibility != View.VISIBLE) {
            super.dispatchWindowVisibilityChanged(View.VISIBLE)
        } else {
            super.dispatchWindowVisibilityChanged(visibility)
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, View.VISIBLE)
    }
}
