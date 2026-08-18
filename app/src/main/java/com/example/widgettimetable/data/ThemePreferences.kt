package com.example.widgettimetable.data

import android.content.Context
import android.content.SharedPreferences
import com.example.widgettimetable.theme.ThemeMode

class ThemePreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("app_theme_prefs", Context.MODE_PRIVATE)

    var themeMode: ThemeMode
        get() {
            val name = prefs.getString("theme_mode", ThemeMode.MAJESTIC_GREY.name)
            return try {
                ThemeMode.valueOf(name ?: ThemeMode.MAJESTIC_GREY.name)
            } catch (e: Exception) {
                ThemeMode.MAJESTIC_GREY
            }
        }
        set(value) {
            prefs.edit().putString("theme_mode", value.name).apply()
        }

    var notificationsEnabled: Boolean
        get() = prefs.getBoolean("notifications_enabled", true)
        set(value) {
            prefs.edit().putBoolean("notifications_enabled", value).apply()
        }
}
