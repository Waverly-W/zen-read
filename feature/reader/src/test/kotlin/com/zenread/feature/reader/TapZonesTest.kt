package com.zenread.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class TapZonesTest {
    @Test
    fun `edges turn pages and middle toggles menu`() {
        assertEquals(TapAction.PREV, tapAction(0.1f, chromeVisible = false))
        assertEquals(TapAction.NEXT, tapAction(0.9f, chromeVisible = false))
        assertEquals(TapAction.TOGGLE_CHROME, tapAction(0.5f, chromeVisible = false))
    }

    @Test
    fun `any tap closes an open menu`() {
        assertEquals(TapAction.TOGGLE_CHROME, tapAction(0.05f, chromeVisible = true))
        assertEquals(TapAction.TOGGLE_CHROME, tapAction(0.95f, chromeVisible = true))
    }
}
