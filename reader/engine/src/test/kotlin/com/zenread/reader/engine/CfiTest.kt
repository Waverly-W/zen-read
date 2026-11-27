package com.zenread.reader.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class CfiTest {
    @Test
    fun `collapses a range to its start point`() {
        assertEquals("epubcfi(/6/8!/4/14/1:67)", Cfi.collapseToStart("epubcfi(/6/8!/4,/14/1:67,/18/1:73)"))
        assertEquals("epubcfi(/6/8!/4/2)", Cfi.collapseToStart("epubcfi(/6/8!/4,/2,/8/1:50)"))
    }

    @Test
    fun `keeps escaped commas inside text assertions`() {
        assertEquals(
            "epubcfi(/6/4!/4/2/1:3[a^,b])",
            Cfi.collapseToStart("epubcfi(/6/4!/4,/2/1:3[a^,b],/2/1:9)"),
        )
    }

    @Test
    fun `leaves points and non-cfi strings alone`() {
        assertEquals("epubcfi(/6/4!/4/2/1:3)", Cfi.collapseToStart("epubcfi(/6/4!/4/2/1:3)"))
        assertEquals("page:3", Cfi.collapseToStart("page:3"))
    }
}
