package com.ryans.nostrshare.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ryans.nostrshare.NostrShareApp
import com.ryans.nostrshare.RelayManager
import com.ryans.nostrshare.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ryans.nostrshare.utils.NotificationHelper
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.ForegroundInfo
import android.media.AudioAttributes
import android.net.Uri

import android.content.pm.ServiceInfo

class ScheduledNoteWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val CHANNEL_ID_PROGRESS = "scheduled_progress"
        const val CHANNEL_ID_ALERTS = "scheduled_alerts_v2"
        const val NOTIFICATION_ID = 1002
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID, 
                createProgressNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, createProgressNotification())
        }
    }

    private fun createProgressNotification(): android.app.Notification {
        createNotificationChannel()
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID_PROGRESS)
            .setContentTitle("Publishing Scheduled Note")
            .setTicker("Publishing Scheduled Note")
            .setContentText("Broadcasting to relays...")
            .setSmallIcon(com.ryans.nostrshare.R.drawable.ic_notification_prism)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        com.ryans.nostrshare.utils.SchedulerLog.log(applicationContext, "ScheduledNoteWorker", "Worker started. ID: $id")
        
        try {
            setForeground(getForegroundInfo())
        } catch (e: Exception) {
            com.ryans.nostrshare.utils.SchedulerLog.log(applicationContext, "ScheduledNoteWorker", "Failed to set foreground: ${e.message}")
        }

        // Double check network
        if (!isNetworkAvailable()) {
            com.ryans.nostrshare.utils.SchedulerLog.log(applicationContext, "ScheduledNoteWorker", "Worker triggered but network unavailable. Retrying based on constraints.")
            return@withContext Result.retry()
        }

        var signedJson = inputData.getString("signed_json")
        var pubkey = inputData.getString("pubkey")
        val draftId = inputData.getInt("draft_id", -1)
        
        val app = applicationContext as NostrShareApp
        val dao = app.database.draftDao()

        // Load from DB if data missing (triggered via AlarmManager -> ScheduleReceiver)
        if (signedJson == null || pubkey == null) {
            if (draftId != -1) {
                val draft = dao.getDraftById(draftId)
                if (draft != null) {
                    signedJson = draft.signedJson
                    pubkey = draft.pubkey
                }
            }
        }

        if (signedJson == null) {
            com.ryans.nostrshare.utils.SchedulerLog.log(applicationContext, "ScheduledNoteWorker", "Error: signed_json is null")
            return@withContext Result.failure()
        }
        if (pubkey == null) {
             com.ryans.nostrshare.utils.SchedulerLog.log(applicationContext, "ScheduledNoteWorker", "Error: pubkey is null")
             return@withContext Result.failure()
        }
        
        com.ryans.nostrshare.utils.SchedulerLog.log(applicationContext, "ScheduledNoteWorker", "Processing draft $draftId")

        val settingsRepository = SettingsRepository(app)
        val relayManager = RelayManager(app.client, settingsRepository)

        createNotificationChannel()
        NotificationHelper.createNotificationChannel(applicationContext)

        try {
            // Find relays for the user
            val baseRelays = try {
                val fetched = relayManager.fetchRelayList(pubkey)
                if (fetched.isEmpty()) listOf("wss://relay.damus.io", "wss://nos.lol") else fetched
            } catch (_: Exception) {
                listOf("wss://relay.damus.io", "wss://nos.lol")
            }

            val targetRelays = mutableListOf<String>().apply { addAll(baseRelays) }
            if (settingsRepository.isCitrineRelayEnabled()) {
                targetRelays.add("ws://localhost:4869")
            }
            val finalRelays = targetRelays.distinct()

            com.ryans.nostrshare.utils.SchedulerLog.log(applicationContext, "ScheduledNoteWorker", "Publishing to ${finalRelays.size} relays")

            // Publish
            val results = relayManager.publishEvent(signedJson, finalRelays)
            
            val nonLocalhostSuccess = results.filter { it.key != "ws://localhost:4869" }.any { it.value }
            if (nonLocalhostSuccess) {
                com.ryans.nostrshare.utils.SchedulerLog.log(applicationContext, "ScheduledNoteWorker", "Success! Published to at least one non-localhost relay.")
                // Success! Record status
                if (draftId != -1) {
                    val dao = app.database.draftDao()
                    val draft = dao.getDraftById(draftId)
                    if (draft != null) {
                        val eventId = try { org.json.JSONObject(signedJson).optString("id") } catch (_: Exception) { null }
                        dao.insertDraft(draft.copy(
                            isCompleted = true, 
                            publishError = null, 
                            publishedEventId = eventId, 
                            actualPublishedAt = System.currentTimeMillis(),
                            isOfflineRetry = false
                        ))
                    }
                }
                showNotification(true)
                NotificationHelper.updateScheduledNotification(applicationContext)
                Result.success()
            } else {
                com.ryans.nostrshare.utils.SchedulerLog.log(applicationContext, "ScheduledNoteWorker", "Failed. No non-localhost relays accepted the event.")
                
                // Get draft to check if it's an offline retry
                val dao = app.database.draftDao()
                val draft = if (draftId != -1) dao.getDraftById(draftId) else null
                val isOfflineRetry = draft?.isOfflineRetry == true

                if (isOfflineRetry) {
                    // For offline retries, NEVER give up and move to history. Keep retrying.
                    com.ryans.nostrshare.utils.SchedulerLog.log(applicationContext, "ScheduledNoteWorker", "Offline retry post failed. Keeping in scheduled list.")
                    Result.retry()
                } else if (runAttemptCount < 3) {
                    Result.retry()
                } else {
                    if (draft != null) {
                        dao.insertDraft(draft.copy(isCompleted = true, publishError = "Failed to publish to any relay"))
                    }
                    showNotification(false)
                    NotificationHelper.updateScheduledNotification(applicationContext)
                    Result.failure()
                }
            }
        } catch (e: Exception) {
            com.ryans.nostrshare.utils.SchedulerLog.log(applicationContext, "ScheduledNoteWorker", "Exception during publish: ${e.message}")
            e.printStackTrace()
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                if (draftId != -1) {
                    val dao = app.database.draftDao()
                    val draft = dao.getDraftById(draftId)
                    if (draft != null) {
                        dao.insertDraft(draft.copy(isCompleted = true, publishError = e.message ?: "Unknown error"))
                    }
                }
                showNotification(false)
                NotificationHelper.updateScheduledNotification(applicationContext)
                Result.failure()
            }
        }
    }

    private fun createNotificationChannel() {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        // 1. Delete old channel
        notificationManager.deleteNotificationChannel("scheduled_posts")

        // 2. Create Progress Channel (Silent)
        val progressName = "Active Posting"
        val progressDesc = "Shows status of notes being sent"
        val progressChannel = NotificationChannel(CHANNEL_ID_PROGRESS, progressName, NotificationManager.IMPORTANCE_LOW).apply {
            description = progressDesc
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(progressChannel)

        // 3. Create Alerts Channel (Sound)
        val alertsName = "Scheduled Posts"
        val alertsDesc = "Notifications for successfully published notes"
        val soundUri = Uri.parse("android.resource://${applicationContext.packageName}/raw/prism_notification")
        
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .build()

        val alertsChannel = NotificationChannel(CHANNEL_ID_ALERTS, alertsName, NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = alertsDesc
            setSound(soundUri, audioAttributes)
        }
        notificationManager.createNotificationChannel(alertsChannel)
    }

    private fun showNotification(success: Boolean) {
        val title = if (success) "Post Published! 🚀" else "Publication Failed ❌"
        val message = if (success) "Your scheduled note was successfully sent." else "There was an error publishing your scheduled note."
        
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                applicationContext,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
        ) {
            val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID_ALERTS)
                .setSmallIcon(com.ryans.nostrshare.R.drawable.ic_notification_prism)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)

            NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID + 1, builder.build())
        }
    }
}
