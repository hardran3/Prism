package com.ryans.nostrshare.utils

import android.content.Context
import com.ryans.nostrshare.data.Draft
import com.ryans.nostrshare.data.DraftDatabase
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
                    if (draft.signedJson != null && draft.scheduledAt != null) {
                        enqueueScheduledWork(appContext, draft)
                    }
                }
                NotificationHelper.updateScheduledNotification(appContext)
            } catch (e: Exception) {
                SchedulerLog.log(appContext, "SchedulerUtils", "Verification failed: ${e.message}")
            }
        }
    }

    fun enqueueScheduledWork(context: Context, draft: Draft) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        
        val intent = android.content.Intent(context, com.ryans.nostrshare.receivers.ScheduleReceiver::class.java).apply {
            action = com.ryans.nostrshare.receivers.ScheduleReceiver.ACTION_PUBLISH_SCHEDULED
            putExtra(com.ryans.nostrshare.receivers.ScheduleReceiver.EXTRA_DRAFT_ID, draft.id)
            putExtra("signed_json", draft.signedJson)
            putExtra("pubkey", draft.pubkey)
        }
        
        val pendingIntent = android.app.PendingIntent.getBroadcast(
            context,
            draft.id,
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val scheduledAt = draft.scheduledAt ?: return

        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, scheduledAt, pendingIntent)
                } else {
                    alarmManager.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, scheduledAt, pendingIntent)
                }
            } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, scheduledAt, pendingIntent)
            } else {
                alarmManager.setExact(android.app.AlarmManager.RTC_WAKEUP, scheduledAt, pendingIntent)
            }
            SchedulerLog.log(context, "SchedulerUtils", "Enqueued draft ${draft.id} for $scheduledAt")
        } catch (e: Exception) {
            alarmManager.set(android.app.AlarmManager.RTC_WAKEUP, scheduledAt, pendingIntent)
            SchedulerLog.log(context, "SchedulerUtils", "Fallback enqueued draft ${draft.id}: ${e.message}")
        }
        
        // Update notification immediately after enqueuing
        NotificationHelper.updateScheduledNotification(context)
    }
}
