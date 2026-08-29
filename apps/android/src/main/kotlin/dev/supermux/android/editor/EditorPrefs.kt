package dev.supermux.android.editor

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

const val EDITOR_PREFS_NAME = "cmux-editor-settings"

/** Observable editor settings. Settings screens write here; panes read Compose state. */
@Stable
class EditorPrefs(context: Context) {
    private val prefs = context.getSharedPreferences(EDITOR_PREFS_NAME, Context.MODE_PRIVATE)

    var lineWrap by mutableStateOf(prefs.getBoolean("lineWrap", true))
        private set
    var fontSize by mutableIntStateOf(prefs.getInt("fontSize", 13))
        private set

    fun persistLineWrap(value: Boolean) {
        lineWrap = value
        prefs.edit().putBoolean("lineWrap", value).apply()
    }

    fun persistFontSize(px: Int) {
        val next = px.coerceIn(10, 24)
        fontSize = next
        prefs.edit().putInt("fontSize", next).apply()
    }
}
