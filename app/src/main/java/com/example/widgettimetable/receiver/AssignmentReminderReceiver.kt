package com.example.widgettimetable.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.widgettimetable.MainActivity
import com.example.widgettimetable.R

class AssignmentReminderReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_REMIND_ASSIGNMENT = "com.example.widgettimetable.ACTION_REMIND_ASSIGNMENT"
        const val CHANNEL_ID = "assignment_reminders_channel"
        const val CHANNEL_NAME = "Assignment Due Reminders"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REMIND_ASSIGNMENT) return

        val title = intent.getStringExtra("title") ?: "Assignment Due Soon"
        val subjectCode = intent.getStringExtra("subject_code") ?: ""
        val subjectName = intent.getStringExtra("subject_name") ?: ""
        val priority = intent.getStringExtra("priority") ?: "MEDIUM"
        val assignmentId = intent.getStringExtra("assignment_id") ?: ""

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create Notification Channel for Android 8.0+ (API 26+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifies you when your subject assignments are near deadline"
                enableLights(true)
                enableVibration(true)
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
        val pendingIntent = PendingIntent.getActivity(context, assignmentId.hashCode(), activityIntent, pendingIntentFlags)

        val contentText = if (subjectCode.isNotEmpty()) {
            "[$subjectCode] $subjectName deadline is approaching!"
        } else {
            "Assignment deadline is approaching!"
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("📌 $title ($priority Priority)")
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$contentText\nMake sure to complete and submit on time."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(assignmentId.hashCode(), notification)
    }
}
