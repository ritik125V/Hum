package com.bravemusic.player.adblock

import android.content.Context
import android.util.Log
import java.io.File
import java.io.InputStreamReader

object BraveShield {
    private const val TAG = "BraveShield"
    private const val CACHE_FILE_NAME = "brave_shield_rules.dat"

    private var enginePtr: Long = 0
    private var isNativeLoaded = false
    private var isInitialized = false

    // Fast in-memory rule set for fallback when native library is not yet compiled
    private val blockedDomainPatterns = mutableListOf<String>()
    private val cosmeticHideSelectors = mutableListOf<String>()

    init {
        try {
            System.loadLibrary("brave_adblock_core")
            isNativeLoaded = true
            Log.d(TAG, "Native brave_adblock_core library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            isNativeLoaded = false
            Log.w(TAG, "Native library not yet compiled; running in fast Kotlin mode", e)
        }
    }

    @Synchronized
    fun initialize(context: Context) {
        if (isInitialized) return

        if (!isNativeLoaded) {
            // Load bundled rules into Kotlin memory structures
            loadKotlinFallbackRules(context)
            isInitialized = true
            return
        }

        val cacheFile = File(context.filesDir, CACHE_FILE_NAME)
        if (cacheFile.exists() && cacheFile.length() > 0) {
            try {
                val bytes = cacheFile.readBytes()
                enginePtr = createEngineFromSerialized(bytes)
                if (enginePtr != 0L) {
                    isInitialized = true
                    Log.d(TAG, "Initialized BraveShield from pre-compiled binary cache (${bytes.size} bytes)")
                    return
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not load binary cache, falling back to assets", e)
            }
        }

        // Fallback: Load bundled text rules from assets
        try {
            val assetManager = context.assets
            val rulesText = assetManager.open("adblock/default_rules.txt").use { input ->
                InputStreamReader(input).readText()
            }
            enginePtr = createEngineFromRules(rulesText)
            if (enginePtr != 0L) {
                isInitialized = true
                Log.d(TAG, "Initialized BraveShield from bundled default_rules.txt")
                saveBinaryCache(context)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize BraveShield from assets", e)
        }
    }

    private fun loadKotlinFallbackRules(context: Context) {
        try {
            val rules = context.assets.open("adblock/default_rules.txt").use { input ->
                InputStreamReader(input).readLines()
            }
            for (line in rules) {
                val trimmed = line.trim()
                if (trimmed.startsWith("!") || trimmed.isEmpty()) continue
                if (trimmed.contains("##")) {
                    val parts = trimmed.split("##")
                    if (parts.size == 2) {
                        cosmeticHideSelectors.add(parts[1].trim())
                    }
                } else if (trimmed.startsWith("||")) {
                    val pattern = trimmed.removePrefix("||").removeSuffix("^").removeSuffix("*")
                    blockedDomainPatterns.add(pattern)
                }
            }
            Log.d(TAG, "Loaded ${blockedDomainPatterns.size} blocking patterns and ${cosmeticHideSelectors.size} cosmetic rules in Kotlin fallback engine")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading fallback rules", e)
        }
    }

    @Synchronized
    fun updateRules(context: Context, newRulesText: String) {
        if (!isNativeLoaded) {
            val lines = newRulesText.lines()
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.startsWith("!") || trimmed.isEmpty()) continue
                if (trimmed.startsWith("||")) {
                    val pattern = trimmed.removePrefix("||").removeSuffix("^").removeSuffix("*")
                    blockedDomainPatterns.add(pattern)
                }
            }
            return
        }

        val newPtr = createEngineFromRules(newRulesText)
        if (newPtr != 0L) {
            val oldPtr = enginePtr
            enginePtr = newPtr
            if (oldPtr != 0L) {
                destroyEngine(oldPtr)
            }
            saveBinaryCache(context)
            Log.d(TAG, "BraveShield successfully updated with new rules")
        }
    }

    private fun saveBinaryCache(context: Context) {
        if (!isNativeLoaded || enginePtr == 0L) return
        try {
            val bytes = serializeEngine(enginePtr)
            if (bytes != null && bytes.isNotEmpty()) {
                val cacheFile = File(context.filesDir, CACHE_FILE_NAME)
                cacheFile.writeBytes(bytes)
                Log.d(TAG, "Saved pre-compiled BraveShield binary cache (${bytes.size} bytes)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save binary cache", e)
        }
    }

    fun shouldBlock(url: String, sourceUrl: String = "https://music.youtube.com", resourceType: String = "other", method: String = "GET"): Boolean {
        if (!isNativeLoaded || enginePtr == 0L) {
            for (pattern in blockedDomainPatterns) {
                if (url.contains(pattern)) return true
            }
            return url.contains("googleads") ||
                   url.contains("/pagead/") ||
                   url.contains("doubleclick.net") ||
                   url.contains("/api/stats/ads") ||
                   url.contains("/api/stats/qoe") ||
                   url.contains("/api/stats/atr") ||
                   url.contains("/ptracking") ||
                   url.contains("generate_204") ||
                   url.contains("get_midroll_info")
        }
        return shouldBlock(enginePtr, url, sourceUrl, resourceType, method)
    }

    fun getCosmeticCss(url: String = "https://music.youtube.com"): String {
        if (!isNativeLoaded || enginePtr == 0L) {
            return if (cosmeticHideSelectors.isNotEmpty()) {
                cosmeticHideSelectors.joinToString(", ") + " { display: none !important; }"
            } else ""
        }
        val bytes = getCosmeticResources(enginePtr, url) ?: return ""
        return String(bytes)
    }

    // Native JNI bindings implemented in Rust (adblock-core)
    private external fun createEngineFromRules(rulesText: String): Long
    private external fun createEngineFromSerialized(serializedBytes: ByteArray): Long
    private external fun serializeEngine(enginePtr: Long): ByteArray?
    private external fun shouldBlock(enginePtr: Long, url: String, sourceUrl: String, resourceType: String, method: String): Boolean
    private external fun getCosmeticResources(enginePtr: Long, url: String): ByteArray?
    private external fun destroyEngine(enginePtr: Long)
}
