package com.brunocodex.kotlinproject.utils

import java.time.LocalDate

object RenterDateSelectionMemoryStore {

    data class Selection(
        val startDate: LocalDate,
        val endDate: LocalDate?
    )

    @Volatile
    private var currentSelection: Selection? = null

    fun read(): Selection? = currentSelection

    fun save(startDate: LocalDate, endDate: LocalDate?) {
        currentSelection = Selection(startDate = startDate, endDate = endDate)
    }

    fun clear() {
        currentSelection = null
    }
}
