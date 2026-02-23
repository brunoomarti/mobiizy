package com.brunocodex.kotlinproject.utils

interface StepValidatable {
    fun validateStep(showErrors: Boolean = true): Boolean
}
