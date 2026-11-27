package com.zenread.feature.settings.about

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenSourceLicensesTest {
    @Test
    fun idsAreUniqueAndFindable() {
        val ids = OpenSourceComponents.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        ids.forEach { assertNotNull(it, OpenSourceComponents.find(it)) }
        assertNull(OpenSourceComponents.find("nope"))
    }

    @Test
    fun linksAreHttps() {
        OpenSourceComponents.all.forEach { assertTrue(it.homepage, it.homepage.startsWith("https://")) }
        License.entries.forEach { assertTrue(it.url, it.url.startsWith("https://")) }
        assertTrue(OpenSourceComponents.UPSTREAM_URL.startsWith("https://"))
    }

    @Test
    fun requiredComponentsAreListed() {
        val byId = OpenSourceComponents.all.associateBy { it.id }
        assertEquals(License.GPL_3_0_OR_LATER, byId.getValue("zenread").license)
        assertEquals(License.MIT, byId.getValue("foliate-js").license)
        assertEquals(License.GPL_3_0_OR_LATER, byId.getValue("readany").license)
        assertEquals(License.LGPL_3_0_ONLY, byId.getValue("edge-tts").license)
        assertEquals(License.OFL_1_1, byId.getValue("atkinson-hyperlegible-next").license)
        listOf("androidx", "compose", "okhttp", "coil", "media3").forEach {
            assertEquals(it, License.APACHE_2_0, byId.getValue(it).license)
        }
    }

    /** Every bundled text must exist in some module's assets (they all merge into the APK). */
    @Test
    fun bundledTextsExist() {
        // Unit tests run with the module directory as working directory.
        val assetRoots = listOf(File("src/main/assets"), File("../../data/fonts/src/main/assets"))
        OpenSourceComponents.all.mapNotNull { it.textAsset }.distinct().forEach { path ->
            assertTrue(path, assetRoots.any { File(it, path).isFile })
        }
    }

    @Test
    fun reflowJoinsHardWrappedLines() {
        val text = "MIT License\n\nCopyright (c) 2022 Someone\n\nPermission is hereby granted,\nfree of charge, to any\r\nperson.\n   \nTHE SOFTWARE\n"
        assertEquals(
            listOf("MIT License", "Copyright (c) 2022 Someone", "Permission is hereby granted, free of charge, to any person.", "THE SOFTWARE"),
            reflowLicenseText(text),
        )
    }

    @Test
    fun reflowCollapsesCentredTitles() {
        val text = "                    GNU GENERAL PUBLIC LICENSE\n                       Version 3, 29 June 2007\n\n\n\n  0. Definitions.\n"
        assertEquals(
            listOf("GNU GENERAL PUBLIC LICENSE Version 3, 29 June 2007", "0. Definitions."),
            reflowLicenseText(text),
        )
    }

    @Test
    fun reflowOfEmptyTextIsEmpty() {
        assertEquals(emptyList<String>(), reflowLicenseText(""))
        assertEquals(emptyList<String>(), reflowLicenseText("\n\n  \n"))
    }
}
