package com.brunocodex.kotlinproject.fragments

import android.os.Bundle
import android.view.View
import android.widget.RadioGroup
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.brunocodex.kotlinproject.R
import com.brunocodex.kotlinproject.components.BankPriceInputEditText
import com.brunocodex.kotlinproject.utils.StepValidatable
import com.brunocodex.kotlinproject.utils.StepValidationUtils
import com.brunocodex.kotlinproject.viewmodels.VehicleRegisterViewModel
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class VehicleStep4ConditionFragment :
    Fragment(R.layout.fragment_vehicle_step4_condition),
    StepValidatable {

    private val vehicleViewModel: VehicleRegisterViewModel by activityViewModels()

    private lateinit var rgCondition: RadioGroup
    private lateinit var rgAccident: RadioGroup
    private lateinit var tvConditionError: TextView
    private lateinit var tvAccidentError: TextView
    private lateinit var accidentDescriptionLayout: TextInputLayout

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<TextView>(R.id.stepHeadline).text =
            getString(R.string.vehicle_step4_headline)
        view.findViewById<TextView>(R.id.stepSubtitle).text =
            getString(R.string.vehicle_step4_subtitle)

        rgCondition = view.findViewById(R.id.rgCondition)
        rgAccident = view.findViewById(R.id.rgAccident)
        tvConditionError = view.findViewById(R.id.tvConditionError)
        tvAccidentError = view.findViewById(R.id.tvAccidentError)
        accidentDescriptionLayout = view.findViewById(R.id.accidentDescriptionLayout)

        bindText(view, R.id.mileageInput, vehicleViewModel.mileage) { vehicleViewModel.mileage = it }
        bindText(
            view,
            R.id.accidentDescriptionInput,
            vehicleViewModel.accidentDescription
        ) { vehicleViewModel.accidentDescription = it }
        bindText(
            view,
            R.id.observationsInput,
            vehicleViewModel.observations
        ) { vehicleViewModel.observations = it }
        bindBankPrice(
            view,
            R.id.dailyPriceInput,
            vehicleViewModel.dailyPrice
        ) { vehicleViewModel.dailyPrice = it }

        setupConditionState()
        setupAccidentState()
        setupItemGroups(view)
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

    private fun bindBankPrice(
        root: View,
        inputId: Int,
        initialValue: String?,
        onChange: (String?) -> Unit
    ) {
        val input = root.findViewById<BankPriceInputEditText>(inputId)
        input.bindBankPrice(initialValue) { formattedValue ->
            onChange(formattedValue)
        }
    }

    private fun setupConditionState() {
        when (vehicleViewModel.condition) {
            VehicleRegisterViewModel.CONDITION_EXCELLENT -> rgCondition.check(R.id.rbConditionExcellent)
            VehicleRegisterViewModel.CONDITION_GOOD -> rgCondition.check(R.id.rbConditionGood)
            VehicleRegisterViewModel.CONDITION_OK -> rgCondition.check(R.id.rbConditionOk)
            VehicleRegisterViewModel.CONDITION_NEEDS_ATTENTION -> rgCondition.check(R.id.rbConditionAttention)
        }

        rgCondition.setOnCheckedChangeListener { _, checkedId ->
            vehicleViewModel.condition = when (checkedId) {
                R.id.rbConditionExcellent -> VehicleRegisterViewModel.CONDITION_EXCELLENT
                R.id.rbConditionGood -> VehicleRegisterViewModel.CONDITION_GOOD
                R.id.rbConditionOk -> VehicleRegisterViewModel.CONDITION_OK
                R.id.rbConditionAttention -> VehicleRegisterViewModel.CONDITION_NEEDS_ATTENTION
                else -> null
            }
            tvConditionError.visibility = View.GONE
        }
    }

    private fun setupAccidentState() {
        when (vehicleViewModel.hadAccident) {
            true -> rgAccident.check(R.id.rbAccidentYes)
            false -> rgAccident.check(R.id.rbAccidentNo)
            null -> rgAccident.clearCheck()
        }
        updateAccidentDescriptionVisibility(vehicleViewModel.hadAccident == true)

        rgAccident.setOnCheckedChangeListener { _, checkedId ->
            vehicleViewModel.hadAccident = when (checkedId) {
                R.id.rbAccidentYes -> true
                R.id.rbAccidentNo -> false
                else -> null
            }

            if (vehicleViewModel.hadAccident == false) {
                vehicleViewModel.accidentDescription = null
                accidentDescriptionLayout.editText?.setText("")
                accidentDescriptionLayout.error = null
            }

            tvAccidentError.visibility = View.GONE
            updateAccidentDescriptionVisibility(vehicleViewModel.hadAccident == true)
        }
    }

    private fun updateAccidentDescriptionVisibility(visible: Boolean) {
        accidentDescriptionLayout.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun setupItemGroups(view: View) {
        val safetyGroup = view.findViewById<ChipGroup>(R.id.chipGroupSafety)
        val comfortGroup = view.findViewById<ChipGroup>(R.id.chipGroupComfort)

        inflateCheckableChips(
            group = safetyGroup,
            options = resources.getStringArray(R.array.vehicle_safety_items),
            selected = vehicleViewModel.safetyItems
        )

        inflateCheckableChips(
            group = comfortGroup,
            options = resources.getStringArray(R.array.vehicle_comfort_items),
            selected = vehicleViewModel.comfortItems
        )
    }

    private fun inflateCheckableChips(
        group: ChipGroup,
        options: Array<String>,
        selected: MutableSet<String>
    ) {
        group.removeAllViews()
        options.forEach { item ->
            val chip = Chip(requireContext()).apply {
                text = item
                isCheckable = true
                isChecked = selected.contains(item)
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) selected.add(item) else selected.remove(item)
                }
            }
            group.addView(chip)
        }
    }

    override fun validateStep(showErrors: Boolean): Boolean {
        val root = view ?: return false
        val fieldsOk = StepValidationUtils.validateRequiredFields(root, showErrors)

        val hasCondition = !vehicleViewModel.condition.isNullOrBlank()
        val hasAccidentSelection = vehicleViewModel.hadAccident != null
        val accidentDescriptionOk = vehicleViewModel.hadAccident != true ||
            !vehicleViewModel.accidentDescription.isNullOrBlank()

        if (showErrors) {
            tvConditionError.visibility = if (hasCondition) View.GONE else View.VISIBLE
            tvAccidentError.visibility = if (hasAccidentSelection) View.GONE else View.VISIBLE
            accidentDescriptionLayout.error = if (accidentDescriptionOk) {
                null
            } else {
                getString(R.string.error_required)
            }
        }

        return fieldsOk && hasCondition && hasAccidentSelection && accidentDescriptionOk
    }
}
