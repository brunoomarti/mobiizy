package com.brunocodex.kotlinproject.utils

interface StepHeaderBindable {
    fun onStepChanged(current: Int, total: Int)
}
