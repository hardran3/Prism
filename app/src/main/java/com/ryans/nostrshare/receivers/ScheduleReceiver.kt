package com.ryans.nostrshare.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ryans.nostrshare.utils.SchedulerUtils
import com.ryans.nostrshare.utils.SchedulerLog

class ScheduleReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_PUBLISH_SCHEDULED = "com.ryans.nostrshare.PUBLISH_SCHEDULED"
        const val EXTRA_DRAFT_ID = "draft_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val draftId = intent.getIntExtra(EXTRA_DRAFT_ID, -1)
        
        SchedulerLog.log(context, "ScheduleReceiver", "Received broadcast: $action for draft $draftId")
        
        if (ACTION_PUBLISH_SCHEDULED == action && draftId != -1) {
            // Use WorkManager to do the actual work immediately (Expedited)
            SchedulerUtils.enqueueImmediateWork(context, draftId)
        }
    }
}
