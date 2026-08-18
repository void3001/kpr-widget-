package com.example.widgettimetable.data

import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class TimeSlot(
    val name: String,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val isBreak: Boolean = false
) {
    val formattedTime: String
        get() = "${startTime.format(TIME_FORMATTER)} - ${endTime.format(TIME_FORMATTER)}"

    companion object {
        private val TIME_FORMATTER = DateTimeFormatter.ofPattern("h:mm a")
    }
}

data class Subject(
    val code: String,
    val name: String,
    val faculty: String
)

object TimetableData {
    val timeSlots = listOf(
        TimeSlot("Mentor Hour", LocalTime.of(8, 45), LocalTime.of(8, 55)),
        TimeSlot("Period 1", LocalTime.of(8, 55), LocalTime.of(9, 50)),
        TimeSlot("Period 2", LocalTime.of(9, 50), LocalTime.of(10, 45)),
        TimeSlot("Tea Break", LocalTime.of(10, 45), LocalTime.of(11, 5), isBreak = true),
        TimeSlot("Period 3", LocalTime.of(11, 5), LocalTime.of(12, 0)),
        TimeSlot("Period 4", LocalTime.of(12, 0), LocalTime.of(12, 55)),
        TimeSlot("Lunch Break", LocalTime.of(12, 55), LocalTime.of(13, 45), isBreak = true),
        TimeSlot("Period 5", LocalTime.of(13, 45), LocalTime.of(14, 35)),
        TimeSlot("Period 6", LocalTime.of(14, 35), LocalTime.of(15, 25)),
        TimeSlot("Period 7", LocalTime.of(15, 25), LocalTime.of(16, 15))
    )

    val subjects = mapOf(
        "U25CSG16" to Subject("U25CSG16", "Database Management Systems", "Ms. Sri Sakthi A"),
        "U25ADG01" to Subject("U25ADG01", "Digital Principles and Computer Org", "Ms. Kiruthiga K"),
        "U25ITG01" to Subject("U25ITG01", "Software Engineering", "Ms. Divyasree M"),
        "U25CSG11" to Subject("U25CSG11", "Object-Oriented Programming (Java)", "Ms. Velammal M"),
        "U25CSG12" to Subject("U25CSG12", "Data Structures", "Ms. Preetha P / Ms. Sri Sakthi A"),
        "U25MA301" to Subject("U25MA301", "Probability & Stats for Computing", "Dr. Damodharan K"),
        "U25CSG18" to Subject("U25CSG18", "DBMS Lab", "Ms. Sri Sakthi A / Ms. Kiruthiga K"),
        "U25CSG13" to Subject("U25CSG13", "OOP (Java) Lab", "Ms. Velammal M / Ms. Kiruthiga K"),
        "U25OAM01" to Subject("U25OAM01", "AWS Fundamentals", "Ms. Preetha P"),
        "U25MCC08" to Subject("U25MCC08", "Professional Skill Dev - III", "Ms. Sri Sakthi A"),
        "U25MNC02" to Subject("U25MNC02", "Essence of Indian Trad Knowledge", "Mr. Ravi P"),
        "PT" to Subject("PT", "Placement Training", "Ms. Sushmitha Raj R"),
        "MH" to Subject("MH", "Mentor Hour", "Ms. Sri Sakthi A / Ms. Preetha P / Mr. Ravi P"),
        "GATE" to Subject("GATE", "GATE Prep", "Ms. Sri Sakthi A / Dr. G. Sangeetha / Ms. Preetha P / Mr. Ravi P"),
        "FH" to Subject("FH", "Free Hour", "N/A"),
        "ACTIVITY" to Subject("ACTIVITY", "Saturday Activity", "Faculty Advisor")
    )

    val schedule = mapOf(
        "Monday" to listOf("MH", "U25ADG01", "U25ITG01", "U25CSG12", "U25CSG12", "U25MA301", "U25MA301", "GATE"),
        "Tuesday" to listOf("MH", "U25CSG18", "U25CSG18", "U25MA301", "U25CSG11", "U25CSG12", "U25CSG12", "GATE"),
        "Wednesday" to listOf("MH", "U25CSG16", "U25CSG11", "U25CSG18", "U25CSG18", "U25ADG01", "PT", "PT"),
        "Thursday" to listOf("MH", "U25CSG13", "U25CSG13", "U25MA301", "U25CSG16", "U25ITG01", "U25CSG12", "MH"),
        "Friday" to listOf("MH", "U25CSG12", "U25CSG16", "U25ADG01", "U25ITG01", "U25CSG11", "GATE", "FH"),
        "Saturday" to listOf("MH", "U25CSG11", "U25CSG12", "U25CSG16", "U25ADG01", "ACTIVITY", "ACTIVITY", "ACTIVITY")
    )

    fun getCurrentSlot(time: LocalTime): TimeSlot? {
        return timeSlots.firstOrNull { !time.isBefore(it.startTime) && time.isBefore(it.endTime) }
    }

    fun getSubjectForSlot(day: String, slot: TimeSlot): Subject? {
        if (slot.isBreak) return null
        val daySchedule = schedule[day] ?: return null
        
        var nonBreakIndex = 0
        for (s in timeSlots) {
            if (s == slot) {
                break
            }
            if (!s.isBreak) {
                nonBreakIndex++
            }
        }
        
        if (nonBreakIndex < daySchedule.size) {
            val code = daySchedule[nonBreakIndex]
            return subjects[code] ?: Subject(code, code, "")
        }
        return null
    }

    fun getNextSlot(time: LocalTime): TimeSlot? {
        return timeSlots.firstOrNull { it.startTime.isAfter(time) }
    }
}
