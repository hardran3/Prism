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
                            
                            val profile = userCache[pk]
                            val username = profile?.name ?: pk.take(8)
                            val noteText = if (group.count == 1) "1 note" else "${group.count} notes"
                            
                            // Create Intent to open MainActivity with specific pubkey
                            val intent = android.content.Intent(context, com.ryans.nostrshare.MainActivity::class.java).apply {
                                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                                putExtra("SELECT_PUBKEY", pk)
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
                                    val loader = ImageLoader(context)
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
