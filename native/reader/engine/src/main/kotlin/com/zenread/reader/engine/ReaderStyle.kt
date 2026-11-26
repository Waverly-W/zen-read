package com.zenread.reader.engine

import com.zenread.core.model.ReaderSettings
import com.zenread.core.model.ReadingTheme
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Builds the `style` object foliate-js expects (see upstream `generateUrl`).
 * Keys this app does not expose yet are pinned to upstream defaults.
 *
 * @param topInset/bottomInset CSS px reserved for system bars and reader chrome.
 */
object ReaderStyle {
    fun build(
        settings: ReaderSettings,
        theme: ReadingTheme,
        locale: String,
        topInset: Int = 0,
        bottomInset: Int = 0,
    ): JsonObject = buildJsonObject {
        // Layout and typography the user controls.
        put("fontSize", settings.fontScale)
        put("spacing", settings.lineHeight)
        put("sideMargin", settings.sideMargin)
        put("pageTurnStyle", settings.pageTurn.jsName)
        put("fontColor", theme.text)
        put("backgroundColor", theme.background)
        put("topMargin", 24 + topInset)
        put("bottomMargin", 24 + bottomInset)

        // Fonts: see ReaderFontStyle for the foliate-js name/path contract.
        val body = ReaderFontStyle.body(settings.font)
        val latin = ReaderFontStyle.latin(settings.latinFont)
        put("fontName", body.name)
        put("fontPath", body.path)
        put("englishFontName", latin?.name)
        put("englishFontPath", latin?.path)
        put("fontWeight", settings.fontWeight)

        // Fixed for now.
        put("simulateBold", false)
        put("letterSpacing", 0)
        put("paragraphSpacing", 1.0)
        put("textIndent", 0)
        put("headingFontSize", 1.0)
        put("justify", true)
        put("hyphenate", false)
        put("textAlign", "auto")
        put("writingMode", "auto")
        put("maxColumnCount", 0)
        put("columnThreshold", 720)
        put("useBookStyles", false)

        // Platform behavior.
        put("mobileTouchPaging", true)
        put("mobileImageFit", true)
        put("desktopPageInput", false)
        put("desktopZoomModifier", "ctrl")
        put("keyboardShortcutTurnPage", false)
        put("readerShortcuts", JsonObject(emptyMap()))
        put("tapOnlyPageTurn", false)
        put("longPressSelectParagraph", false)
        put("selectionLocale", locale)
        put("selectionSafeInsets", JsonNull)
        put("scrollPagePercent", 80)
        put("scrollAutoAlign", true)
        put("continuousScroll", true)
        put("eInkMode", false)
        put("eInkDisableAnimations", true)
        put("readingNightMode", false)
        put("verticalPageInsets", JsonNull)
        put("verticalPageChrome", JsonNull)
        put("verticalRedFrame", false)
        put("backgroundImage", "none")
        put("bgimgBlur", 0)
        put("bgimgOpacity", 1)
        put("bgimgFit", "cover")
        put("allowScript", false)
        put("readerScriptEvents", false)
        put("customTextFilters", JsonArray(emptyList()))
        put("customCSS", "")
        put("customHighlightRules", JsonArray(emptyList()))
        put("customCSSEnabled", false)
        put("codeHighlightTheme", "default")
    }
}
