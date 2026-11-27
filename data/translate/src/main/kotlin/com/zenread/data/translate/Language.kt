package com.zenread.data.translate

import java.util.Locale

/**
 * Languages offered as translation targets. Ported from upstream `lib/enums/lang_list.dart`
 * (minus "Auto", which is modelled as a null source, and the two Crimean Tatar variants,
 * which none of the ported services support consistently).
 *
 * [code] is the persisted token and the Google code; services map it with their own
 * `…Code()` functions in [LanguageCodes].
 */
enum class Language(val code: String, val nativeName: String) {
    ENGLISH("en", "English"),
    SIMPLIFIED_CHINESE("zh-CN", "简体中文"),
    TRADITIONAL_CHINESE("zh-TW", "繁體中文"),
    ARABIC("ar", "العربية"),
    BULGARIAN("bg", "Български"),
    CATALAN("ca", "Català"),
    CROATIAN("hr", "Hrvatski"),
    CZECH("cs", "Čeština"),
    DANISH("da", "Dansk"),
    DUTCH("nl", "Nederlands"),
    FINNISH("fi", "Suomi"),
    FRENCH("fr", "Français"),
    GERMAN("de", "Deutsch"),
    GREEK("el", "Ελληνικά"),
    HINDI("hi", "हिन्दी"),
    HUNGARIAN("hu", "Magyar"),
    INDONESIAN("id", "Indonesia"),
    ITALIAN("it", "Italiano"),
    JAPANESE("ja", "日本語"),
    KOREAN("ko", "한국어"),
    MALAY("ms", "Melayu"),
    MALTESE("mt", "Malti"),
    NORWEGIAN("nb", "Norsk Bokmål"),
    POLISH("pl", "Polski"),
    PORTUGUESE("pt", "Português"),
    ROMANIAN("ro", "Română"),
    RUSSIAN("ru", "Русский"),
    SLOVAK("sk", "Slovenčina"),
    SLOVENIAN("sl", "Slovenščina"),
    SPANISH("es", "Español"),
    SWEDISH("sv", "Svenska"),
    TAMIL("ta", "தமிழ்"),
    TELUGU("te", "తెలుగు"),
    THAI("th", "ไทย"),
    TURKISH("tr", "Türkçe"),
    UKRAINIAN("uk", "Українська"),
    VIETNAMESE("vi", "Tiếng Việt"),
    ;

    /** Primary language subtag: both Chinese variants are `zh`. */
    val family: String get() = code.substringBefore('-').lowercase()

    /** Same language, ignoring the script/region variant (simplified vs traditional Chinese). */
    fun sameFamily(other: Language?): Boolean = other != null && other.family == family

    companion object {
        /**
         * Parses any service's language code: `zh-CN`, `zh-Hans`, `ZH-HANT`, `EN-US`, `pt-BR`, `no`, `iw`….
         * Returns null for languages this app does not list.
         */
        fun fromCode(raw: String?): Language? {
            val code = raw?.trim()?.replace('_', '-')?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            entries.firstOrNull { it.code.lowercase() == code }?.let { return it }
            val base = code.substringBefore('-')
            val rest = code.substringAfter('-', "")
            return when (base) {
                "zh" -> if (rest in TRADITIONAL_TAGS) TRADITIONAL_CHINESE else SIMPLIFIED_CHINESE
                "no", "nn", "nb" -> NORWEGIAN
                else -> entries.firstOrNull { it.code == base }
            }
        }

        /** The language matching [locale], falling back to null when it is not offered. */
        fun fromLocale(locale: Locale): Language? {
            if (locale.language == "zh") {
                val traditional = locale.script.equals("Hant", ignoreCase = true) ||
                    (locale.script.isEmpty() && locale.country.uppercase() in setOf("TW", "HK", "MO"))
                return if (traditional) TRADITIONAL_CHINESE else SIMPLIFIED_CHINESE
            }
            return fromCode(locale.language)
        }

        private val TRADITIONAL_TAGS = setOf("tw", "hk", "mo", "hant", "hant-tw", "hant-hk")
    }
}

/** Language codes per service API. Kept together so they can be unit-tested side by side. */
object LanguageCodes {
    /** Google (free web endpoint and Cloud Translation v2). */
    fun google(language: Language): String = when (language) {
        Language.NORWEGIAN -> "no"
        else -> language.code
    }

    /** Microsoft Translator. */
    fun microsoft(language: Language): String = when (language) {
        Language.SIMPLIFIED_CHINESE -> "zh-Hans"
        Language.TRADITIONAL_CHINESE -> "zh-Hant"
        else -> language.code
    }

    /** DeepL `source_lang`: base language only, upper case. */
    fun deepLSource(language: Language): String = language.family.uppercase()

    /**
     * DeepL `target_lang`. English and Portuguese need a regional variant (the bare codes are
     * deprecated); Chinese needs the script to tell simplified from traditional.
     */
    fun deepLTarget(language: Language): String = when (language) {
        Language.ENGLISH -> "EN-US"
        Language.PORTUGUESE -> "PT-BR"
        Language.SIMPLIFIED_CHINESE -> "ZH-HANS"
        Language.TRADITIONAL_CHINESE -> "ZH-HANT"
        else -> language.family.uppercase()
    }
}
