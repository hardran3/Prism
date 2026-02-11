package com.ryans.nostrshare.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.ryans.nostrshare.workers.ScheduledNoteWorker

class ScheduleReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_PUBLISH_SCHEDULED = "com.ryans.nostrshare.PUBLISH_SCHEDULED"
        const val EXTRA_DRAFT_ID = "draft_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_PUBLISH_SCHEDULED) {
            val draftId = intent.getIntExtra(EXTRA_DRAFT_ID, -1)
            if (draftId != -1) {
                val signedJson = intent.getStringExtra("signed_json")
                val pubkey = intent.getStringExtra("pubkey")
                
                if (signedJson != null && pubkey != null) {
                    com.ryans.nostrshare.utils.SchedulerLog.log(context, "ScheduleReceiver", "Alarm fired for draft $draftId. Enqueuing worker.")
                    
                    // Enqueue Standard Work (No Expedited to prevent crashes)
                    val workRequest = OneTimeWorkRequestBuilder<ScheduledNoteWorker>()
                        .setInputData(workDataOf(
                            "draft_id" to draftId,
                            "signed_json" to signedJson,
                            "pubkey" to pubkey
                        ))
                        .build()
    
                    WorkManager.getInstance(context).enqueue(workRequest)
                } else {
                     com.ryans.nostrshare.utils.SchedulerLog.log(context, "ScheduleReceiver", "Alarm fired but data missing for draft $draftId")
                }
            } else {
                 // Debug: Notify that alarm fired but draft ID missing?
            }
        }
    }
}
