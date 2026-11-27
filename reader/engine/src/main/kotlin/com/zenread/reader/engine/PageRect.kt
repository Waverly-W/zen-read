package com.zenread.reader.engine

/** A rectangle in the reader page, each edge normalized to 0..1 of the page size. */
data class PageRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    companion object {
        val CENTER = PageRect(0.5f, 0.5f, 0.5f, 0.5f)
    }
}
