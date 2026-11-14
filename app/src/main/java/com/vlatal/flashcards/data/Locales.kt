package com.vlatal.flashcards.data

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** In-app language: "" follows the system, otherwise one of [SUPPORTED]. */
object Locales {
    /** Language tag to its native name, shown in the language picker. */
    val SUPPORTED = linkedMapOf(
        "en" to "English",
        "uk" to "Українська",
        "ru" to "Русский",
        "sk" to "Slovenčina",
    )

    fun override(context: Context): Locale? =
        Settings.get(context).current.language.takeIf { it.isNotEmpty() }?.let { Locale.forLanguageTag(it) }

    /** The locale the UI is shown in. */
    fun current(context: Context): Locale =
        override(context) ?: context.resources.configuration.locales[0]

    /** A context whose resources use the chosen app language (for code outside Compose). */
    fun wrap(context: Context): Context {
        val locale = override(context) ?: return context
        return context.createConfigurationContext(withLocale(context.resources.configuration, locale))
    }

    fun withLocale(base: Configuration, locale: Locale): Configuration =
        Configuration(base).apply { setLocale(locale); setLayoutDirection(locale) }
}
