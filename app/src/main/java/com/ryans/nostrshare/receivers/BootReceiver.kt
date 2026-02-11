package com.ryans.nostrshare.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ryans.nostrshare.utils.SchedulerUtils

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            com.ryans.nostrshare.utils.SchedulerLog.log(context, "BootReceiver", "Boot detected. Verifying scheduled notes.")
            SchedulerUtils.verifyAllScheduledNotes(context)
        }
    }
}
