package com.zenread.feature.reader

enum class TapAction { PREV, NEXT, TOGGLE_CHROME }

/**
 * Left third turns back, right third turns forward, the middle toggles the menu.
 * While the menu is open, any tap just closes it.
 */
fun tapAction(x: Float, chromeVisible: Boolean): TapAction = when {
    chromeVisible -> TapAction.TOGGLE_CHROME
    x < 0.3f -> TapAction.PREV
    x > 0.7f -> TapAction.NEXT
    else -> TapAction.TOGGLE_CHROME
}
