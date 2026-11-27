package com.zenread.feature.settings.about

import androidx.annotation.StringRes
import com.zenread.feature.settings.R

/** Licenses that appear on the About pages. Names are legal titles and stay untranslated. */
enum class License(val spdx: String, val title: String, val url: String) {
    GPL_3_0_OR_LATER("GPL-3.0-or-later", "GNU General Public License v3.0 or later", "https://www.gnu.org/licenses/gpl-3.0.html"),
    LGPL_3_0_ONLY("LGPL-3.0-only", "GNU Lesser General Public License v3.0", "https://www.gnu.org/licenses/lgpl-3.0.html"),
    MIT("MIT", "MIT License", "https://opensource.org/license/mit"),
    APACHE_2_0("Apache-2.0", "Apache License 2.0", "https://www.apache.org/licenses/LICENSE-2.0"),
    OFL_1_1("OFL-1.1", "SIL Open Font License 1.1", "https://openfontlicense.org/open-font-license-official-text/"),
    SQLITE_BLESSING("blessing", "Public domain (SQLite blessing)", "https://sqlite.org/copyright.html"),
}

/**
 * Something Zen Read ships or ports. [textAsset] is the full license text inside the APK's assets
 * (merged from every module), or null when only [License.url] is offered.
 */
data class OpenSourceComponent(
    val id: String,
    val name: String,
    val copyright: String,
    @param:StringRes val role: Int,
    val license: License,
    val homepage: String,
    val textAsset: String?,
)

object OpenSourceComponents {
    const val UPSTREAM_URL = "https://github.com/sobranie2406/modureader"

    private const val GPL_TEXT = "licenses/GPL-3.0.txt"
    private const val APACHE_TEXT = "licenses/Apache-2.0.txt"

    /** Zen Read itself; opened from the license row on the About page. */
    val app = OpenSourceComponent(
        id = "zenread",
        name = "Zen Read",
        copyright = "Copyright (c) 2026 Modu contributors",
        role = R.string.about_license_summary,
        license = License.GPL_3_0_OR_LATER,
        homepage = UPSTREAM_URL,
        textAsset = GPL_TEXT,
    )

    /** Code and assets copied or ported into the app. */
    val ported = listOf(
        OpenSourceComponent(
            id = "foliate-js",
            name = "foliate-js",
            copyright = "Copyright (c) 2022 John Factotum",
            role = R.string.licenses_role_foliate,
            license = License.MIT,
            homepage = "https://github.com/johnfactotum/foliate-js",
            textAsset = "licenses/foliate-js-MIT.txt",
        ),
        OpenSourceComponent(
            id = "pdfjs",
            name = "PDF.js",
            copyright = "Copyright Mozilla Foundation",
            role = R.string.licenses_role_pdfjs,
            license = License.APACHE_2_0,
            homepage = "https://github.com/mozilla/pdf.js",
            textAsset = APACHE_TEXT,
        ),
        OpenSourceComponent(
            id = "anx-reader",
            name = "Anx Reader",
            copyright = "Copyright (c) 2025 Anxcye",
            role = R.string.licenses_role_anx,
            license = License.MIT,
            homepage = "https://github.com/anxcye/anx-reader",
            textAsset = "licenses/anx-reader-MIT.txt",
        ),
        OpenSourceComponent(
            id = "readany",
            name = "ReadAny",
            copyright = "Copyright (C) 2024 ReadAny Team",
            role = R.string.licenses_role_readany,
            license = License.GPL_3_0_OR_LATER,
            homepage = "https://github.com/codedogQBY/ReadAny",
            textAsset = GPL_TEXT,
        ),
        OpenSourceComponent(
            id = "edge-tts",
            name = "edge-tts",
            copyright = "Copyright (c) 2025- rany",
            role = R.string.licenses_role_edge_tts,
            license = License.LGPL_3_0_ONLY,
            homepage = "https://github.com/rany2/edge-tts",
            textAsset = "licenses/edge-tts-LGPL-3.0.txt",
        ),
        OpenSourceComponent(
            id = "atkinson-hyperlegible-next",
            name = "Atkinson Hyperlegible Next",
            copyright = "Copyright 2020-2024 The Atkinson Hyperlegible Next Project Authors",
            role = R.string.licenses_role_atkinson,
            license = License.OFL_1_1,
            homepage = "https://github.com/googlefonts/atkinson-hyperlegible-next",
            // Shipped by data:fonts next to the font file.
            textAsset = "fonts/AtkinsonHyperlegibleNext-OFL.txt",
        ),
    )

    /** Libraries linked into the app. */
    val libraries = listOf(
        library("androidx", "Android Jetpack (AndroidX)", "The Android Open Source Project", R.string.licenses_role_androidx, "https://developer.android.com/jetpack/androidx"),
        library("compose", "Jetpack Compose", "The Android Open Source Project", R.string.licenses_role_compose, "https://developer.android.com/compose"),
        library("media3", "AndroidX Media3", "The Android Open Source Project", R.string.licenses_role_media3, "https://github.com/androidx/media"),
        library("kotlin", "Kotlin, kotlinx.coroutines, kotlinx.serialization", "JetBrains s.r.o. and contributors", R.string.licenses_role_kotlin, "https://kotlinlang.org"),
        library("hilt", "Dagger Hilt", "The Dagger Authors", R.string.licenses_role_hilt, "https://dagger.dev/hilt/"),
        library("okhttp", "OkHttp", "Square, Inc.", R.string.licenses_role_okhttp, "https://square.github.io/okhttp/"),
        library("coil", "Coil", "Coil Contributors", R.string.licenses_role_coil, "https://coil-kt.github.io/coil/"),
        library("markdown-renderer", "Multiplatform Markdown Renderer", "Mike Penz", R.string.licenses_role_markdown, "https://github.com/mikepenz/multiplatform-markdown-renderer"),
        OpenSourceComponent(
            id = "sqlite",
            name = "SQLite",
            copyright = "D. Richard Hipp and contributors",
            role = R.string.licenses_role_sqlite,
            license = License.SQLITE_BLESSING,
            homepage = "https://sqlite.org",
            textAsset = null,
        ),
    )

    val all: List<OpenSourceComponent> = listOf(app) + ported + libraries

    fun find(id: String): OpenSourceComponent? = all.firstOrNull { it.id == id }

    private fun library(id: String, name: String, copyright: String, @StringRes role: Int, homepage: String) =
        OpenSourceComponent(id, name, "Copyright $copyright", role, License.APACHE_2_0, homepage, APACHE_TEXT)
}

/**
 * Turns a hard-wrapped plain-text license into paragraphs that the UI can re-wrap to the screen:
 * blank lines separate paragraphs, line breaks inside a paragraph become spaces, and runs of
 * spaces (from centred titles and indents) collapse.
 */
fun reflowLicenseText(text: String): List<String> =
    text.replace("\r\n", "\n")
        .split(Regex("\n[ \t\u000C]*\n"))
        .map { block -> block.lines().joinToString(" ") { it.trim() }.replace(Regex(" {2,}"), " ").trim() }
        .filter { it.isNotEmpty() }
