package com.zenread.data.translate

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageTest {
    @Test
    fun parsesServiceCodes() {
        assertEquals(Language.SIMPLIFIED_CHINESE, Language.fromCode("zh-CN"))
        assertEquals(Language.SIMPLIFIED_CHINESE, Language.fromCode("zh-Hans"))
        assertEquals(Language.SIMPLIFIED_CHINESE, Language.fromCode("ZH"))
        assertEquals(Language.SIMPLIFIED_CHINESE, Language.fromCode("ZH-HANS"))
        assertEquals(Language.TRADITIONAL_CHINESE, Language.fromCode("zh-Hant"))
        assertEquals(Language.TRADITIONAL_CHINESE, Language.fromCode("zh_TW"))
        assertEquals(Language.TRADITIONAL_CHINESE, Language.fromCode("zh-HK"))
        assertEquals(Language.ENGLISH, Language.fromCode("EN-US"))
        assertEquals(Language.PORTUGUESE, Language.fromCode("pt-BR"))
        assertEquals(Language.NORWEGIAN, Language.fromCode("no"))
        assertEquals(Language.NORWEGIAN, Language.fromCode("NB"))
        assertNull(Language.fromCode("iw"))
        assertNull(Language.fromCode(""))
        assertNull(Language.fromCode(null))
    }

    @Test
    fun followsSystemLocale() {
        assertEquals(Language.SIMPLIFIED_CHINESE, Language.fromLocale(Locale.forLanguageTag("zh-CN")))
        assertEquals(Language.SIMPLIFIED_CHINESE, Language.fromLocale(Locale.forLanguageTag("zh-Hans-HK")))
        assertEquals(Language.TRADITIONAL_CHINESE, Language.fromLocale(Locale.forLanguageTag("zh-TW")))
        assertEquals(Language.TRADITIONAL_CHINESE, Language.fromLocale(Locale.forLanguageTag("zh-Hant")))
        assertEquals(Language.GERMAN, Language.fromLocale(Locale.GERMANY))
        assertNull(Language.fromLocale(Locale.forLanguageTag("sw-KE")))
    }

    @Test
    fun targetDefaultsToSystemThenEnglish() {
        assertEquals(Language.JAPANESE, TranslateSettings().resolvedTarget(Locale.JAPAN))
        assertEquals(Language.ENGLISH, TranslateSettings().resolvedTarget(Locale.forLanguageTag("sw")))
        assertEquals(Language.FRENCH, TranslateSettings(targetLanguage = Language.FRENCH).resolvedTarget(Locale.JAPAN))
    }

    @Test
    fun chineseVariantsShareAFamily() {
        assertTrue(Language.SIMPLIFIED_CHINESE.sameFamily(Language.TRADITIONAL_CHINESE))
        assertFalse(Language.SIMPLIFIED_CHINESE.sameFamily(Language.JAPANESE))
        assertFalse(Language.ENGLISH.sameFamily(null))
    }

    @Test
    fun mapsCodesPerService() {
        assertEquals("zh-CN", LanguageCodes.google(Language.SIMPLIFIED_CHINESE))
        assertEquals("no", LanguageCodes.google(Language.NORWEGIAN))
        assertEquals("zh-Hans", LanguageCodes.microsoft(Language.SIMPLIFIED_CHINESE))
        assertEquals("zh-Hant", LanguageCodes.microsoft(Language.TRADITIONAL_CHINESE))
        assertEquals("nb", LanguageCodes.microsoft(Language.NORWEGIAN))
        assertEquals("ZH", LanguageCodes.deepLSource(Language.TRADITIONAL_CHINESE))
        assertEquals("EN", LanguageCodes.deepLSource(Language.ENGLISH))
        assertEquals("EN-US", LanguageCodes.deepLTarget(Language.ENGLISH))
        assertEquals("PT-BR", LanguageCodes.deepLTarget(Language.PORTUGUESE))
        assertEquals("ZH-HANT", LanguageCodes.deepLTarget(Language.TRADITIONAL_CHINESE))
        assertEquals("JA", LanguageCodes.deepLTarget(Language.JAPANESE))
    }

    @Test
    fun everyLanguageRoundTripsThroughItsCode() {
        Language.entries.forEach { assertEquals(it, Language.fromCode(it.code)) }
    }
}

class SelectionTargetsTest {
    @Test
    fun foreignTextGoesToTarget() {
        assertEquals(Language.SIMPLIFIED_CHINESE, SelectionTargets.initial(Language.SIMPLIFIED_CHINESE, Language.SIMPLIFIED_CHINESE, null))
        assertEquals(Language.SIMPLIFIED_CHINESE, SelectionTargets.initial(Language.SIMPLIFIED_CHINESE, null, Language.JAPANESE))
    }

    @Test
    fun textInTargetLanguageGoesToEnglish() {
        assertEquals(Language.ENGLISH, SelectionTargets.initial(Language.SIMPLIFIED_CHINESE, null, Language.SIMPLIFIED_CHINESE))
        // Traditional Chinese text with a simplified-Chinese target: same language family.
        assertEquals(Language.ENGLISH, SelectionTargets.initial(Language.TRADITIONAL_CHINESE, null, Language.SIMPLIFIED_CHINESE))
    }

    @Test
    fun englishTargetFallsBackToSystemLanguage() {
        assertEquals(Language.GERMAN, SelectionTargets.fallback(Language.ENGLISH, Language.GERMAN))
        assertNull(SelectionTargets.fallback(Language.ENGLISH, Language.ENGLISH))
        assertNull(SelectionTargets.fallback(Language.ENGLISH, null))
    }

    @Test
    fun retargetsOnlyAfterDetectingTheTarget() {
        assertEquals(Language.ENGLISH, SelectionTargets.retarget(Language.FRENCH, null, Language.FRENCH, Language.FRENCH))
        assertNull(SelectionTargets.retarget(Language.FRENCH, null, Language.FRENCH, Language.GERMAN))
        assertNull(SelectionTargets.retarget(Language.FRENCH, null, Language.FRENCH, null))
        // Already sent to the fallback: never loop.
        assertNull(SelectionTargets.retarget(Language.FRENCH, null, Language.ENGLISH, Language.FRENCH))
        assertNull(SelectionTargets.retarget(Language.ENGLISH, Language.ENGLISH, Language.ENGLISH, Language.ENGLISH))
    }
}
