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

class ScheduledNoteWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val CHANNEL_ID = "scheduled_posts"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val signedJson = inputData.getString("signed_json") ?: return@withContext Result.failure()
        val pubkey = inputData.getString("pubkey") ?: return@withContext Result.failure()
        val draftId = inputData.getInt("draft_id", -1)

        val app = applicationContext as NostrShareApp
        val settingsRepository = SettingsRepository(app)
        val relayManager = RelayManager(app.client, settingsRepository)

        createNotificationChannel()

        try {
            // Find relays for the user
            val relays = try {
                val fetched = relayManager.fetchRelayList(pubkey)
                if (fetched.isEmpty()) listOf("wss://relay.damus.io", "wss://nos.lol") else fetched
            } catch (_: Exception) {
                listOf("wss://relay.damus.io", "wss://nos.lol")
            }

            // Publish
            val results = relayManager.publishEvent(signedJson, relays)
            
            if (results.any { it.value }) {
                // Success! Record status
                if (draftId != -1) {
                    val dao = app.database.draftDao()
                    val draft = dao.getDraftById(draftId)
                    if (draft != null) {
                        dao.insertDraft(draft.copy(isCompleted = true, publishError = null))
                    }
                }
                showNotification(true)
                Result.success()
            } else {
                // No relay accepted it. Retry if under the limit?
                if (runAttemptCount < 3) {
                    Result.retry()
                } else {
                    if (draftId != -1) {
                        val dao = app.database.draftDao()
                        val draft = dao.getDraftById(draftId)
                        if (draft != null) {
                            dao.insertDraft(draft.copy(isCompleted = true, publishError = "Failed to publish to any relay"))
                        }
                    }
                    showNotification(false)
                    Result.failure()
                }
            }
        } catch (e: Exception) {
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
                Result.failure()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
    }

    private fun showNotification(success: Boolean) {
        val title = if (success) "Post Published! 🚀" else "Publication Failed ❌"
        val message = if (success) "Your scheduled Nostr note was successfully broadcast." 
                      else "We couldn't broadcast your scheduled note to any relays."

        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(com.ryans.nostrshare.R.drawable.ic_prism) // Assuming ic_prism exists in R
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        try {
            with(NotificationManagerCompat.from(applicationContext)) {
                // Check permission again just in case, though handled by Activity
                if (androidx.core.content.ContextCompat.checkSelfPermission(
                        applicationContext,
                        android.Manifest.permission.POST_NOTIFICATIONS
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    notify(System.currentTimeMillis().toInt(), builder.build())
                }
            }
        } catch (_: Exception) {}
    }
}
