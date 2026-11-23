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
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_CODE_THEME = "code_theme"

    /** Colores de las letras en el editor (id de CodeThemes). */
    private val _codeTheme = MutableStateFlow("classic")
    val codeTheme: StateFlow<String> = _codeTheme.asStateFlow()

    fun setCodeTheme(context: Context, id: String) {
        prefs(context).edit().putString(KEY_CODE_THEME, id).apply()
        _codeTheme.value = id
    }

    /** Tema de la app: seguir al sistema, claro u oscuro. */
    enum class ThemeMode { SYSTEM, LIGHT, DARK, CUSTOM }

    /** Tema Personalizado: principal (botones), secundario (tarjetas/barras) y fondo, como ARGB. */
    /** [text] null = automático (blanco o negro según el fondo). */
    data class CustomColors(val primary: Int, val secondary: Int, val background: Int, val text: Int? = null)

    private val DEFAULT_CUSTOM = CustomColors(0xFF9CD634.toInt(), 0xFF1E360C.toInt(), 0xFF000000.toInt())
    private val _customColors = MutableStateFlow(DEFAULT_CUSTOM)
    val customColors: StateFlow<CustomColors> = _customColors.asStateFlow()

    fun setCustomColors(context: Context, colors: CustomColors) {
        prefs(context).edit()
            .putInt("custom_primary", colors.primary)
            .putInt("custom_secondary", colors.secondary)
            .putInt("custom_background", colors.background)
            .apply { if (colors.text == null) remove("custom_text") else putInt("custom_text", colors.text) }
            .apply()
        _customColors.value = colors
    }

    private val _themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    fun setThemeMode(context: Context, mode: ThemeMode) {
        prefs(context).edit().putString(KEY_THEME_MODE, mode.name).apply()
        _themeMode.value = mode
    }

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
        _themeMode.value = prefs(context).getString(KEY_THEME_MODE, null)
            ?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } }
            ?: ThemeMode.SYSTEM
        _codeTheme.value = prefs(context).getString(KEY_CODE_THEME, null) ?: "classic"
        prefs(context).let { p ->
            _customColors.value = CustomColors(
                p.getInt("custom_primary", DEFAULT_CUSTOM.primary),
                p.getInt("custom_secondary", DEFAULT_CUSTOM.secondary),
                p.getInt("custom_background", DEFAULT_CUSTOM.background),
                if (p.contains("custom_text")) p.getInt("custom_text", 0) else null,
            )
        }
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
