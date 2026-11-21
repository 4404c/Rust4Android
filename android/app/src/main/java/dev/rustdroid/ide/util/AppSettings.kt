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
    private const val KEY_LIVE_CHECK = "live_check"
    private const val KEY_AUTO_COMPLETE = "auto_complete"

    /** File-type icons in the Files drawer and the editor tabs. On by default. */
    private val _fileIcons = MutableStateFlow(true)
    val fileIcons: StateFlow<Boolean> = _fileIcons.asStateFlow()

    /** Check for errors while typing (cargo check after a pause). On by default. */
    private val _liveCheck = MutableStateFlow(true)
    val liveCheck: StateFlow<Boolean> = _liveCheck.asStateFlow()

    /** Call once at app start (RustDroidApp.onCreate). */
    fun init(context: Context) {
        _fileIcons.value = prefs(context).getBoolean(KEY_FILE_ICONS, true)
        _liveCheck.value = prefs(context).getBoolean(KEY_LIVE_CHECK, true)
        _autoComplete.value = prefs(context).getBoolean(KEY_AUTO_COMPLETE, true)
    }

    /** Code suggestions while typing (the ⓘ toggle in the editor). On by default. */
    private val _autoComplete = MutableStateFlow(true)
    val autoComplete: StateFlow<Boolean> = _autoComplete.asStateFlow()

    fun setAutoComplete(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_COMPLETE, enabled).apply()
        _autoComplete.value = enabled
    }

    fun setLiveCheck(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LIVE_CHECK, enabled).apply()
        _liveCheck.value = enabled
    }

    fun setFileIcons(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_FILE_ICONS, enabled).apply()
        _fileIcons.value = enabled
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
