package com.vlatal.flashcards.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** App settings (stored locally in SharedPreferences). */
class Settings private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    data class Values(
        val newPerDay: Int,
        val dailyGoal: Int,
        val reminderEnabled: Boolean,
        val reminderMinutes: Int, // minutes after midnight
        val autoBackup: Boolean,
        val lastAutoBackup: Long,
        val language: String, // "" = system, otherwise a language tag: en, uk, ru, sk
        val theme: String,    // system / light / dark
    )

    private val _values = MutableStateFlow(read())
    val values: StateFlow<Values> = _values
    val current get() = _values.value

    private fun read() = Values(
        newPerDay = prefs.getInt("newPerDay", 10),
        dailyGoal = prefs.getInt("dailyGoal", 20),
        reminderEnabled = prefs.getBoolean("reminderEnabled", false),
        reminderMinutes = prefs.getInt("reminderMinutes", 19 * 60),
        autoBackup = prefs.getBoolean("autoBackup", true),
        lastAutoBackup = prefs.getLong("lastAutoBackup", 0),
        language = prefs.getString("language", "") ?: "",
        theme = prefs.getString("theme", "system") ?: "system",
    )

    fun update(block: Values.() -> Values) {
        val v = current.block()
        prefs.edit()
            .putInt("newPerDay", v.newPerDay)
            .putInt("dailyGoal", v.dailyGoal)
            .putBoolean("reminderEnabled", v.reminderEnabled)
            .putInt("reminderMinutes", v.reminderMinutes)
            .putBoolean("autoBackup", v.autoBackup)
            .putLong("lastAutoBackup", v.lastAutoBackup)
            .putString("language", v.language)
            .putString("theme", v.theme)
            .apply()
        _values.value = v
    }

    companion object {
        @Volatile private var instance: Settings? = null
        fun get(context: Context): Settings =
            instance ?: synchronized(this) { instance ?: Settings(context).also { instance = it } }
    }
}
