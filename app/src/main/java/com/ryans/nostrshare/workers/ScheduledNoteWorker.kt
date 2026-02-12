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

class ScheduledNoteWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val CHANNEL_ID = "scheduled_posts"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        com.ryans.nostrshare.utils.SchedulerLog.log(applicationContext, "ScheduledNoteWorker", "Worker started. ID: $id")
        val signedJson = inputData.getString("signed_json")
        if (signedJson == null) {
            com.ryans.nostrshare.utils.SchedulerLog.log(applicationContext, "ScheduledNoteWorker", "Error: signed_json is null")
            return@withContext Result.failure()
        }
        val pubkey = inputData.getString("pubkey")
        if (pubkey == null) {
             com.ryans.nostrshare.utils.SchedulerLog.log(applicationContext, "ScheduledNoteWorker", "Error: pubkey is null")
             return@withContext Result.failure()
        }
        val draftId = inputData.getInt("draft_id", -1)
        com.ryans.nostrshare.utils.SchedulerLog.log(applicationContext, "ScheduledNoteWorker", "Processing draft $draftId")

        val app = applicationContext as NostrShareApp
        val settingsRepository = SettingsRepository(app)
        val relayManager = RelayManager(app.client, settingsRepository)

        createNotificationChannel()
        NotificationHelper.createNotificationChannel(applicationContext)

        // Show standard progress notification (not foreground service)
        showProgressNotification()

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
            
            if (results.any { it.value }) {
                com.ryans.nostrshare.utils.SchedulerLog.log(applicationContext, "ScheduledNoteWorker", "Success! Published to at least one relay.")
                // Success! Record status
                if (draftId != -1) {
                    val dao = app.database.draftDao()
                    val draft = dao.getDraftById(draftId)
                    if (draft != null) {
                        val eventId = try { org.json.JSONObject(signedJson).optString("id") } catch (_: Exception) { null }
                        dao.insertDraft(draft.copy(isCompleted = true, publishError = null, publishedEventId = eventId))
                    }
                }
                clearProgressNotification()
                showNotification(true)
                NotificationHelper.updateScheduledNotification(applicationContext)
                Result.success()
            } else {
                com.ryans.nostrshare.utils.SchedulerLog.log(applicationContext, "ScheduledNoteWorker", "Failed. No relays accepted the event.")
                // No relay accepted it. Retry if under the limit?
                if (runAttemptCount < 3) {
                    clearProgressNotification()
                    Result.retry()
                } else {
                    if (draftId != -1) {
                        val dao = app.database.draftDao()
                        val draft = dao.getDraftById(draftId)
                        if (draft != null) {
                            dao.insertDraft(draft.copy(isCompleted = true, publishError = "Failed to publish to any relay"))
                        }
                    }
                    clearProgressNotification()
                    showNotification(false)
                    NotificationHelper.updateScheduledNotification(applicationContext)
                    Result.failure()
                }
            }
        } catch (e: Exception) {
            com.ryans.nostrshare.utils.SchedulerLog.log(applicationContext, "ScheduledNoteWorker", "Exception during publish: ${e.message}")
            e.printStackTrace()
            clearProgressNotification()
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
        val name = "Scheduled Posts"
        val descriptionText = "Notifications for scheduled Nostr posts"
        val importance = NotificationManager.IMPORTANCE_DEFAULT
        val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
            description = descriptionText
        }
        val notificationManager: NotificationManager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun showNotification(success: Boolean) {
        val title = if (success) "Post Published! 🚀" else "Publication Failed ❌"
        val message = if (success) "Your scheduled Nostr note was successfully broadcast." 
                      else "We couldn't broadcast your scheduled note to any relays."

        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(com.ryans.nostrshare.R.drawable.ic_notification_prism)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        try {
            with(NotificationManagerCompat.from(applicationContext)) {
                if (androidx.core.content.ContextCompat.checkSelfPermission(
                        applicationContext,
                        android.Manifest.permission.POST_NOTIFICATIONS
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    notify(System.currentTimeMillis().toInt(), builder.build())
                }
            }
        } catch (_: Exception) {}
    }

    private fun showProgressNotification() {
        val title = "Publishing Scheduled Note"
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(title)
            .setTicker(title)
            .setContentText("Broadcasting to relays...")
            .setSmallIcon(com.ryans.nostrshare.R.drawable.ic_notification_prism)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        
        try {
            val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(1002, notification)
            com.ryans.nostrshare.utils.SchedulerLog.log(applicationContext, "ScheduledNoteWorker", "Progress notification shown.")
        } catch (e: Exception) {
            com.ryans.nostrshare.utils.SchedulerLog.log(applicationContext, "ScheduledNoteWorker", "Failed to show progress notification: ${e.message}")
        }
    }

    private fun clearProgressNotification() {
        try {
            val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(1002)
        } catch (_: Exception) {}
    }
}
