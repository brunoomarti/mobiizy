package com.brunocodex.kotlinproject.fragments

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.brunocodex.kotlinproject.R
import com.brunocodex.kotlinproject.utils.StepValidatable
import com.brunocodex.kotlinproject.utils.StepValidationUtils
import com.brunocodex.kotlinproject.viewmodels.VehicleRegisterViewModel
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText

class VehicleStep2IdentificationFragment :
    Fragment(R.layout.fragment_vehicle_step2_identification),
    StepValidatable {

    private val vehicleViewModel: VehicleRegisterViewModel by activityViewModels()

    private lateinit var chipGroupHighlights: ChipGroup
    private lateinit var tvHighlightsError: TextView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<TextView>(R.id.stepHeadline).text =
            getString(R.string.vehicle_step2_headline)
        view.findViewById<TextView>(R.id.stepSubtitle).text =
            getString(R.string.vehicle_step2_subtitle)

        chipGroupHighlights = view.findViewById(R.id.chipGroupHighlights)
        tvHighlightsError = view.findViewById(R.id.tvHighlightsError)

        setupTextBindings(view)
        setupDropdowns(view)
        setupHighlightTags(view)
    }

    private fun setupTextBindings(view: View) {
        bindText(view, R.id.brandInput, vehicleViewModel.brand) { vehicleViewModel.brand = it }
        bindText(view, R.id.modelInput, vehicleViewModel.model) { vehicleViewModel.model = it }
        bindText(
            view,
            R.id.manufactureYearInput,
            vehicleViewModel.manufactureYear
        ) { vehicleViewModel.manufactureYear = it }
        bindText(view, R.id.modelYearInput, vehicleViewModel.modelYear) { vehicleViewModel.modelYear = it }
        bindText(view, R.id.trimInput, vehicleViewModel.trimVersion) { vehicleViewModel.trimVersion = it }
        bindText(view, R.id.plateInput, vehicleViewModel.plate) { vehicleViewModel.plate = it?.uppercase() }
        bindText(
            view,
            R.id.renavamInput,
            vehicleViewModel.renavamOrChassis
        ) { vehicleViewModel.renavamOrChassis = it }
        bindText(view, R.id.colorInput, vehicleViewModel.color) { vehicleViewModel.color = it }
        bindText(view, R.id.doorsInput, vehicleViewModel.doors) { vehicleViewModel.doors = it }
        bindText(view, R.id.seatsInput, vehicleViewModel.seats) { vehicleViewModel.seats = it }
    }

    private fun setupDropdowns(view: View) {
        bindDropdown(
            view = view,
            inputId = R.id.bodyTypeInput,
            options = resources.getStringArray(R.array.vehicle_body_types),
            initial = vehicleViewModel.bodyType
        ) { vehicleViewModel.bodyType = it }

        bindDropdown(
            view = view,
            inputId = R.id.fuelTypeInput,
            options = resources.getStringArray(R.array.vehicle_fuel_types),
            initial = vehicleViewModel.fuelType
        ) { vehicleViewModel.fuelType = it }

        bindDropdown(
            view = view,
            inputId = R.id.transmissionInput,
            options = resources.getStringArray(R.array.vehicle_transmission_types),
            initial = vehicleViewModel.transmissionType
        ) { vehicleViewModel.transmissionType = it }
    }

    private fun setupHighlightTags(view: View) {
        val tags = resources.getStringArray(R.array.vehicle_highlight_tags)
        chipGroupHighlights.removeAllViews()

        tags.forEach { tag ->
            val chip = Chip(requireContext()).apply {
                text = tag
                isCheckable = true
                isChecked = vehicleViewModel.highlightTags.contains(tag)
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        vehicleViewModel.highlightTags.add(tag)
                    } else {
                        vehicleViewModel.highlightTags.remove(tag)
                    }
                    if (vehicleViewModel.highlightTags.size >= 3) {
                        tvHighlightsError.visibility = View.GONE
                    }
                }
            }
            chipGroupHighlights.addView(chip)
        }
    }

    private fun bindText(
        root: View,
        inputId: Int,
        initialValue: String?,
        onChange: (String?) -> Unit
    ) {
        val input = root.findViewById<TextInputEditText>(inputId)
        input.setText(initialValue.orEmpty())
        input.doAfterTextChanged { onChange(it?.toString()?.trim()) }
    }

    private fun bindDropdown(
        view: View,
        inputId: Int,
        options: Array<String>,
        initial: String?,
        onValue: (String?) -> Unit
    ) {
        val input = view.findViewById<MaterialAutoCompleteTextView>(inputId)
        input.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, options))
        input.setText(initial.orEmpty(), false)
        input.setOnItemClickListener { parent, _, position, _ ->
            onValue(parent.getItemAtPosition(position)?.toString())
        }
        input.doAfterTextChanged { text ->
            val value = text?.toString()?.trim().orEmpty()
            onValue(value.ifBlank { null })
        }
    }

    override fun validateStep(showErrors: Boolean): Boolean {
        val root = view ?: return false
        val fieldsOk = StepValidationUtils.validateRequiredFields(root, showErrors)
        val tagsOk = vehicleViewModel.highlightTags.size >= 3

        if (showErrors) {
            tvHighlightsError.visibility = if (tagsOk) View.GONE else View.VISIBLE
        }

        return fieldsOk && tagsOk
    }
}
