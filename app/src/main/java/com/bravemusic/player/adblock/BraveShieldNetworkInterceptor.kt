package com.bravemusic.player.adblock

import android.util.Log
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

class BraveShieldNetworkInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()
        val method = request.method

        // 1. Evaluate URL through BraveShield's adblock rules engine
        if (BraveShield.shouldBlock(url, sourceUrl = "https://music.youtube.com", method = method)) {
            Log.d(TAG, "🛑 BraveShield INTERCEPTOR BLOCKED: $url")
            BraveShieldMetrics.recordBlocked(url)

            // Return immediate HTTP 204 No Content response without any network transmission
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_2)
                .code(204)
                .message("Blocked by Brave Shields")
                .body("".toResponseBody("text/plain".toMediaTypeOrNull()))
                .build()
        }

        // 2. Allow clean requests to proceed normally
        return chain.proceed(request)
    }

    companion object {
        private const val TAG = "BraveShieldInterceptor"
    }
}
