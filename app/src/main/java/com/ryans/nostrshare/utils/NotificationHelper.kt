package com.ryans.nostrshare.utils

import android.content.Context
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ryans.nostrshare.R
import com.ryans.nostrshare.data.DraftDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object NotificationHelper {

    const val CHANNEL_ID_SCHEDULE = "prism_scheduler"
    const val NOTIFICATION_ID_SCHEDULED_STATUS = 1001
    const val SUMMARY_ID = 1000
    const val GROUP_KEY_SCHEDULED = "com.ryans.nostrshare.SCHEDULED_NOTES"

    fun createNotificationChannel(context: Context) {
        val name = "Prism Scheduler"
        val descriptionText = "Notifications for pending scheduled posts"
        val importance = NotificationManager.IMPORTANCE_LOW 
        val channel = NotificationChannel(CHANNEL_ID_SCHEDULE, name, importance).apply {
            description = descriptionText
            setShowBadge(false)
        }
        val notificationManager: NotificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    fun updateScheduledNotification(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val contextApp = context.applicationContext
                val db = try {
                    (contextApp as? com.ryans.nostrshare.NostrShareApp)?.database
                        ?: DraftDatabase.getDatabase(contextApp)
                } catch (e: Exception) {
                    DraftDatabase.getDatabase(contextApp)
                }
                
                val groupedCounts = db.draftDao().getScheduledCountByPubkey()
                val allKnownPubkeys = db.draftDao().getAllPubkeys()
                val settings = com.ryans.nostrshare.SettingsRepository(contextApp)
                val userCache = settings.getUsernameCache()
                
                with(NotificationManagerCompat.from(context)) {
                    if (groupedCounts.isEmpty()) {
                        cancel(SUMMARY_ID)
                        allKnownPubkeys.forEach { cancel(it.hashCode()) }
                        return@with
                    }

                    val countsMap = groupedCounts.associate { (it.pubkey ?: "Unknown") to it.count }
                    
                    // Cleanup any users who no longer have notes
                    allKnownPubkeys.forEach { pk ->
                        if (!countsMap.containsKey(pk)) {
                            cancel(pk.hashCode())
                        }
                    }

                    val totalNotes = groupedCounts.sumOf { it.count }
                    val totalUsers = groupedCounts.size

                    if (androidx.core.content.ContextCompat.checkSelfPermission(
                            context,
                            android.Manifest.permission.POST_NOTIFICATIONS
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                    ) {
                        // 1. Individual Notifications
                        groupedCounts.forEach { group ->
                            val pk = group.pubkey ?: "Unknown"
                            val hash = pk.hashCode()
                            
                            val username = userCache[pk]?.name ?: pk.take(8)
                            val noteText = if (group.count == 1) "1 note" else "${group.count} notes"
                            
                            val individualBuilder = NotificationCompat.Builder(context, CHANNEL_ID_SCHEDULE)
                                .setSmallIcon(R.drawable.ic_notification_prism)
                                .setContentTitle(username)
                                .setContentText("$noteText scheduled")
                                .setPriority(NotificationCompat.PRIORITY_LOW)
                                .setGroup(GROUP_KEY_SCHEDULED)
                                .setSilent(true) 
                                .setOngoing(true)

                            notify(hash, individualBuilder.build())
                        }

                        // 2. Summary Notification
                        val summaryText = if (totalUsers == 1) {
                            "Scheduled notes for 1 account"
                        } else {
                            "$totalNotes notes across $totalUsers accounts"
                        }

                        val summaryBuilder = NotificationCompat.Builder(context, CHANNEL_ID_SCHEDULE)
                            .setSmallIcon(R.drawable.ic_notification_prism)
                            .setContentTitle("Prism Scheduler")
                            .setContentText(summaryText)
                            .setStyle(NotificationCompat.InboxStyle()
                                .setSummaryText("Scheduled Posts"))
                            .setPriority(NotificationCompat.PRIORITY_LOW)
                            .setGroup(GROUP_KEY_SCHEDULED)
                            .setGroupSummary(true)
                            .setOngoing(true)
                            .setSilent(true)

                        notify(SUMMARY_ID, summaryBuilder.build())
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
