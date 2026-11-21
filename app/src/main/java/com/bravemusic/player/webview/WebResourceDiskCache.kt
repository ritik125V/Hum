package com.bravemusic.player.webview

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Intelligent Static Web Resource Disk Cache for Alternative 2 Architecture.
 * 
 * Aggressively intercepts and caches static, immutable web assets:
 * - JavaScript bundles (e.g. s.ytimg.com/yts/jsbin, desktop_polymer.js, base.js)
 * - CSS stylesheets
 * - Web fonts (WOFF2, TTF, Google Fonts)
 * - Core static SVG vectors
 * 
 * Crucially EXCLUDES:
 * - /youtubei/v1/ endpoints (Browse recommendations, search, next queue, stream ciphers)
 *   so that the user ALWAYS receives fresh, live, personalized YouTube Music recommendations.
 * - Authentication & Google login endpoints.
 * - Media audio streams (handled by AudioDiskCache).
 */
class WebResourceDiskCache(private val context: Context) {

    private val cacheDir = File(context.cacheDir, "web_static_cache").apply {
        if (!exists()) mkdirs()
    }

    private val maxCacheSizeBytes = 80L * 1024L * 1024L // 80 MB static cache
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val inFlightUrls = ConcurrentHashMap<String, Boolean>()

    fun shouldCache(url: String): Boolean {
        // Exclude dynamic API calls - MUST ALWAYS BE LIVE FOR PERSONALIZED RECOMMENDATIONS
        if (url.contains("/youtubei/v1/")) return false
        
        // Exclude Google Auth & login flows
        if (url.contains("accounts.google.com") ||
            url.contains("accounts.youtube.com") ||
            url.contains("apis.google.com") ||
            url.contains("myaccount.google.com")) return false

        // Exclude video/audio streaming chunks (handled by AudioDiskCache)
        if (url.contains("googlevideo.com/videoplayback")) return false

        // Exclude root HTML document to ensure latest YouTube Music web version is fetched
        val uri = try { Uri.parse(url) } catch (e: Exception) { return false }
        val path = uri.path ?: ""
        if (path == "/" || path.isEmpty()) return false

        // Cache static JS bundles, CSS stylesheets, fonts, and static vectors
        if (url.contains(".js") || url.contains(".mjs") ||
            url.contains("/yts/jsbin/") ||
            url.contains(".css") ||
            url.contains(".woff2") || url.contains(".woff") || url.contains(".ttf") ||
            url.contains("fonts.gstatic.com") ||
            (url.contains(".svg") && !url.contains("profile")) ||
            url.contains("/static/")) {
            return true
        }

        return false
    }

    fun getCachedResponse(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url?.toString() ?: return null
        if (!shouldCache(url)) return null

        val cacheKey = hashString(url)
        val dataFile = File(cacheDir, "$cacheKey.data")
        val metaFile = File(cacheDir, "$cacheKey.meta")

        if (dataFile.exists() && dataFile.length() > 0 && metaFile.exists()) {
            return try {
                val mimeType = metaFile.readText().trim().ifEmpty { guessMimeType(url) }
                createResponse(mimeType, FileInputStream(dataFile))
            } catch (e: Exception) {
                null
            }
        }

        // Cache miss: asynchronously or synchronously fetch asset via OkHttp and write to disk
        // shouldInterceptRequest runs on a background binder/IO thread, so network I/O is valid
        if (inFlightUrls.putIfAbsent(cacheKey, true) == null) {
            try {
                val okHttpRequest = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
                    .build()

                client.newCall(okHttpRequest).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body
                        if (body != null) {
                            val mimeType = body.contentType()?.toString()?.split(";")?.firstOrNull()?.trim()
                                ?: guessMimeType(url)
                            val bytes = body.bytes()
                            if (bytes.isNotEmpty() && bytes.size < 15 * 1024 * 1024) { // < 15MB
                                ensureCacheCapacity(bytes.size.toLong())
                                val tempFile = File(cacheDir, "$cacheKey.tmp")
                                FileOutputStream(tempFile).use { it.write(bytes) }
                                tempFile.renameTo(dataFile)
                                metaFile.writeText(mimeType)

                                return createResponse(mimeType, ByteArrayInputStream(bytes))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Static asset pre-cache fetch skipped: $url (${e.message})")
            } finally {
                inFlightUrls.remove(cacheKey)
            }
        }

        return null
    }

    private fun createResponse(mimeType: String, inputStream: java.io.InputStream): WebResourceResponse {
        val encoding = if (mimeType.startsWith("text/") || mimeType.contains("javascript") || mimeType.contains("json")) "UTF-8" else null
        val headers = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Cache-Control" to "public, max-age=31536000, immutable",
            "Timing-Allow-Origin" to "*"
        )
        return WebResourceResponse(
            mimeType,
            encoding,
            200,
            "OK",
            headers,
            inputStream
        )
    }

    private fun guessMimeType(url: String): String {
        return when {
            url.contains(".js") || url.contains("/jsbin/") -> "application/javascript"
            url.contains(".css") -> "text/css"
            url.contains(".woff2") -> "font/woff2"
            url.contains(".woff") -> "font/woff"
            url.contains(".ttf") -> "font/ttf"
            url.contains(".svg") -> "image/svg+xml"
            url.contains(".png") -> "image/png"
            url.contains(".jpg") || url.contains(".jpeg") -> "image/jpeg"
            url.contains(".webp") -> "image/webp"
            else -> "application/octet-stream"
        }
    }

    private fun ensureCacheCapacity(newBytes: Long) {
        var currentSize = cacheDir.listFiles()?.sumOf { it.length() } ?: 0L
        if (currentSize + newBytes > maxCacheSizeBytes) {
            val files = cacheDir.listFiles()?.sortedBy { it.lastModified() } ?: return
            for (file in files) {
                currentSize -= file.length()
                file.delete()
                if (currentSize + newBytes <= maxCacheSizeBytes) break
            }
        }
    }

    private fun hashString(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "WebResourceDiskCache"
    }
}
