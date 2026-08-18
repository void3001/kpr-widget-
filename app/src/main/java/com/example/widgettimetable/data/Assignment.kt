package com.example.widgettimetable.data

import org.json.JSONObject
import java.util.UUID

enum class Priority(val label: String, val colorHex: Long, val badgeColor: Long) {
    HIGH("High", 0xFFEF4444, 0x33EF4444),
    MEDIUM("Medium", 0xFFF59E0B, 0x33F59E0B),
    LOW("Low", 0xFF10B981, 0x3310B981)
}

data class Assignment(
    val id: String = UUID.randomUUID().toString(),
    val subjectCode: String,
    val subjectName: String,
    val title: String,
    val description: String = "",
    val dueDateEpochMillis: Long,
    val priority: Priority = Priority.MEDIUM,
    val isCompleted: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("id", id)
        json.put("subjectCode", subjectCode)
        json.put("subjectName", subjectName)
        json.put("title", title)
        json.put("description", description)
        json.put("dueDateEpochMillis", dueDateEpochMillis)
        json.put("priority", priority.name)
        json.put("isCompleted", isCompleted)
        json.put("createdAt", createdAt)
        return json
    }

    companion object {
        fun fromJson(json: JSONObject): Assignment {
            return Assignment(
                id = json.optString("id", UUID.randomUUID().toString()),
                subjectCode = json.optString("subjectCode", ""),
                subjectName = json.optString("subjectName", ""),
                title = json.optString("title", "Assignment"),
                description = json.optString("description", ""),
                dueDateEpochMillis = json.optLong("dueDateEpochMillis", System.currentTimeMillis()),
                priority = try {
                    Priority.valueOf(json.optString("priority", Priority.MEDIUM.name))
                } catch (e: Exception) {
                    Priority.MEDIUM
                },
                isCompleted = json.optBoolean("isCompleted", false),
                createdAt = json.optLong("createdAt", System.currentTimeMillis())
            )
        }
    }
}
