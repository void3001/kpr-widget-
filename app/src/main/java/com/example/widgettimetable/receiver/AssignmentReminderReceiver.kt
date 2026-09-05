package com.example.widgettimetable.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.widgettimetable.MainActivity
import com.example.widgettimetable.R

class AssignmentReminderReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_REMIND_ASSIGNMENT = "com.example.widgettimetable.ACTION_REMIND_ASSIGNMENT"
        const val CHANNEL_ID = "assignment_reminders_channel_v2"
        const val CHANNEL_NAME = "Assignment Due Reminders"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REMIND_ASSIGNMENT) return

        val themePreferences = com.example.widgettimetable.data.ThemePreferences(context)
        if (!themePreferences.notificationsEnabled) return

        val title = intent.getStringExtra("title") ?: "Assignment Reminder"
        val subjectCode = intent.getStringExtra("subject_code") ?: ""
        val subjectName = intent.getStringExtra("subject_name") ?: ""
        val priority = intent.getStringExtra("priority") ?: "MEDIUM"
        val assignmentId = intent.getStringExtra("assignment_id") ?: ""
        val isAdvance = intent.getBooleanExtra("is_advance", false)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create Notification Channel for Android 8.0+ (API 26+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Heads-up alert notifications for assignment deadlines"
                enableLights(true)
                enableVibration(true)
                setSound(soundUri, null)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }

        val activityIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_tab", "assignments")
        }
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val notifId = if (isAdvance) assignmentId.hashCode() + 1 else assignmentId.hashCode()
        val pendingIntent = PendingIntent.getActivity(context, notifId, activityIntent, pendingIntentFlags)

        val headerTitle = if (isAdvance) {
            "Reminder: $title ($priority Priority)"
        } else {
            "Due Now: $title ($priority Priority)"
        }

        val contentText = if (subjectCode.isNotEmpty()) {
            "[$subjectCode] $subjectName deadline ${if (isAdvance) "is in 1 hour" else "is due now"}!"
        } else {
            "Assignment deadline ${if (isAdvance) "is in 1 hour" else "is due now"}!"
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(headerTitle)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$contentText\nTap to open assignments and mark complete."))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(notifId, notification)
    }
}
