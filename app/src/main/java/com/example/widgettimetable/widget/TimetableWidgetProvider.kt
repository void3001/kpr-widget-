package com.example.widgettimetable.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import com.example.widgettimetable.MainActivity
import com.example.widgettimetable.R
import com.example.widgettimetable.data.TimetableData
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class TimetableWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_UPDATE_TIMETABLE = "com.example.widgettimetable.ACTION_UPDATE_TIMETABLE"
        private val TIME_FORMATTER_12H = DateTimeFormatter.ofPattern("h:mm a")
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
        scheduleNextUpdate(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action
        if (action == ACTION_UPDATE_TIMETABLE ||
            action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_TIME_CHANGED ||
            action == Intent.ACTION_TIMEZONE_CHANGED ||
            action == Intent.ACTION_USER_PRESENT ||
            action == Intent.ACTION_SCREEN_ON
        ) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, TimetableWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
            if (appWidgetIds != null && appWidgetIds.isNotEmpty()) {
                for (appWidgetId in appWidgetIds) {
                    updateAppWidget(context, appWidgetManager, appWidgetId)
                }
            }
            scheduleNextUpdate(context)
        }
    }

    private fun updateAppWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        val zoneId = ZoneId.of("Asia/Kolkata")
        val now = LocalTime.now(zoneId)
        val today = LocalDate.now(zoneId)
        val dayOfWeek = today.dayOfWeek
        val dayStr = when (dayOfWeek) {
            DayOfWeek.MONDAY -> "Monday"
            DayOfWeek.TUESDAY -> "Tuesday"
            DayOfWeek.WEDNESDAY -> "Wednesday"
            DayOfWeek.THURSDAY -> "Thursday"
            DayOfWeek.FRIDAY -> "Friday"
            DayOfWeek.SATURDAY -> "Saturday"
            else -> "Sunday"
        }

        val slot = TimetableData.getCurrentSlot(now)
        val currentSubject = if (slot != null) TimetableData.getSubjectForSlot(dayStr, slot) else null

        val nextSlot = TimetableData.getNextSlot(now)
        val nextSubject = if (nextSlot != null) TimetableData.getSubjectForSlot(dayStr, nextSlot) else null

        val views = RemoteViews(context.packageName, R.layout.timetable_widget)

        // Set up Click Action to launch MainActivity
        val activityIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(context, 0, activityIntent, pendingIntentFlags)
        views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

        // Update UI Text
        if (dayStr == "Sunday") {
            views.setTextViewText(R.id.widget_current_period_meta, "Sunday")
            views.setTextViewText(R.id.widget_current_subject, "No classes today")
        } else if (slot != null) {
            views.setTextViewText(R.id.widget_current_period_meta, "${slot.name}  •  ${slot.formattedTime}")
            val titleText = if (slot.isBreak) slot.name else currentSubject?.code ?: "Free Hour"
            views.setTextViewText(R.id.widget_current_subject, titleText)
        } else {
            val firstSlot = TimetableData.timeSlots.first()
            if (now.isBefore(firstSlot.startTime)) {
                views.setTextViewText(R.id.widget_current_period_meta, "Before Classes")
                views.setTextViewText(R.id.widget_current_subject, "Starts at ${firstSlot.startTime.format(TIME_FORMATTER_12H)}")
            } else {
                views.setTextViewText(R.id.widget_current_period_meta, "End of Day")
                views.setTextViewText(R.id.widget_current_subject, "No more classes today")
            }
        }

        // Update Next Period Text
        val nextText = if (dayStr == "Sunday") {
            "No classes scheduled"
        } else if (nextSlot != null) {
            val nextCode = if (nextSlot.isBreak) nextSlot.name else nextSubject?.code ?: "Free Hour"
            "Next: $nextCode (${nextSlot.formattedTime})"
        } else {
            "No more classes today"
        }
        views.setTextViewText(R.id.widget_next_period, nextText)

        // Apply changes
        appWidgetManager.updateAppWidget(appWidgetId, views)
    }

    private fun scheduleNextUpdate(context: Context) {
        val zoneId = ZoneId.of("Asia/Kolkata")
        val now = LocalTime.now(zoneId)
        val today = LocalDate.now(zoneId)

        val transitions = mutableListOf<LocalTime>()
        // Add start and end times of all slots
        TimetableData.timeSlots.forEach {
            if (it.startTime.isAfter(now)) {
                transitions.add(it.startTime)
            }
            if (it.endTime.isAfter(now)) {
                transitions.add(it.endTime)
            }
        }

        val nextUpdateTime: LocalDateTime = if (transitions.isNotEmpty()) {
            val nextTime = transitions.minOrNull()!!
            LocalDateTime.of(today, nextTime)
        } else {
            // Schedule for tomorrow morning at 08:00 AM IST
            LocalDateTime.of(today.plusDays(1), LocalTime.of(8, 0))
        }

        val alarmIntent = Intent(context, TimetableWidgetProvider::class.java).apply {
            action = ACTION_UPDATE_TIMETABLE
        }
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getBroadcast(context, 1001, alarmIntent, pendingIntentFlags)

        val triggerAtMillis = nextUpdateTime.atZone(zoneId).toInstant().toEpochMilli()
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            }
        } catch (e: Exception) {
            alarmManager.set(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
        }
    }
}
