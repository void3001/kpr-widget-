package com.example.widgettimetable

import android.app.Application
import java.util.TimeZone

class WidgetTimetableApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Force the default time zone to India (Asia/Kolkata) across the application process.
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
    }
}
