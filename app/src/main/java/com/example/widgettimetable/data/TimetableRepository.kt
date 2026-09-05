package com.example.widgettimetable.data

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import com.example.widgettimetable.widget.TimetableWidgetProvider
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID

data class PeriodItem(
    val id: String = UUID.randomUUID().toString(),
    val slotName: String,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val isBreak: Boolean = false,
    val subjectCode: String = "",
    val subjectName: String = "",
    val faculty: String = ""
) {
    val formattedTime: String
        get() = "${startTime.format(TIME_FORMATTER)} - ${endTime.format(TIME_FORMATTER)}"

    companion object {
        private val TIME_FORMATTER = DateTimeFormatter.ofPattern("h:mm a")

        fun fromJson(json: JSONObject): PeriodItem {
            return PeriodItem(
                id = json.optString("id", UUID.randomUUID().toString()),
                slotName = json.optString("slotName", ""),
                startTime = LocalTime.parse(json.optString("startTime", "08:45")),
                endTime = LocalTime.parse(json.optString("endTime", "09:50")),
                isBreak = json.optBoolean("isBreak", false),
                subjectCode = json.optString("subjectCode", ""),
                subjectName = json.optString("subjectName", ""),
                faculty = json.optString("faculty", "")
            )
        }
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("slotName", slotName)
            put("startTime", startTime.toString())
            put("endTime", endTime.toString())
            put("isBreak", isBreak)
            put("subjectCode", subjectCode)
            put("subjectName", subjectName)
            put("faculty", faculty)
        }
    }
}

class TimetableRepository(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("custom_timetable_prefs", Context.MODE_PRIVATE)

    companion object {
        val DAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
    }

    init {
        // Initialize with default college schedule if not already present
        if (!prefs.contains("timetable_initialized_v1")) {
            initDefaultSchedule()
        }
    }

    private fun initDefaultSchedule() {
        val editor = prefs.edit()
        for (day in DAYS) {
            val items = mutableListOf<PeriodItem>()
            val daySchedule = TimetableData.schedule[day] ?: emptyList()
            var nonBreakIdx = 0

            for (slot in TimetableData.timeSlots) {
                if (slot.isBreak) {
                    items.add(
                        PeriodItem(
                            slotName = slot.name,
                            startTime = slot.startTime,
                            endTime = slot.endTime,
                            isBreak = true
                        )
                    )
                } else {
                    val code = if (nonBreakIdx < daySchedule.size) daySchedule[nonBreakIdx] else ""
                    val subject = if (code.isNotEmpty()) TimetableData.subjects[code] else null
                    items.add(
                        PeriodItem(
                            slotName = slot.name,
                            startTime = slot.startTime,
                            endTime = slot.endTime,
                            isBreak = false,
                            subjectCode = subject?.code ?: code,
                            subjectName = subject?.name ?: (if (code.isNotEmpty()) code else "Free Hour"),
                            faculty = subject?.faculty ?: ""
                        )
                    )
                    nonBreakIdx++
                }
            }

            val array = JSONArray()
            items.forEach { array.put(it.toJson()) }
            editor.putString("day_schedule_$day", array.toString())
        }
        editor.putBoolean("timetable_initialized_v1", true)
        editor.apply()
    }

    fun getDaySchedule(day: String): List<PeriodItem> {
        val jsonString = prefs.getString("day_schedule_$day", null) ?: return emptyList()
        val list = mutableListOf<PeriodItem>()
        try {
            val array = JSONArray(jsonString)
            for (i in 0 until array.length()) {
                list.add(PeriodItem.fromJson(array.getJSONObject(i)))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun saveDaySchedule(day: String, items: List<PeriodItem>) {
        val array = JSONArray()
        items.forEach { array.put(it.toJson()) }
        prefs.edit().putString("day_schedule_$day", array.toString()).apply()
        notifyWidgetUpdate()
    }

    fun addPeriod(day: String, item: PeriodItem, atIndex: Int = -1) {
        val current = getDaySchedule(day).toMutableList()
        if (atIndex in 0..current.size) {
            current.add(atIndex, item)
        } else {
            current.add(item)
        }
        saveDaySchedule(day, current)
    }

    fun updatePeriod(day: String, updatedItem: PeriodItem) {
        val current = getDaySchedule(day).toMutableList()
        val idx = current.indexOfFirst { it.id == updatedItem.id }
        if (idx != -1) {
            current[idx] = updatedItem
            saveDaySchedule(day, current)
        }
    }

    fun deletePeriod(day: String, itemId: String) {
        val current = getDaySchedule(day).toMutableList()
        current.removeAll { it.id == itemId }
        saveDaySchedule(day, current)
    }

    fun reorderPeriods(day: String, fromIndex: Int, toIndex: Int) {
        val current = getDaySchedule(day).toMutableList()
        if (fromIndex in 0 until current.size && toIndex in 0 until current.size) {
            val item = current.removeAt(fromIndex)
            current.add(toIndex, item)
            saveDaySchedule(day, current)
        }
    }

    fun resetToDefaults() {
        initDefaultSchedule()
        notifyWidgetUpdate()
    }

    fun getCurrentSlot(day: String, time: LocalTime): PeriodItem? {
        val list = getDaySchedule(day)
        return list.firstOrNull { !time.isBefore(it.startTime) && time.isBefore(it.endTime) }
    }

    fun getNextSlot(day: String, time: LocalTime): PeriodItem? {
        val list = getDaySchedule(day)
        return list.firstOrNull { it.startTime.isAfter(time) }
    }

    private fun notifyWidgetUpdate() {
        val intent = Intent(context, TimetableWidgetProvider::class.java).apply {
            action = TimetableWidgetProvider.ACTION_UPDATE_TIMETABLE
        }
        context.sendBroadcast(intent)
    }
}
