package com.example.widgettimetable.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

enum class ThemeMode(val displayName: String, val subtitle: String) {
    MAJESTIC_GREY("Majestic Grey", "Sleek slate with frosted glass accents"),
    OLED_BLACK("OLED Black", "Deep true black with vibrant high contrast")
}

data class AppColorScheme(
    val isOled: Boolean,
    val background: Color,
    val backgroundGradient: Brush,
    val surface: Color,
    val surfaceVariant: Color,
    val surfaceBorder: Color,
    val activeCardBackground: Color,
    val activeCardBorder: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accentPrimary: Color,
    val accentSecondary: Color,
    val pillActive: Color,
    val pillInactive: Color,
    val pillBorder: Color,
    val dividerColor: Color
)

object TimetableColors {
    val MajesticGrey = AppColorScheme(
        isOled = false,
        background = Color(0xFF0F172A),
        backgroundGradient = Brush.verticalGradient(
            colors = listOf(Color(0xFF0F172A), Color(0xFF1E293B))
        ),
        surface = Color(0xFF1E293B).copy(alpha = 0.90f),
        surfaceVariant = Color(0xFF161E2E),
        surfaceBorder = Color(0x33FFFFFF),
        activeCardBackground = Color(0xFF1E3A8A).copy(alpha = 0.40f),
        activeCardBorder = Color(0xFF3B82F6),
        textPrimary = Color(0xFFF8FAFC),
        textSecondary = Color(0xFF94A3B8),
        textMuted = Color(0xFF64748B),
        accentPrimary = Color(0xFF3B82F6),
        accentSecondary = Color(0xFF10B981),
        pillActive = Color(0xFF3B82F6),
        pillInactive = Color(0xFF1E293B),
        pillBorder = Color(0x33FFFFFF),
        dividerColor = Color(0x1AFFFFFF)
    )

    val OledBlack = AppColorScheme(
        isOled = true,
        background = Color(0xFF000000),
        backgroundGradient = Brush.verticalGradient(
            colors = listOf(Color(0xFF000000), Color(0xFF0A0A0A))
        ),
        surface = Color(0xFF121212),
        surfaceVariant = Color(0xFF1A1A1A),
        surfaceBorder = Color(0xFF2A2A2A),
        activeCardBackground = Color(0xFF0C2444),
        activeCardBorder = Color(0xFF38BDF8),
        textPrimary = Color(0xFFFFFFFF),
        textSecondary = Color(0xFFA3A3A3),
        textMuted = Color(0xFF737373),
        accentPrimary = Color(0xFF38BDF8),
        accentSecondary = Color(0xFF34D399),
        pillActive = Color(0xFF38BDF8),
        pillInactive = Color(0xFF141414),
        pillBorder = Color(0xFF2A2A2A),
        dividerColor = Color(0xFF222222)
    )

    fun forMode(mode: ThemeMode): AppColorScheme {
        return when (mode) {
            ThemeMode.MAJESTIC_GREY -> MajesticGrey
            ThemeMode.OLED_BLACK -> OledBlack
        }
    }
}
