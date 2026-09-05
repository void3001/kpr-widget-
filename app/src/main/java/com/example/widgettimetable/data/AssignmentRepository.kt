package com.example.widgettimetable.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import com.example.widgettimetable.receiver.AssignmentReminderReceiver
import org.json.JSONArray
import org.json.JSONObject

class AssignmentRepository(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("assignments_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "AssignmentRepo"
    }

    fun getAllAssignments(): List<Assignment> {
        val jsonString = prefs.getString("assignments_list", "[]") ?: "[]"
        val list = mutableListOf<Assignment>()
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(Assignment.fromJson(obj))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list.sortedBy { it.dueDateEpochMillis }
    }

    fun getPendingAssignments(): List<Assignment> {
        return getAllAssignments().filter { !it.isCompleted }
    }

    private fun saveAll(list: List<Assignment>) {
        val jsonArray = JSONArray()
        list.forEach { jsonArray.put(it.toJson()) }
        prefs.edit().putString("assignments_list", jsonArray.toString()).apply()
    }

    fun addAssignment(assignment: Assignment) {
        val list = getAllAssignments().toMutableList()
        list.removeAll { it.id == assignment.id }
        list.add(assignment)
        saveAll(list)
        scheduleReminder(assignment)
    }

    fun updateAssignment(assignment: Assignment) {
        addAssignment(assignment)
    }

    fun toggleCompletion(id: String) {
        val list = getAllAssignments().map {
            if (it.id == id) it.copy(isCompleted = !it.isCompleted) else it
        }
        saveAll(list)
        val updated = list.firstOrNull { it.id == id }
        if (updated != null && updated.isCompleted) {
            cancelReminder(id)
        } else if (updated != null) {
            scheduleReminder(updated)
        }
    }

    fun markCompleted(id: String) {
        val list = getAllAssignments().map {
            if (it.id == id) it.copy(isCompleted = true) else it
        }
        saveAll(list)
        cancelReminder(id)
    }

    fun deleteAssignment(id: String) {
        val list = getAllAssignments().toMutableList()
        list.removeAll { it.id == id }
        saveAll(list)
        cancelReminder(id)
    }

    fun rescheduleAllReminders() {
        val pending = getPendingAssignments()
        pending.forEach { scheduleReminder(it) }
    }

    fun scheduleReminder(assignment: Assignment) {
        if (assignment.isCompleted) {
            cancelReminder(assignment.id)
            return
        }

        val now = System.currentTimeMillis()
        if (assignment.dueDateEpochMillis <= now) {
            return // Already past
        }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // 1. Primary Reminder at Due Time
        scheduleSingleAlarm(
            alarmManager = alarmManager,
            assignment = assignment,
            triggerTime = assignment.dueDateEpochMillis,
            requestCode = assignment.id.hashCode(),
            isAdvanceWarning = false
        )

        // 2. Advance Warning (1 hour before due time if sufficiently in the future)
        val advanceTime = assignment.dueDateEpochMillis - (60 * 60 * 1000)
        if (advanceTime > now) {
            scheduleSingleAlarm(
                alarmManager = alarmManager,
                assignment = assignment,
                triggerTime = advanceTime,
                requestCode = assignment.id.hashCode() + 1,
                isAdvanceWarning = true
            )
        }
    }

    private fun scheduleSingleAlarm(
        alarmManager: AlarmManager,
        assignment: Assignment,
        triggerTime: Long,
        requestCode: Int,
        isAdvanceWarning: Boolean
    ) {
        val intent = Intent(context, AssignmentReminderReceiver::class.java).apply {
            action = AssignmentReminderReceiver.ACTION_REMIND_ASSIGNMENT
            putExtra("assignment_id", assignment.id)
            putExtra("title", assignment.title)
            putExtra("subject_code", assignment.subjectCode)
            putExtra("subject_name", assignment.subjectName)
            putExtra("priority", assignment.priority.name)
            putExtra("due_time", assignment.dueDateEpochMillis)
            putExtra("is_advance", isAdvanceWarning)
        }

        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getBroadcast(context, requestCode, intent, pendingIntentFlags)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                } else {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            }
            Log.d(TAG, "Scheduled alarm for ${assignment.title} at $triggerTime")
        } catch (e: Exception) {
            Log.e(TAG, "Failed exact alarm, falling back to setAndAllowWhileIdle", e)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                } else {
                    alarmManager.set(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                }
            } catch (ex: Exception) {
                Log.e(TAG, "Fatal alarm scheduling error", ex)
            }
        }
    }

    fun cancelReminder(id: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AssignmentReminderReceiver::class.java).apply {
            action = AssignmentReminderReceiver.ACTION_REMIND_ASSIGNMENT
        }
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        // Cancel both due time and advance warning
        val p1 = PendingIntent.getBroadcast(context, id.hashCode(), intent, pendingIntentFlags)
        val p2 = PendingIntent.getBroadcast(context, id.hashCode() + 1, intent, pendingIntentFlags)
        alarmManager.cancel(p1)
        alarmManager.cancel(p2)
    }
}
