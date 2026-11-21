package dev.rustdroid.ide.util

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * R4A: user preferences from the Settings screen. Stored in the same
 * SharedPreferences file as [AppLanguage]; exposed as StateFlows so a change
 * in Settings shows up immediately in the editor (no restart).
 */
object AppSettings {

    private const val PREFS = "r4a_prefs"
    private const val KEY_FILE_ICONS = "file_type_icons"

    /** File-type icons in the Files drawer and the editor tabs. On by default. */
    private val _fileIcons = MutableStateFlow(true)
    val fileIcons: StateFlow<Boolean> = _fileIcons.asStateFlow()

    /** Call once at app start (RustDroidApp.onCreate). */
    fun init(context: Context) {
        _fileIcons.value = prefs(context).getBoolean(KEY_FILE_ICONS, true)
    }

    fun setFileIcons(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_FILE_ICONS, enabled).apply()
        _fileIcons.value = enabled
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
