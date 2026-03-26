package com.brunocodex.kotlinproject.utils

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

enum class AppLanguageOption(
    val preferenceValue: String,
    val languageTag: String?
) {
    SYSTEM("system", null),
    PORTUGUESE_BRAZIL("pt-BR", "pt-BR"),
    ENGLISH("en", "en");

    companion object {
        fun fromPreferenceValue(value: String?): AppLanguageOption {
            return entries.firstOrNull { option -> option.preferenceValue == value } ?: SYSTEM
        }
    }
}

object AppLanguageManager {

    private const val PREFS_NAME = "app_prefs"
    private const val KEY_SELECTED_LANGUAGE = "selected_language"

    fun getSelectedOption(context: Context): AppLanguageOption {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val value = prefs.getString(KEY_SELECTED_LANGUAGE, AppLanguageOption.SYSTEM.preferenceValue)
        return AppLanguageOption.fromPreferenceValue(value)
    }

    fun setSelectedOption(context: Context, option: AppLanguageOption) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SELECTED_LANGUAGE, option.preferenceValue)
            .apply()
        applyOption(option)
    }

    fun applyStoredLanguage(context: Context): Boolean {
        return applyOption(getSelectedOption(context))
    }

    private fun applyOption(option: AppLanguageOption): Boolean {
        val targetLocales = buildLocaleList(option)
        val currentLocales = AppCompatDelegate.getApplicationLocales()
        if (currentLocales.toLanguageTags() == targetLocales.toLanguageTags()) return false
        AppCompatDelegate.setApplicationLocales(targetLocales)
        return true
    }

    private fun buildLocaleList(option: AppLanguageOption): LocaleListCompat {
        return option.languageTag
            ?.let { tag -> LocaleListCompat.forLanguageTags(tag) }
            ?: LocaleListCompat.getEmptyLocaleList()
    }
}
