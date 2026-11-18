package com.bravemusic.player.webview

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.bravemusic.player.adblock.BraveShield
import com.bravemusic.player.playback.AudioDiskCache
import com.bravemusic.player.utils.SettingsManager
import java.io.ByteArrayInputStream

class MusicWebViewClient(
    private val context: Context,
    private val audioCache: AudioDiskCache,
    private val onPageLoaded: () -> Unit
) : WebViewClient() {

    private val settings = SettingsManager(context)

    override fun shouldOverrideUrlLoading(
        view: WebView?,
        request: WebResourceRequest?
    ): Boolean {
        val url = request?.url?.toString() ?: return false

        // Block Play Store redirects / nag intents that break WebView with ERR_UNKNOWN_URL_SCHEME
        if (url.startsWith("intent://") || url.startsWith("market://") || url.contains("play.google.com/store/apps")) {
            Log.d(TAG, "Suppressed Play Store redirect intent: $url")
            return true // Consumed and blocked
        }

        // Suppress any non-http(s) scheme
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return true
        }

        // Keep all navigation (YouTube Music, Google Accounts, Sign-in flows) inside the app
        return false
    }

    override fun onReceivedError(
        view: WebView?,
        errorCode: Int,
        description: String?,
        failingUrl: String?
    ) {
        super.onReceivedError(view, errorCode, description, failingUrl)
        if (failingUrl?.startsWith("intent:") == true || failingUrl?.startsWith("market:") == true) {
            view?.post {
                if (view.canGoBack()) {
                    view.goBack()
                } else {
                    view.loadUrl("https://music.youtube.com")
                }
            }
        }
    }

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        val url = request?.url?.toString() ?: return null
        val method = request.method ?: "GET"

        // 0. AUTHENTICATION SAFEGUARD: Never intercept or block Google login & OAuth tokens
        if (url.contains("accounts.google.com") ||
            url.contains("accounts.youtube.com") ||
            url.contains("apis.google.com") ||
            url.contains("myaccount.google.com")) {
            return super.shouldInterceptRequest(view, request)
        }

        // 1. BRAVE SHIELD CHECK: Block Google Ads, trackers, analytics, and telemetry
        if (BraveShield.shouldBlock(url, sourceUrl = "https://music.youtube.com", method = method)) {
            Log.d(TAG, "BraveShield BLOCKED: $url")
            com.bravemusic.player.adblock.BraveShieldMetrics.recordBlocked(url)
            return createEmptyResponse()
        }

        // Block ad video streaming chunks before they can play
        if (url.contains("googlevideo.com/videoplayback") &&
            (url.contains("&adformat=") || url.contains("&ad_type=") || url.contains("&ad_cpn=") || url.contains("&is_ad=1"))) {
            Log.d(TAG, "BraveShield BLOCKED ad stream: $url")
            com.bravemusic.player.adblock.BraveShieldMetrics.recordBlocked(url, 250000L) // ad video chunk ~250KB
            return createEmptyResponse()
        }

        // 2. CACHE CHECK: If audio is already stored on disk, serve from local flash
        if (url.contains("googlevideo.com/videoplayback")) {
            val cachedResponse = audioCache.getCachedResponse(url)
            if (cachedResponse != null) {
                return cachedResponse
            }
        }

        return super.shouldInterceptRequest(view, request)
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        if (view != null) {
            ScriptletInjector.injectAll(context, view)
        }
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        android.webkit.CookieManager.getInstance().flush()
        if (view != null) {
            ScriptletInjector.injectAll(context, view)
        }
        onPageLoaded()
    }

    private fun createEmptyResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "UTF-8",
            ByteArrayInputStream(ByteArray(0))
        )
    }

    companion object {
        private const val TAG = "MusicWebViewClient"
    }
}
