package com.brunocodex.kotlinproject.fragments

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.fragment.app.Fragment
import com.brunocodex.kotlinproject.R
import com.google.android.material.progressindicator.CircularProgressIndicator

class StepActionsFragment : Fragment(R.layout.fragment_step_actions) {

    interface Listener {
        fun onStepBackClicked()
        fun onStepNextClicked()
    }

    private data class UiState(
        var backEnabled: Boolean = true,
        var nextEnabled: Boolean = true,
        var nextText: CharSequence? = null,
        var loading: Boolean = false
    )

    private val uiState = UiState()

    private var listener: Listener? = null

    private lateinit var btnBack: Button
    private lateinit var btnNext: Button
    private lateinit var progressBtnNext: CircularProgressIndicator

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = when {
            parentFragment is Listener -> parentFragment as Listener
            context is Listener -> context
            else -> null
        }
    }

    override fun onDetach() {
        listener = null
        super.onDetach()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        btnBack = view.findViewById(R.id.btnBack)
        btnNext = view.findViewById(R.id.btnNext)
        progressBtnNext = view.findViewById(R.id.progressBtnNext)

        btnBack.setOnClickListener { listener?.onStepBackClicked() }
        btnNext.setOnClickListener { listener?.onStepNextClicked() }

        applyUiState()
    }

    fun setBackEnabled(enabled: Boolean) {
        uiState.backEnabled = enabled
        applyUiState()
    }

    fun setNextEnabled(enabled: Boolean) {
        uiState.nextEnabled = enabled
        applyUiState()
    }

    fun setNextText(text: CharSequence) {
        uiState.nextText = text
        applyUiState()
    }

    fun setLoading(loading: Boolean) {
        uiState.loading = loading
        applyUiState()
    }

    private fun applyUiState() {
        if (!::btnBack.isInitialized || !::btnNext.isInitialized || !::progressBtnNext.isInitialized) {
            return
        }

        val controlsEnabled = !uiState.loading
        btnBack.isEnabled = uiState.backEnabled && controlsEnabled
        btnNext.isEnabled = uiState.nextEnabled && controlsEnabled

        if (uiState.loading) {
            btnNext.text = ""
            progressBtnNext.visibility = View.VISIBLE
            return
        }

        progressBtnNext.visibility = View.GONE
        uiState.nextText?.let { btnNext.text = it }
    }
}
