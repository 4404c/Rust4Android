package dev.rustdroid.ide.util

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * R4A: in-app language choice. Spanish is the primary language: it lives in
 * res/values (the default resources), English in res/values-en. The choice
 * is stored in SharedPreferences and applied by wrapping the base context of
 * the Application and the Activity (works on every API level, no AppCompat).
 * Changing it recreates the activity; already-running services pick it up on
 * the next app start.
 */
object AppLanguage {

    enum class Option(val tag: String) { ES("es"), EN("en") }

    val DEFAULT = Option.ES

    private const val PREFS = "r4a_prefs"
    private const val KEY = "language"

    fun get(context: Context): Option {
        val tag = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)
        return Option.entries.firstOrNull { it.tag == tag } ?: DEFAULT
    }

    fun set(context: Context, option: Option) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, option.tag).apply()
        // The activity picks the language up when it is recreated; ViewModels
        // build messages from the application context, so switch that one in
        // place too (otherwise it would keep the old language until restart).
        val locale = Locale.forLanguageTag(option.tag)
        Locale.setDefault(locale)
        val res = context.applicationContext.resources
        val config = Configuration(res.configuration)
        config.setLocale(locale)
        @Suppress("DEPRECATION")
        res.updateConfiguration(config, res.displayMetrics)
    }

    /** Returns [base] with its resources switched to the chosen language. */
    fun wrap(base: Context): Context {
        val locale = Locale.forLanguageTag(get(base).tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}
