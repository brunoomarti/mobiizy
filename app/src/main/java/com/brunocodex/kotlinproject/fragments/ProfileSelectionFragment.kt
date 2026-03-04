package com.brunocodex.kotlinproject.fragments

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.brunocodex.kotlinproject.R
import com.brunocodex.kotlinproject.model.ProfileType
import com.brunocodex.kotlinproject.viewmodels.RegisterViewModel
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors

class ProfileSelectionFragment : Fragment(R.layout.fragment_profile_selection) {

    private val registerViewModel: RegisterViewModel by activityViewModels()

    private lateinit var cardRent: MaterialCardView
    private lateinit var cardProvide: MaterialCardView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<TextView>(R.id.stepHeadline).text = getString(R.string.profile_selection_step_headline)
        view.findViewById<TextView>(R.id.stepSubtitle).text =
            getString(R.string.profile_selection_step_subtitle)

        cardRent = view.findViewById(R.id.cardRent)
        cardProvide = view.findViewById(R.id.cardProvide)

        cardRent.setOnClickListener {
            registerViewModel.profileType = ProfileType.RENTER
            updateCardSelection()
        }

        cardProvide.setOnClickListener {
            registerViewModel.profileType = ProfileType.PROVIDER
            updateCardSelection()
        }

        updateCardSelection()
    }

    private fun updateCardSelection() {
        when (registerViewModel.profileType) {
            ProfileType.RENTER -> {
                applyCardState(cardRent, selected = true)
                applyCardState(cardProvide, selected = false)
            }
            ProfileType.PROVIDER -> {
                applyCardState(cardRent, selected = false)
                applyCardState(cardProvide, selected = true)
            }
            null -> {
                applyCardState(cardRent, selected = false)
                applyCardState(cardProvide, selected = false)
            }
        }
    }

    private fun applyCardState(card: MaterialCardView, selected: Boolean) {
        val strokeSelected = MaterialColors.getColor(card, com.google.android.material.R.attr.colorSecondary)
        val strokeDefault = MaterialColors.getColor(card, R.attr.borderLight)
        val bgDefault = MaterialColors.getColor(card, R.attr.formBackground)
        val bgSelected = MaterialColors.layer(bgDefault, strokeSelected, 0.18f)

        card.strokeWidth = if (selected) 2 else 1
        card.strokeColor = if (selected) strokeSelected else strokeDefault
        card.cardElevation = if (selected) 1f else 1f
        card.setCardBackgroundColor(if (selected) bgSelected else bgDefault)
    }
}
