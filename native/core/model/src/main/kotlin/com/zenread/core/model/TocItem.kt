package com.zenread.core.model

data class TocItem(
    val label: String,
    val href: String,
    /** 1-based nesting depth. */
    val level: Int,
    val startPercentage: Float,
    val children: List<TocItem>,
)

/** Depth-first flattening for list display. */
fun List<TocItem>.flattenToc(): List<TocItem> = flatMap { listOf(it) + it.children.flattenToc() }
