package com.example.widgettimetable.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.widgettimetable.MainActivity
import com.example.widgettimetable.R

class AssignmentReminderReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_REMIND_ASSIGNMENT = "com.example.widgettimetable.ACTION_REMIND_ASSIGNMENT"
        const val CHANNEL_ID = "assignment_reminders_alert_v5"
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
        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

        // Create Notification Channel with explicit AudioAttributes for Android 8.0+ (API 26+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Delete old channels so new sound and vibration settings apply immediately
            try {
                notificationManager.deleteNotificationChannel("assignment_reminders")
                notificationManager.deleteNotificationChannel("assignment_reminders_sound_v2")
                notificationManager.deleteNotificationChannel("assignment_reminders_sound_v3")
            } catch (e: Exception) {
                // Ignore
            }

            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .build()

            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Heads-up alert notifications for assignment deadlines with sound"
                enableLights(true)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 350, 200, 350)
                setSound(soundUri, audioAttributes)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }

        // Direct ringtone play fallback to guarantee audible alert on all devices & custom OEM ROMs
        try {
            val ringtone = RingtoneManager.getRingtone(context, soundUri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                ringtone?.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            }
            ringtone?.play()
        } catch (e: Exception) {
            e.printStackTrace()
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
            .setSound(soundUri)
            .setVibrate(longArrayOf(0, 350, 200, 350))
            .setDefaults(NotificationCompat.DEFAULT_LIGHTS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(notifId, notification)
    }
}
