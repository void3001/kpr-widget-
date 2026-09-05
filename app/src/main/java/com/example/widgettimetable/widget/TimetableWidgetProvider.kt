package com.example.widgettimetable.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import com.example.widgettimetable.MainActivity
import com.example.widgettimetable.R
import com.example.widgettimetable.data.Assignment
import com.example.widgettimetable.data.AssignmentRepository
import com.example.widgettimetable.data.PeriodItem
import com.example.widgettimetable.data.Priority
import com.example.widgettimetable.data.TimetableRepository
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class TimetableWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_UPDATE_TIMETABLE = "com.example.widgettimetable.ACTION_UPDATE_TIMETABLE"
        const val ACTION_WIDGET_PREV_PERIOD = "com.example.widgettimetable.ACTION_WIDGET_PREV_PERIOD"
        const val ACTION_WIDGET_NEXT_PERIOD = "com.example.widgettimetable.ACTION_WIDGET_NEXT_PERIOD"
        const val ACTION_WIDGET_TOGGLE_MODE = "com.example.widgettimetable.ACTION_WIDGET_TOGGLE_MODE"
        const val ACTION_WIDGET_PREV_TASK = "com.example.widgettimetable.ACTION_WIDGET_PREV_TASK"
        const val ACTION_WIDGET_NEXT_TASK = "com.example.widgettimetable.ACTION_WIDGET_NEXT_TASK"
        const val ACTION_WIDGET_COMPLETE_TASK = "com.example.widgettimetable.ACTION_WIDGET_COMPLETE_TASK"

        private const val PREFS_NAME = "widget_state_prefs"
        private val TIME_FORMATTER_12H = DateTimeFormatter.ofPattern("h:mm a")
        private val DUE_FORMATTER = DateTimeFormatter.ofPattern("MMM dd, h:mm a")
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
        scheduleNextUpdate(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action ?: return
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val componentName = ComponentName(context, TimetableWidgetProvider::class.java)
        val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName) ?: return

        when (action) {
            ACTION_WIDGET_PREV_PERIOD -> {
                val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    adjustPeriodIndex(context, appWidgetId, -1)
                    updateAppWidget(context, appWidgetManager, appWidgetId)
                }
            }
            ACTION_WIDGET_NEXT_PERIOD -> {
                val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    adjustPeriodIndex(context, appWidgetId, 1)
                    updateAppWidget(context, appWidgetManager, appWidgetId)
                }
            }
            ACTION_WIDGET_TOGGLE_MODE -> {
                val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    val prefs = getPrefs(context)
                    val currentMode = prefs.getString("mode_$appWidgetId", "TIMETABLE") ?: "TIMETABLE"
                    val newMode = if (currentMode == "TIMETABLE") "TASKS" else "TIMETABLE"
                    prefs.edit().putString("mode_$appWidgetId", newMode).apply()
                    updateAppWidget(context, appWidgetManager, appWidgetId)
                }
            }
            ACTION_WIDGET_PREV_TASK -> {
                val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    adjustTaskIndex(context, appWidgetId, -1)
                    updateAppWidget(context, appWidgetManager, appWidgetId)
                }
            }
            ACTION_WIDGET_NEXT_TASK -> {
                val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    adjustTaskIndex(context, appWidgetId, 1)
                    updateAppWidget(context, appWidgetManager, appWidgetId)
                }
            }
            ACTION_WIDGET_COMPLETE_TASK -> {
                val taskId = intent.getStringExtra("task_id")
                if (!taskId.isNullOrEmpty()) {
                    val repo = AssignmentRepository(context)
                    repo.markCompleted(taskId)
                    Toast.makeText(context, "Assignment marked as completed!", Toast.LENGTH_SHORT).show()
                    for (id in appWidgetIds) {
                        updateAppWidget(context, appWidgetManager, id)
                    }
                }
            }
            ACTION_UPDATE_TIMETABLE,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_USER_PRESENT,
            Intent.ACTION_SCREEN_ON -> {
                if (action == Intent.ACTION_BOOT_COMPLETED) {
                    AssignmentRepository(context).rescheduleAllReminders()
                }
                if (action == ACTION_UPDATE_TIMETABLE || action == Intent.ACTION_TIME_CHANGED) {
                    val prefs = getPrefs(context)
                    val editor = prefs.edit()
                    for (id in appWidgetIds) {
                        editor.remove("period_index_$id")
                    }
                    editor.apply()
                }
                for (id in appWidgetIds) {
                    updateAppWidget(context, appWidgetManager, id)
                }
                scheduleNextUpdate(context)
            }
        }
    }

    private fun adjustPeriodIndex(context: Context, appWidgetId: Int, delta: Int) {
        val prefs = getPrefs(context)
        val timetableRepo = TimetableRepository(context)
        val dayStr = getCurrentDayString()
        val schedule = timetableRepo.getDaySchedule(dayStr)
        if (schedule.isEmpty()) return

        val zoneId = ZoneId.of("Asia/Kolkata")
        val now = LocalTime.now(zoneId)
        val isClassesFinished = !now.isBefore(schedule.last().endTime)
        val defaultIdx = if (isClassesFinished) schedule.size else getLiveSlotIndex(schedule)

        // totalSlots includes 0..(schedule.size-1) plus schedule.size ("No more classes")
        val totalSlots = schedule.size + 1
        val currentIndex = prefs.getInt("period_index_$appWidgetId", defaultIdx)
        var newIndex = currentIndex + delta
        if (newIndex < 0) newIndex = totalSlots - 1
        if (newIndex >= totalSlots) newIndex = 0

        prefs.edit().putInt("period_index_$appWidgetId", newIndex).apply()
    }

    private fun adjustTaskIndex(context: Context, appWidgetId: Int, delta: Int) {
        val prefs = getPrefs(context)
        val assignmentRepo = AssignmentRepository(context)
        val pending = assignmentRepo.getPendingAssignments()
        if (pending.isEmpty()) return

        val currentIndex = prefs.getInt("task_index_$appWidgetId", 0)
        var newIndex = currentIndex + delta
        if (newIndex < 0) newIndex = pending.size - 1
        if (newIndex >= pending.size) newIndex = 0

        prefs.edit().putInt("task_index_$appWidgetId", newIndex).apply()
    }

    private fun getLiveSlotIndex(schedule: List<PeriodItem>): Int {
        val zoneId = ZoneId.of("Asia/Kolkata")
        val now = LocalTime.now(zoneId)
        val idx = schedule.indexOfFirst { !now.isBefore(it.startTime) && now.isBefore(it.endTime) }
        if (idx != -1) return idx
        val nextIdx = schedule.indexOfFirst { it.startTime.isAfter(now) }
        if (nextIdx != -1) return nextIdx
        return 0
    }

    private fun getCurrentDayString(): String {
        val zoneId = ZoneId.of("Asia/Kolkata")
        val today = LocalDate.now(zoneId)
        return when (today.dayOfWeek) {
            DayOfWeek.MONDAY -> "Monday"
            DayOfWeek.TUESDAY -> "Tuesday"
            DayOfWeek.WEDNESDAY -> "Wednesday"
            DayOfWeek.THURSDAY -> "Thursday"
            DayOfWeek.FRIDAY -> "Friday"
            DayOfWeek.SATURDAY -> "Saturday"
            else -> "Sunday"
        }
    }

    private fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        val prefs = getPrefs(context)
        val mode = prefs.getString("mode_$appWidgetId", "TIMETABLE") ?: "TIMETABLE"
        val views = RemoteViews(context.packageName, R.layout.timetable_widget)

        val dayStr = getCurrentDayString()
        val zoneId = ZoneId.of("Asia/Kolkata")
        val now = LocalTime.now(zoneId)
        val currentTimeFormatted = now.format(TIME_FORMATTER_12H)

        // Setup Main Container Click to Launch App
        val mainAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_tab", if (mode == "TASKS") "assignments" else "timetable")
        }
        val mainPendingIntent = PendingIntent.getActivity(
            context,
            appWidgetId,
            mainAppIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        )
        views.setOnClickPendingIntent(R.id.widget_period_content, mainPendingIntent)
        views.setOnClickPendingIntent(R.id.widget_task_content, mainPendingIntent)

        // Setup Mode Toggle Click
        val toggleIntent = Intent(context, TimetableWidgetProvider::class.java).apply {
            action = ACTION_WIDGET_TOGGLE_MODE
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        val togglePendingIntent = PendingIntent.getBroadcast(
            context,
            appWidgetId * 10 + 1,
            toggleIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        )
        views.setOnClickPendingIntent(R.id.widget_mode_toggle_btn, togglePendingIntent)

        if (mode == "TIMETABLE") {
            renderTimetableMode(context, views, appWidgetId, dayStr, now, currentTimeFormatted)
        } else {
            renderTasksMode(context, views, appWidgetId, currentTimeFormatted)
        }

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }

    private fun renderTimetableMode(
        context: Context,
        views: RemoteViews,
        appWidgetId: Int,
        dayStr: String,
        now: LocalTime,
        currentTimeFormatted: String
    ) {
        val prefs = getPrefs(context)
        val timetableRepo = TimetableRepository(context)
        val schedule = timetableRepo.getDaySchedule(dayStr)

        views.setViewVisibility(R.id.widget_timetable_container, View.VISIBLE)
        views.setViewVisibility(R.id.widget_tasks_container, View.GONE)
        views.setTextViewText(R.id.widget_mode_toggle_btn, "▼ Tasks")
        views.setTextViewText(R.id.widget_header_title, dayStr)
        views.setViewVisibility(R.id.widget_current_period_meta, View.GONE)
        views.setViewVisibility(R.id.widget_next_period, View.GONE)

        // Period navigation buttons
        val prevIntent = Intent(context, TimetableWidgetProvider::class.java).apply {
            action = ACTION_WIDGET_PREV_PERIOD
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        val prevPending = PendingIntent.getBroadcast(
            context,
            appWidgetId * 10 + 2,
            prevIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        )
        views.setOnClickPendingIntent(R.id.widget_period_prev_btn, prevPending)

        val nextIntent = Intent(context, TimetableWidgetProvider::class.java).apply {
            action = ACTION_WIDGET_NEXT_PERIOD
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        val nextPending = PendingIntent.getBroadcast(
            context,
            appWidgetId * 10 + 3,
            nextIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        )
        views.setOnClickPendingIntent(R.id.widget_period_next_btn, nextPending)

        if (dayStr == "Sunday" || schedule.isEmpty()) {
            views.setViewVisibility(R.id.widget_current_period_meta, View.GONE)
            views.setTextViewText(R.id.widget_current_subject, "No more classes")
            views.setViewVisibility(R.id.widget_current_faculty, View.GONE)
            views.setViewVisibility(R.id.widget_live_badge, View.GONE)
            views.setViewVisibility(R.id.widget_next_period, View.GONE)
            return
        }

        val isClassesFinished = !now.isBefore(schedule.last().endTime)
        val defaultIdx = if (isClassesFinished) schedule.size else getLiveSlotIndex(schedule)
        val storedIndex = prefs.getInt("period_index_$appWidgetId", defaultIdx)
        val selectedIndex = if (storedIndex in 0..schedule.size) storedIndex else defaultIdx

        if (selectedIndex == schedule.size) {
            views.setViewVisibility(R.id.widget_current_period_meta, View.GONE)
            views.setTextViewText(R.id.widget_current_subject, "No more classes")
            views.setViewVisibility(R.id.widget_current_faculty, View.GONE)
            views.setViewVisibility(R.id.widget_live_badge, View.GONE)
            views.setViewVisibility(R.id.widget_next_period, View.GONE)
            return
        }

        val item = schedule[selectedIndex]
        val isLive = !now.isBefore(item.startTime) && now.isBefore(item.endTime)
        views.setViewVisibility(R.id.widget_live_badge, if (isLive) View.VISIBLE else View.GONE)

        // Show period time above each period (e.g. 8:55 AM - 9:50 AM)
        val periodTimeText = "${item.startTime.format(TIME_FORMATTER_12H)} - ${item.endTime.format(TIME_FORMATTER_12H)}"
        views.setTextViewText(R.id.widget_current_period_meta, periodTimeText)
        views.setViewVisibility(R.id.widget_current_period_meta, View.VISIBLE)

        // Show subject name instead of subject code in widget
        val displayName = when {
            item.isBreak -> item.slotName
            item.subjectName.isNotEmpty() -> item.subjectName
            item.subjectCode.isNotEmpty() -> item.subjectCode
            else -> item.slotName
        }
        views.setTextViewText(R.id.widget_current_subject, displayName)
        views.setViewVisibility(R.id.widget_current_faculty, View.GONE)
        views.setViewVisibility(R.id.widget_next_period, View.GONE)
    }

    private fun renderTasksMode(
        context: Context,
        views: RemoteViews,
        appWidgetId: Int,
        currentTimeFormatted: String
    ) {
        val prefs = getPrefs(context)
        val assignmentRepo = AssignmentRepository(context)
        val pending = assignmentRepo.getPendingAssignments()

        views.setViewVisibility(R.id.widget_timetable_container, View.GONE)
        views.setViewVisibility(R.id.widget_tasks_container, View.VISIBLE)
        views.setTextViewText(R.id.widget_mode_toggle_btn, "▲ Timetable")
        views.setTextViewText(R.id.widget_header_title, "Pending Tasks (${pending.size})")
        views.setViewVisibility(R.id.widget_live_badge, View.GONE)
        views.setViewVisibility(R.id.widget_next_period, View.GONE)

        // Hide task arrows if no tasks or only 1 task available
        val showArrows = pending.size > 1
        views.setViewVisibility(R.id.widget_task_prev_btn, if (showArrows) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.widget_task_next_btn, if (showArrows) View.VISIBLE else View.GONE)

        // Task navigation buttons
        val prevIntent = Intent(context, TimetableWidgetProvider::class.java).apply {
            action = ACTION_WIDGET_PREV_TASK
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        val prevPending = PendingIntent.getBroadcast(
            context,
            appWidgetId * 10 + 4,
            prevIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        )
        views.setOnClickPendingIntent(R.id.widget_task_prev_btn, prevPending)

        val nextIntent = Intent(context, TimetableWidgetProvider::class.java).apply {
            action = ACTION_WIDGET_NEXT_TASK
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        val nextPending = PendingIntent.getBroadcast(
            context,
            appWidgetId * 10 + 5,
            nextIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        )
        views.setOnClickPendingIntent(R.id.widget_task_next_btn, nextPending)

        if (pending.isEmpty()) {
            views.setTextViewText(R.id.widget_task_meta, "")
            views.setTextViewText(R.id.widget_task_title, "No pending assignments")
            views.setTextViewText(R.id.widget_task_due, "")
            views.setViewVisibility(R.id.widget_task_meta, View.GONE)
            views.setViewVisibility(R.id.widget_task_due, View.GONE)
            views.setViewVisibility(R.id.widget_task_complete_btn, View.GONE)
            views.setViewVisibility(R.id.widget_next_period, View.GONE)
            return
        }

        views.setViewVisibility(R.id.widget_task_meta, View.VISIBLE)
        views.setViewVisibility(R.id.widget_task_due, View.VISIBLE)
        views.setViewVisibility(R.id.widget_task_complete_btn, View.VISIBLE)
        views.setViewVisibility(R.id.widget_next_period, View.GONE)

        val storedIndex = prefs.getInt("task_index_$appWidgetId", 0)
        val selectedIndex = if (storedIndex in pending.indices) storedIndex else 0
        val task = pending[selectedIndex]

        val prio = task.priority.name.lowercase()
        views.setTextViewText(
            R.id.widget_task_meta,
            "task ${selectedIndex + 1} - $prio"
        )

        views.setTextViewText(R.id.widget_task_title, task.title)

        val dueDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(task.dueDateEpochMillis), ZoneId.of("Asia/Kolkata"))
        val isOverdue = task.dueDateEpochMillis < System.currentTimeMillis()
        val dueText = if (isOverdue) "Overdue: ${dueDateTime.format(DUE_FORMATTER)}" else "Due: ${dueDateTime.format(DUE_FORMATTER)}"
        views.setTextViewText(R.id.widget_task_due, dueText)

        // Setup Complete Task Click Action
        val completeIntent = Intent(context, TimetableWidgetProvider::class.java).apply {
            action = ACTION_WIDGET_COMPLETE_TASK
            putExtra("task_id", task.id)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        val completePending = PendingIntent.getBroadcast(
            context,
            task.id.hashCode(),
            completeIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        )
        views.setOnClickPendingIntent(R.id.widget_task_complete_btn, completePending)
    }

    private fun scheduleNextUpdate(context: Context) {
        val zoneId = ZoneId.of("Asia/Kolkata")
        val now = LocalTime.now(zoneId)
        val today = LocalDate.now(zoneId)
        val dayStr = getCurrentDayString()

        val timetableRepo = TimetableRepository(context)
        val schedule = timetableRepo.getDaySchedule(dayStr)

        val transitions = mutableListOf<LocalTime>()
        schedule.forEach {
            if (it.startTime.isAfter(now)) transitions.add(it.startTime)
            if (it.endTime.isAfter(now)) transitions.add(it.endTime)
        }

        // Calculate next target alarm time: slot boundary OR 15-minute fallback
        val nextTime: LocalTime = if (transitions.isNotEmpty()) {
            val nextSlotTransition = transitions.minOrNull()!!
            val fifteenMinAhead = now.plusMinutes(15)
            if (fifteenMinAhead.isBefore(nextSlotTransition)) fifteenMinAhead else nextSlotTransition
        } else {
            // Tomorrow 8:00 AM IST or 15 mins fallback
            val fifteenMinAhead = now.plusMinutes(15)
            if (now.isBefore(LocalTime.of(8, 0))) LocalTime.of(8, 0) else fifteenMinAhead
        }

        val nextDateTime = if (nextTime.isAfter(now)) {
            LocalDateTime.of(today, nextTime)
        } else {
            LocalDateTime.of(today.plusDays(1), nextTime)
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

        val triggerAtMillis = nextDateTime.atZone(zoneId).toInstant().toEpochMilli()
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                } else {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            }
        } catch (e: Exception) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        }
    }
}
