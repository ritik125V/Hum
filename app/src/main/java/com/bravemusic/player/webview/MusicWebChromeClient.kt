package com.bravemusic.player.webview

import android.animation.ObjectAnimator
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.ProgressBar

class MusicWebChromeClient(
    private val progressBar: ProgressBar,
    private val dropLoadingView: View? = null
) : WebChromeClient() {

    private var isDroppingDown = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val autoDismissRunnable = Runnable {
        hideDropLoading()
    }

    fun showDropLoading() {
        dropLoadingView?.let { dropView ->
            mainHandler.removeCallbacks(autoDismissRunnable)
            // Auto dismiss after 2.5s to prevent lingering on SPA subresource loads
            mainHandler.postDelayed(autoDismissRunnable, 2500)

            if (!isDroppingDown) {
                isDroppingDown = true
                dropView.visibility = View.VISIBLE
                val density = dropView.resources.displayMetrics.density
                val offscreenY = -120f * density
                if (dropView.translationY >= 0f) {
                    dropView.translationY = offscreenY
                }
                dropView.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(260)
                    .setInterpolator(DecelerateInterpolator(1.6f))
                    .start()
            }
        }
    }

    fun hideDropLoading() {
        mainHandler.removeCallbacks(autoDismissRunnable)
        dropLoadingView?.let { dropView ->
            if (isDroppingDown || dropView.visibility == View.VISIBLE) {
                isDroppingDown = false
                val density = dropView.resources.displayMetrics.density
                val offscreenY = -140f * density
                dropView.animate()
                    .translationY(offscreenY)
                    .alpha(0f)
                    .setDuration(220)
                    .setInterpolator(AccelerateInterpolator(1.3f))
                    .withEndAction {
                        dropView.visibility = View.GONE
                    }
                    .start()
            }
        }
    }

    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        if (newProgress < 100) {
            if (progressBar.visibility != View.VISIBLE) {
                progressBar.visibility = View.VISIBLE
                progressBar.alpha = 1f
            }
            // Smoothly animate progress bar gradient transition
            ObjectAnimator.ofInt(progressBar, "progress", progressBar.progress, newProgress).apply {
                duration = 180
                interpolator = DecelerateInterpolator()
                start()
            }

            // Smooth drop-down loading pill animation from top
            if (newProgress < 85) {
                showDropLoading()
            }
        } else {
            // Smoothly complete and retract
            ObjectAnimator.ofInt(progressBar, "progress", progressBar.progress, 100).apply {
                duration = 120
                interpolator = DecelerateInterpolator()
                start()
            }
            progressBar.animate()
                .alpha(0f)
                .setDuration(160)
                .withEndAction {
                    progressBar.visibility = View.GONE
                    progressBar.progress = 0
                }
                .start()

            hideDropLoading()
        }
        super.onProgressChanged(view, newProgress)
    }

    override fun onJsBeforeUnload(
        view: WebView?,
        url: String?,
        message: String?,
        result: android.webkit.JsResult?
    ): Boolean {
        // Automatically allow navigation without annoying popups
        result?.confirm()
        return true
    }
}
