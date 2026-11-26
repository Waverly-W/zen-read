package com.zenread.reader.engine

import android.annotation.SuppressLint
import android.content.Context
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.WebView

/**
 * WebView whose text-selection action mode has no items, so the system
 * Copy / Select all toolbar never covers the app's own selection menu.
 * Selection handles keep working because the action mode itself stays alive.
 */
@SuppressLint("ViewConstructor")
internal class ReaderWebView(context: Context) : WebView(context) {
    override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? =
        super.startActionMode(callback?.let(::EmptyMenuCallback), type)

    override fun startActionMode(callback: ActionMode.Callback?): ActionMode? =
        super.startActionMode(callback?.let(::EmptyMenuCallback))

    override fun startActionModeForChild(originalView: View?, callback: ActionMode.Callback?, type: Int): ActionMode? =
        super.startActionModeForChild(originalView, callback?.let(::EmptyMenuCallback), type)

    private class EmptyMenuCallback(private val delegate: ActionMode.Callback) : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            delegate.onCreateActionMode(mode, menu)
            menu.clear()
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            delegate.onPrepareActionMode(mode, menu)
            menu.clear()
            return true
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean = false

        override fun onDestroyActionMode(mode: ActionMode) = delegate.onDestroyActionMode(mode)
    }
}
