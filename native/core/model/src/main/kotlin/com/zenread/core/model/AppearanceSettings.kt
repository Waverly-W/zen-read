package com.zenread.core.model

/** Light or dark app chrome. */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    ;

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }
}

/**
 * Preset seed colors for devices without dynamic color, or when it is turned off.
 * The schemes themselves are generated from [seed] (Material tonal spot) and live in
 * `core:designsystem`.
 */
enum class ThemeAccent(val seed: Long) {
    TEAL(0xFF197D74),
    BLUE(0xFF3B6EB4),
    VIOLET(0xFF6750A4),
    ROSE(0xFFB5446E),
    AMBER(0xFFB06D1E),
    GREEN(0xFF4E8A3E),
}

/** Settings → Appearance. The app language is not here: the system keeps it (see [AppLanguage]). */
data class AppearanceSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Material You wallpaper colors; only honoured on Android 12+. */
    val dynamicColor: Boolean = true,
    val accent: ThemeAccent = ThemeAccent.TEAL,
    /** Black backgrounds in dark mode, for OLED screens. */
    val pureBlack: Boolean = false,
)

/**
 * In-app language choice. Stored by the platform (per-app language on Android 13+, AppCompat's
 * own store before that) as a BCP 47 tag list; this maps that list to the choices the app offers.
 */
enum class AppLanguage(val tag: String) {
    SYSTEM(""),
    SIMPLIFIED_CHINESE("zh-CN"),
    ENGLISH("en"),
    ;

    companion object {
        /**
         * Maps a comma-separated tag list (as from `LocaleListCompat.toLanguageTags()`) to a choice.
         * Only the first tag counts. Any Chinese tag means [SIMPLIFIED_CHINESE] because the app has a
         * single Chinese translation; a language the app does not ship falls back to [SYSTEM].
         */
        fun fromTags(tags: String?): AppLanguage {
            val first = tags?.split(',')?.firstOrNull()?.trim().orEmpty()
            if (first.isEmpty()) return SYSTEM
            return when (first.substringBefore('-').substringBefore('_').lowercase()) {
                "zh" -> SIMPLIFIED_CHINESE
                "en" -> ENGLISH
                else -> SYSTEM
            }
        }
    }
}
