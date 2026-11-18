package com.bravemusic.player.playback

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.WebResourceResponse
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * High-efficiency LRU Audio Stream Cache.
 * Caches Opus/AAC audio chunks locally on disk.
 * When a song is replayed, serves bytes directly from flash storage,
 * saving mobile data and keeping the phone's 4G/5G radio in low-power sleep.
 */
class AudioDiskCache(context: Context) {
    private val cacheDir = File(context.cacheDir, "audio_stream_cache").apply {
        if (!exists()) mkdirs()
    }
    private val maxCacheSizeBytes = 500L * 1024L * 1024L // 500 MB max audio cache

    fun getCachedResponse(url: String): WebResourceResponse? {
        val cacheKey = extractAudioCacheKey(url) ?: return null
        val cachedFile = File(cacheDir, cacheKey)

        if (cachedFile.exists() && cachedFile.length() > 0) {
            Log.d(TAG, "Cache HIT: Serving audio from disk ($cacheKey) - 0 network used")
            return try {
                WebResourceResponse(
                    "audio/webm",
                    "UTF-8",
                    FileInputStream(cachedFile)
                )
            } catch (e: Exception) {
                Log.w(TAG, "Error opening cached audio stream", e)
                null
            }
        }
        return null
    }

    fun cacheAudioChunk(url: String, data: ByteArray) {
        val cacheKey = extractAudioCacheKey(url) ?: return
        try {
            ensureCacheCapacity(data.size.toLong())
            val cachedFile = File(cacheDir, cacheKey)
            FileOutputStream(cachedFile, true).use { fos ->
                fos.write(data)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cache audio chunk", e)
        }
    }

    private fun extractAudioCacheKey(url: String): String? {
        return try {
            val uri = Uri.parse(url)
            val id = uri.getQueryParameter("id") ?: uri.getQueryParameter("docid")
            val itag = uri.getQueryParameter("itag") // e.g. 251 for Opus
            if (id != null) {
                hashString("${id}_${itag ?: "default"}")
            } else {
                null
            }
        } catch (e: Exception) {
            null
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
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "AudioDiskCache"
    }
}
