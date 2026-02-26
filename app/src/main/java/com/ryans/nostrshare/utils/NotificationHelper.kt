package com.ryans.nostrshare.utils

import android.content.Context
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.ryans.nostrshare.R
import com.ryans.nostrshare.data.DraftDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object NotificationHelper {

    // Functional Channels (Internal IDs)
    const val CHANNEL_ID_SCHEDULE = "prism_scheduler"
    const val CHANNEL_ID_SYNC = "prism_sync"
    const val CHANNEL_ID_PROGRESS = "scheduled_progress"
    const val CHANNEL_ID_ALERTS = "prism_alerts_v2" // Updated to v2 to force sound fix

    const val NOTIFICATION_ID_SCHEDULED_STATUS = 1001
    const val NOTIFICATION_ID_SYNC = 1002
    const val NOTIFICATION_ID_PROGRESS = 1003
    const val SUMMARY_ID = 1000
    const val GROUP_KEY_SCHEDULED = "com.ryans.nostrshare.SCHEDULED_NOTES"

    fun createNotificationChannel(context: Context) {
        val notificationManager: NotificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // 1. Spring Cleaning: Delete old, messy channel IDs to clean up system settings
        val oldChannelIds = listOf("scheduled_posts", "scheduled_alerts_v2", "scheduled_alerts_v3", "prism_alerts_v1")
        oldChannelIds.forEach { id ->
            notificationManager.deleteNotificationChannel(id)
        }

        // 2. Scheduler Status Channel (Silent)
        val schedulerChannel = NotificationChannel(
            CHANNEL_ID_SCHEDULE, 
            "Scheduler Status", 
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows pending scheduled notes count"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(schedulerChannel)

        // 3. Sync Progress Channel (Silent)
        val syncChannel = NotificationChannel(
            CHANNEL_ID_SYNC, 
            "History Sync", 
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Progress of Nostr history synchronization"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(syncChannel)

        // 4. Active Posting Channel (Silent - Required for Foreground Service)
        val progressChannel = NotificationChannel(
            CHANNEL_ID_PROGRESS, 
            "Active Posting", 
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows status of notes currently being sent"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(progressChannel)

        // 5. Alerts Channel (SOUND - Fixed URI using direct resource ID)
        val soundUri = android.net.Uri.parse("android.resource://" + context.packageName + "/" + com.ryans.nostrshare.R.raw.prism_notification)
        val audioAttributes = android.media.AudioAttributes.Builder()
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
            .build()

        val alertsChannel = NotificationChannel(
            CHANNEL_ID_ALERTS, 
            "Alerts", 
            NotificationManager.IMPORTANCE_HIGH // High importance forces sound and heads-up
        ).apply {
            description = "Notifications for successfully published notes"
            setSound(soundUri, audioAttributes)
            enableVibration(true)
        }
        notificationManager.createNotificationChannel(alertsChannel)
    }

    fun showSyncProgressNotification(context: Context, relayUrl: String, current: Int, total: Int, isCompleted: Boolean = false) {
        val notificationManager = NotificationManagerCompat.from(context)
        
        if (isCompleted) {
            notificationManager.cancel(NOTIFICATION_ID_SYNC)
            return
        }

        if (androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        ) {
            return
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID_SYNC)
            .setSmallIcon(R.drawable.ic_notification_prism)
            .setContentTitle("Syncing History")
            .setContentText("Relay: $relayUrl")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(total, current, false)

        notificationManager.notify(NOTIFICATION_ID_SYNC, builder.build())
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
                            
                            val profile = userCache[pk]
                            val username = profile?.name ?: pk.take(8)
                            val noteText = if (group.count == 1) "1 note" else "${group.count} notes"
                            
                            val intent = android.content.Intent(context, com.ryans.nostrshare.MainActivity::class.java).apply {
                                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                                putExtra("SELECT_PUBKEY", pk)
                                putExtra("OPEN_TAB", 1) // 1 = Scheduled Tab
                            }
                            val pendingIntent = android.app.PendingIntent.getActivity(
                                context,
                                hash,
                                intent,
                                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                            )

                            // Load Avatar Bitmap asynchronously
                            val avatarBitmap = profile?.pictureUrl?.let { url ->
                                try {
                                    val loader = com.ryans.nostrshare.NostrShareApp.getInstance().avatarImageLoader
                                    val request = ImageRequest.Builder(context)
                                        .data(url)
                                        .allowHardware(false) // Required for getting bitmap
                                        .build()
                                    val result = (loader.execute(request) as? SuccessResult)?.drawable
                                    (result as? android.graphics.drawable.BitmapDrawable)?.bitmap?.let { 
                                        getCircularBitmap(it)
                                    }
                                } catch (e: Exception) {
                                    null
                                }
                            }

                            val individualBuilder = NotificationCompat.Builder(context, CHANNEL_ID_SCHEDULE)
                                .setSmallIcon(R.drawable.ic_notification_prism)
                                .setContentTitle(username)
                                .setContentText("$noteText scheduled")
                                .setLargeIcon(avatarBitmap) // Show user avatar
                                .setPriority(NotificationCompat.PRIORITY_LOW)
                                .setGroup(GROUP_KEY_SCHEDULED)
                                .setContentIntent(pendingIntent) 
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

    private fun getCircularBitmap(bitmap: Bitmap): Bitmap {
        val size = Math.min(bitmap.width, bitmap.height)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint()
        val rect = Rect(0, 0, size, size)

        paint.isAntiAlias = true
        canvas.drawARGB(0, 0, 0, 0)
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(bitmap, rect, rect, paint)
        return output
    }
}
