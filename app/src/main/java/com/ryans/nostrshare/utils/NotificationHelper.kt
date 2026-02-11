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

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
    }

    fun updateScheduledNotification(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Get count from DB
                // We need to access DB here. If Dependency Injection was used it would be cleaner, 
                // but we can access via Room builder or if App has a static instance. 
                // Assuming we can build a fresh instance or use a provided one. 
                // For safety/simplicity in this helper, let's look at how the App does it or pass DAO.
                // Re-creating DB instance might be expensive. 
                // Let's assume we can query via a passed function or similar if needed, 
                // but to keep it simple and encapsulated:
                val contextApp = context.applicationContext
                val db = try {
                    (contextApp as? com.ryans.nostrshare.NostrShareApp)?.database
                        ?: com.ryans.nostrshare.data.DraftDatabase.getDatabase(contextApp)
                } catch (e: Exception) {
                    com.ryans.nostrshare.data.DraftDatabase.getDatabase(contextApp)
                }
                
                val count = db.draftDao().getScheduledCount() // We need to add this method to DAO
                
                with(NotificationManagerCompat.from(context)) {
                    if (count > 0) {
                        // Create persistent notification
                        if (androidx.core.content.ContextCompat.checkSelfPermission(
                                context,
                                android.Manifest.permission.POST_NOTIFICATIONS
                            ) == android.content.pm.PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                        ) {
                            val builder = NotificationCompat.Builder(context, CHANNEL_ID_SCHEDULE)
                                .setSmallIcon(R.drawable.ic_notification_prism)
                                .setContentTitle("Prism Scheduler")
                                .setContentText("$count note(s) scheduled for publication")
                                .setPriority(NotificationCompat.PRIORITY_LOW)
                                .setOngoing(true)
                                .setOnlyAlertOnce(true)

                            notify(NOTIFICATION_ID_SCHEDULED_STATUS, builder.build())
                        }
                    } else {
                        // Cancel
                        cancel(NOTIFICATION_ID_SCHEDULED_STATUS)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
