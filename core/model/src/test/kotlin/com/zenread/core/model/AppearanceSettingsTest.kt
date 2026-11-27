package com.zenread.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceSettingsTest {
    @Test
    fun themeModeResolvesAgainstSystem() {
        assertTrue(ThemeMode.SYSTEM.isDark(systemDark = true))
        assertFalse(ThemeMode.SYSTEM.isDark(systemDark = false))
        assertFalse(ThemeMode.LIGHT.isDark(systemDark = true))
        assertTrue(ThemeMode.DARK.isDark(systemDark = false))
    }

    @Test
    fun emptyTagsFollowSystem() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTags(null))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTags(""))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTags("  "))
    }

    @Test
    fun chineseVariantsMapToSimplifiedChinese() {
        listOf("zh-CN", "zh", "zh-Hans", "zh-Hans-CN", "zh-TW", "ZH_cn").forEach {
            assertEquals(it, AppLanguage.SIMPLIFIED_CHINESE, AppLanguage.fromTags(it))
        }
    }

    @Test
    fun englishVariantsMapToEnglish() {
        listOf("en", "en-US", "en-GB").forEach {
            assertEquals(it, AppLanguage.ENGLISH, AppLanguage.fromTags(it))
        }
    }

    @Test
    fun onlyTheFirstTagCounts() {
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTags("en-US,zh-CN"))
        assertEquals(AppLanguage.SIMPLIFIED_CHINESE, AppLanguage.fromTags("zh-CN, en"))
    }

    @Test
    fun unsupportedLanguageFallsBackToSystem() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTags("fr-FR"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTags("ja,en"))
    }

    @Test
    fun tagsRoundTrip() {
        AppLanguage.entries.forEach { assertEquals(it, AppLanguage.fromTags(it.tag)) }
    }
}
