package com.brunocodex.kotlinproject.activities

import android.os.Bundle
import android.view.View
import android.widget.RadioGroup
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.brunocodex.kotlinproject.R
import com.brunocodex.kotlinproject.utils.AppLanguageManager
import com.brunocodex.kotlinproject.utils.AppLanguageOption

class LanguageSettingsActivity : AppCompatActivity() {

    private lateinit var languageGroup: RadioGroup
    private var syncingSelection = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_language_settings)

        applyWindowInsets()
        bindViews()
        setupInteractions()
        syncSelectedLanguage()
    }

    override fun onResume() {
        super.onResume()
        syncSelectedLanguage()
    }

    private fun applyWindowInsets() {
        val root = findViewById<View>(R.id.main)
        val initialPaddingLeft = root.paddingLeft
        val initialPaddingTop = root.paddingTop
        val initialPaddingRight = root.paddingRight
        val initialPaddingBottom = root.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                initialPaddingLeft + systemBars.left,
                initialPaddingTop + systemBars.top,
                initialPaddingRight + systemBars.right,
                initialPaddingBottom + systemBars.bottom
            )
            insets
        }
    }

    private fun bindViews() {
        languageGroup = findViewById(R.id.languageRadioGroup)
    }

    private fun setupInteractions() {
        findViewById<View>(R.id.btnLanguageBack).setOnClickListener { finish() }
        languageGroup.setOnCheckedChangeListener { _, checkedId ->
            if (syncingSelection) return@setOnCheckedChangeListener

            val selectedOption = when (checkedId) {
                R.id.rbLanguageSystem -> AppLanguageOption.SYSTEM
                R.id.rbLanguagePortuguese -> AppLanguageOption.PORTUGUESE_BRAZIL
                R.id.rbLanguageEnglish -> AppLanguageOption.ENGLISH
                else -> return@setOnCheckedChangeListener
            }

            AppLanguageManager.setSelectedOption(this, selectedOption)
        }
    }

    private fun syncSelectedLanguage() {
        syncingSelection = true
        languageGroup.check(
            when (AppLanguageManager.getSelectedOption(this)) {
                AppLanguageOption.SYSTEM -> R.id.rbLanguageSystem
                AppLanguageOption.PORTUGUESE_BRAZIL -> R.id.rbLanguagePortuguese
                AppLanguageOption.ENGLISH -> R.id.rbLanguageEnglish
            }
        )
        syncingSelection = false
    }
}
