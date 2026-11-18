package com.bravemusic.player.adblock

import android.content.Context
import android.util.Log
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class BraveShieldSyncWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting BraveShield filter list background update...")
            
            // Fetch updated YouTube-specific and general adblocking rules
            val ruleUrls = listOf(
                "https://raw.githubusercontent.com/brave/adblock-lists/master/brave-lists/brave-unbreak.txt",
                "https://easylist.to/easylist/easylist.txt"
            )

            val combinedRules = StringBuilder()

            for (url in ruleUrls) {
                try {
                    val request = Request.Builder().url(url).build()
                    val response = httpClient.newCall(request).execute()
                    if (response.isSuccessful) {
                        response.body?.string()?.let { content ->
                            combinedRules.append(content).append("\n")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to download rule list from $url", e)
                }
            }

            if (combinedRules.isNotEmpty()) {
                BraveShield.updateRules(context, combinedRules.toString())
                Log.d(TAG, "BraveShield filter rules successfully updated and cached.")
                Result.success()
            } else {
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "BraveShield update worker encountered an error", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "BraveShieldSync"
        private const val WORK_NAME = "brave_shield_periodic_sync"

        fun schedulePeriodicSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED) // Only update on Wi-Fi (Saves cellular data!)
                .setRequiresBatteryNotLow(true)               // Only when battery is not low (Saves battery!)
                .build()

            val updateRequest = PeriodicWorkRequestBuilder<BraveShieldSyncWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                updateRequest
            )
        }
    }
}
