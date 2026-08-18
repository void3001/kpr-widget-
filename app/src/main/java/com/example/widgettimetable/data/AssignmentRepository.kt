package com.example.widgettimetable.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import com.example.widgettimetable.receiver.AssignmentReminderReceiver
import org.json.JSONArray
import org.json.JSONObject

class AssignmentRepository(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("assignments_prefs", Context.MODE_PRIVATE)

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
    }

    fun deleteAssignment(id: String) {
        val list = getAllAssignments().toMutableList()
        list.removeAll { it.id == id }
        saveAll(list)
        cancelReminder(id)
    }

    fun scheduleReminder(assignment: Assignment) {
        if (assignment.isCompleted) {
            cancelReminder(assignment.id)
            return
        }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AssignmentReminderReceiver::class.java).apply {
            action = AssignmentReminderReceiver.ACTION_REMIND_ASSIGNMENT
            putExtra("assignment_id", assignment.id)
            putExtra("title", assignment.title)
            putExtra("subject_code", assignment.subjectCode)
            putExtra("subject_name", assignment.subjectName)
            putExtra("priority", assignment.priority.name)
            putExtra("due_time", assignment.dueDateEpochMillis)
        }

        val requestCode = assignment.id.hashCode()
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getBroadcast(context, requestCode, intent, pendingIntentFlags)

        // Reminder 2 hours before due time
        val reminderTime = assignment.dueDateEpochMillis - (2 * 60 * 60 * 1000)
        val triggerTime = if (reminderTime > System.currentTimeMillis()) reminderTime else assignment.dueDateEpochMillis

        if (triggerTime > System.currentTimeMillis()) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                } else {
                    alarmManager.setExact(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                }
            } catch (e: Exception) {
                alarmManager.set(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            }
        }
    }

    fun cancelReminder(id: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AssignmentReminderReceiver::class.java).apply {
            action = AssignmentReminderReceiver.ACTION_REMIND_ASSIGNMENT
        }
        val requestCode = id.hashCode()
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getBroadcast(context, requestCode, intent, pendingIntentFlags)
        alarmManager.cancel(pendingIntent)
    }
}
