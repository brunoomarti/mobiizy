package com.brunocodex.kotlinproject.utils

import android.util.Patterns
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import com.google.android.material.textfield.TextInputLayout
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.core.content.ContextCompat

object StepValidationUtils {

    /**
     * Valida todos os campos com tag que contenha "required".
     * Tags suportadas (combine com |):
     * - required
     * - email
     * - min:6
     */
    fun validateRequiredFields(root: View, showErrors: Boolean): Boolean {
        var ok = true

        walk(root) { v ->
            // Preferência: TextInputLayout (melhor UI pro erro)
            if (v is TextInputLayout && v.tag is String) {
                val tags = parseTags(v.tag as String)
                if ("required" in tags) {
                    val value = v.editText?.text?.toString()?.trim().orEmpty()
                    val fieldOk = validateValue(value, tags)

                    if (!fieldOk) {
                        ok = false
                        if (showErrors) v.error = errorMessage(tags)
                    } else {
                        v.error = null
                    }
                }
            }

            // Fallback: EditText direto
            if (v is EditText && v.tag is String) {
                val tags = parseTags(v.tag as String)
                if ("required" in tags) {
                    val value = v.text?.toString()?.trim().orEmpty()
                    val fieldOk = validateValue(value, tags)

                    if (!fieldOk) {
                        ok = false
                        if (showErrors) v.error = errorMessage(tags)
                    } else {
                        v.error = null
                    }
                }
            }
        }

        return ok
    }

    fun findEditTextByExactTag(root: View, tag: String): EditText? {
        var found: EditText? = null
        walk(root) { v ->
            if (found == null && v is EditText && v.tag == tag) found = v
        }
        return found
    }

    private fun validateValue(value: String, tags: Set<String>): Boolean {
        if (value.isBlank()) return false

        if ("email" in tags && !Patterns.EMAIL_ADDRESS.matcher(value).matches()) return false

        tags.firstOrNull { it.startsWith("min:") }?.let {
            val min = it.removePrefix("min:").toIntOrNull()
            if (min != null && value.length < min) return false
        }

        return true
    }

    private fun errorMessage(tags: Set<String>): String {
        return when {
            "email" in tags -> "Digite um e-mail válido"
            tags.any { it.startsWith("min:") } -> {
                val min = tags.first { it.startsWith("min:") }.removePrefix("min:")
                "Mínimo de $min caracteres"
            }
            else -> "Obrigatório"
        }
    }

    private fun parseTags(raw: String): Set<String> =
        raw.split("|").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    private fun walk(view: View, onVisit: (View) -> Unit) {
        onVisit(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                walk(view.getChildAt(i), onVisit)
            }
        }
    }

    fun applyRequiredAsterisk(root: View, colorRes: Int) {
        val color = ContextCompat.getColor(root.context, colorRes)

        fun walk(v: View) {
            if (v is TextInputLayout && v.tag is String) {
                val tags = (v.tag as String).split("|").map { it.trim() }.toSet()
                if ("required" in tags) {
                    val baseHint = v.hint?.toString() ?: v.editText?.hint?.toString()
                    if (!baseHint.isNullOrBlank() && !baseHint.endsWith(" *")) {
                        val text = "$baseHint *"
                        val span = SpannableString(text)
                        span.setSpan(
                            ForegroundColorSpan(color),
                            text.length - 1,
                            text.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        v.hint = span
                    }
                }
            }

            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }

        walk(root)
    }
}