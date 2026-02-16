package com.ryans.nostrshare.utils

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.*
import com.ryans.nostrshare.data.Draft
import com.ryans.nostrshare.data.DraftDatabase
import com.ryans.nostrshare.workers.ScheduledNoteWorker
import com.ryans.nostrshare.receivers.ScheduleReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object SchedulerUtils {

    fun verifyAllScheduledNotes(context: Context) {
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = DraftDatabase.getDatabase(appContext)
                val pending = db.draftDao().getScheduledDrafts()
                SchedulerLog.log(appContext, "SchedulerUtils", "Verifying ${pending.size} scheduled notes.")
                pending.forEach { draft ->
                    if (draft.signedJson != null) {
                        if (draft.isOfflineRetry) {
                            enqueueOfflineRetry(appContext, draft)
                        } else if (draft.scheduledAt != null) {
                            // Re-sync alarm for all pending notes
                            enqueueScheduledWork(appContext, draft, forceRefresh = true)
                        }
                    }
                }
                NotificationHelper.updateScheduledNotification(appContext)
            } catch (e: Exception) {
                SchedulerLog.log(appContext, "SchedulerUtils", "Verification failed: ${e.message}")
            }
        }
    }

    fun enqueueOfflineRetry(context: Context, draft: Draft) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<ScheduledNoteWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                java.util.concurrent.TimeUnit.MILLISECONDS
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(workDataOf(
                "draft_id" to draft.id,
                "signed_json" to draft.signedJson,
                "pubkey" to draft.pubkey
            ))
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "offline_retry_${draft.id}",
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
        SchedulerLog.log(context, "SchedulerUtils", "Enqueued unique offline retry for draft ${draft.id}")
        NotificationHelper.updateScheduledNotification(context)
    }

    /**
     * Set a high-precision wake-up alarm.
     */
    fun enqueueScheduledWork(context: Context, draft: Draft, forceRefresh: Boolean = false) {
        val scheduledAt = draft.scheduledAt ?: return
        
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ScheduleReceiver::class.java).apply {
            action = ScheduleReceiver.ACTION_PUBLISH_SCHEDULED
            putExtra(ScheduleReceiver.EXTRA_DRAFT_ID, draft.id)
        }
        
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            draft.id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Set high-precision alarm that triggers even in Doze mode
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    scheduledAt,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    scheduledAt,
                    pendingIntent
                )
            }
            SchedulerLog.log(context, "SchedulerUtils", "Alarm set for draft ${draft.id} at $scheduledAt")
        } catch (e: Exception) {
            SchedulerLog.log(context, "SchedulerUtils", "Failed to set alarm: ${e.message}")
            // Fallback to immediate WorkManager if alarm fails
            if (scheduledAt <= System.currentTimeMillis()) {
                enqueueImmediateWork(context, draft.id)
            }
        }
        
        NotificationHelper.updateScheduledNotification(context)
    }

    /**
     * Triggered by ScheduleReceiver when the alarm goes off.
     * Launches an Expedited WorkManager task (0 delay).
     */
    fun enqueueImmediateWork(context: Context, draftId: Int) {
        val workRequest = OneTimeWorkRequestBuilder<ScheduledNoteWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                java.util.concurrent.TimeUnit.MILLISECONDS
            )
            .setInputData(workDataOf("draft_id" to draftId))
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "scheduled_post_$draftId",
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
        SchedulerLog.log(context, "SchedulerUtils", "Immediate expedited work enqueued for draft $draftId")
    }

    fun cancelScheduledWork(context: Context, draftId: Int) {
        // 1. Cancel AlarmManager
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ScheduleReceiver::class.java).apply {
            action = ScheduleReceiver.ACTION_PUBLISH_SCHEDULED
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            draftId,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }

        // 2. Cancel WorkManager
        WorkManager.getInstance(context).cancelUniqueWork("scheduled_post_$draftId")
        WorkManager.getInstance(context).cancelUniqueWork("offline_retry_$draftId")
        
        SchedulerLog.log(context, "SchedulerUtils", "Cancelled all work/alarms for draft $draftId")
    }
}
