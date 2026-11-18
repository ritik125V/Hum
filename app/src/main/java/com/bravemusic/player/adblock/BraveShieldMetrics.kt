package com.bravemusic.player.adblock

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.atomic.AtomicLong

object BraveShieldMetrics {
    private const val PREFS_NAME = "brave_shield_metrics"
    private const val KEY_BLOCKED_COUNT = "total_blocked_count"
    private const val KEY_DATA_SAVED_BYTES = "total_data_saved_bytes"

    private lateinit var prefs: SharedPreferences
    private val blockedCounter = AtomicLong(0)
    private val dataSavedBytesCounter = AtomicLong(0)

    // Average size of an ad/tracker script or video beacon (~45 KB)
    private const val AVG_AD_PAYLOAD_BYTES = 46080L

    @Synchronized
    fun initialize(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        blockedCounter.set(prefs.getLong(KEY_BLOCKED_COUNT, 0L))
        dataSavedBytesCounter.set(prefs.getLong(KEY_DATA_SAVED_BYTES, 0L))
    }

    fun recordBlocked(url: String, estimatedBytes: Long = AVG_AD_PAYLOAD_BYTES): Long {
        val newBlocked = blockedCounter.incrementAndGet()
        val newDataSaved = dataSavedBytesCounter.addAndGet(estimatedBytes)
        
        // Persist periodically or when reaching multiples
        if (newBlocked % 10L == 0L && ::prefs.isInitialized) {
            prefs.edit()
                .putLong(KEY_BLOCKED_COUNT, newBlocked)
                .putLong(KEY_DATA_SAVED_BYTES, newDataSaved)
                .apply()
        }
        return newBlocked
    }

    fun getTotalBlockedCount(): Long = blockedCounter.get()

    fun getFormattedDataSaved(): String {
        val bytes = dataSavedBytesCounter.get()
        return when {
            bytes >= 1024L * 1024L * 1024L -> String.format("%.2f GB", bytes.toDouble() / (1024 * 1024 * 1024))
            bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes.toDouble() / (1024 * 1024))
            bytes >= 1024L -> String.format("%.0f KB", bytes.toDouble() / 1024)
            else -> "$bytes B"
        }
    }

    fun resetMetrics() {
        blockedCounter.set(0)
        dataSavedBytesCounter.set(0)
        if (::prefs.isInitialized) {
            prefs.edit().clear().apply()
        }
    }
}
