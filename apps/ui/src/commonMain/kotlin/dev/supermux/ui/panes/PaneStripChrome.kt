package dev.supermux.ui.panes

import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier

/**
 * Platform window chrome for one tab strip.
 *
 * [strip] covers the whole strip; [tabs] covers the tabs + "+" row and is excluded from the
 * [strip] surface. `key` must be unique and stable per registered element.
 */
@Stable
interface PaneStripChrome {
    /** Applied to the whole strip. */
    fun strip(key: String): Modifier

    /** Applied to the tabs + "+" row; excluded from the [strip] surface. */
    fun tabs(key: String): Modifier

    /** No chrome: the default for tests, previews, and any host that supplies none. */
    object None : PaneStripChrome {
        override fun strip(key: String): Modifier = Modifier
        override fun tabs(key: String): Modifier = Modifier
    }
}
