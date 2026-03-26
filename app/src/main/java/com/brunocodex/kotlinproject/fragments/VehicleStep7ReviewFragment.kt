package com.brunocodex.kotlinproject.fragments

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.brunocodex.kotlinproject.R
import com.brunocodex.kotlinproject.utils.StepValidatable
import com.brunocodex.kotlinproject.utils.VehicleStepNavigator
import com.brunocodex.kotlinproject.viewmodels.VehicleRegisterViewModel
import com.google.android.material.checkbox.MaterialCheckBox

class VehicleStep7ReviewFragment : Fragment(R.layout.fragment_vehicle_step7_review), StepValidatable {

    private val vehicleViewModel: VehicleRegisterViewModel by activityViewModels()

    private lateinit var tvSummary: TextView
    private lateinit var cbChecklistPhotos: MaterialCheckBox
    private lateinit var cbChecklistPrice: MaterialCheckBox
    private lateinit var cbChecklistRules: MaterialCheckBox
    private lateinit var cbChecklistLocation: MaterialCheckBox
    private lateinit var tvChecklistError: TextView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<TextView>(R.id.stepHeadline).text =
            getString(R.string.vehicle_step7_headline)
        view.findViewById<TextView>(R.id.stepSubtitle).text =
            getString(R.string.vehicle_step7_subtitle)

        tvSummary = view.findViewById(R.id.tvSummary)
        cbChecklistPhotos = view.findViewById(R.id.cbChecklistPhotos)
        cbChecklistPrice = view.findViewById(R.id.cbChecklistPrice)
        cbChecklistRules = view.findViewById(R.id.cbChecklistRules)
        cbChecklistLocation = view.findViewById(R.id.cbChecklistLocation)
        tvChecklistError = view.findViewById(R.id.tvChecklistError)

        bindEditButton(view, R.id.btnEditStep1, 0, R.string.vehicle_step1_headline)
        bindEditButton(view, R.id.btnEditStep2, 1, R.string.vehicle_step2_headline)
        bindEditButton(view, R.id.btnEditStep3, 2, R.string.vehicle_step3_headline)
        bindEditButton(view, R.id.btnEditStep4, 3, R.string.vehicle_step4_headline)
        bindEditButton(view, R.id.btnEditStep5, 4, R.string.vehicle_step5_headline)
        bindEditButton(view, R.id.btnEditStep6, 5, R.string.vehicle_step6_headline)

        refreshReview()
    }

    override fun onResume() {
        super.onResume()
        refreshReview()
    }

    private fun bindEditButton(root: View, buttonId: Int, step: Int, stepTitleResId: Int) {
        root.findViewById<Button>(buttonId).apply {
            text = getString(
                R.string.vehicle_step7_edit_step_named,
                getString(stepTitleResId)
            )
            setOnClickListener {
                (activity as? VehicleStepNavigator)?.goToStep(step)
            }
        }
    }

    private fun refreshReview() {
        if (!::tvSummary.isInitialized) return

        val vehicleType = when (vehicleViewModel.vehicleType) {
            VehicleRegisterViewModel.TYPE_CAR -> getString(R.string.vehicle_option_car)
            VehicleRegisterViewModel.TYPE_MOTORCYCLE -> getString(R.string.vehicle_option_motorcycle)
            else -> getString(R.string.vehicle_summary_not_informed)
        }

        val availability = when (vehicleViewModel.availabilityMode) {
            VehicleRegisterViewModel.AVAILABILITY_IMMEDIATE ->
                getString(R.string.vehicle_option_availability_immediate)

            VehicleRegisterViewModel.AVAILABILITY_DRAFT ->
                getString(R.string.vehicle_option_availability_draft)

            else -> getString(R.string.vehicle_summary_not_informed)
        }

        val summary = buildString {
            val rulesSummary = if (vehicleViewModel.vehicleType == VehicleRegisterViewModel.TYPE_MOTORCYCLE) {
                getString(
                    R.string.vehicle_summary_rules_motorcycle,
                    boolLabel(vehicleViewModel.allowTrip)
                )
            } else {
                getString(
                    R.string.vehicle_summary_rules,
                    boolLabel(vehicleViewModel.allowPet),
                    boolLabel(vehicleViewModel.allowSmoking),
                    boolLabel(vehicleViewModel.allowTrip)
                )
            }

            appendLine(getString(R.string.vehicle_summary_type_and_availability, vehicleType, availability))
            appendLine(
                getString(
                    R.string.vehicle_summary_identification,
                    vehicleViewModel.brand.orEmpty().ifBlank { getString(R.string.vehicle_summary_not_informed) },
                    vehicleViewModel.model.orEmpty().ifBlank { getString(R.string.vehicle_summary_not_informed) },
                    vehicleViewModel.manufactureYear.orEmpty().ifBlank { "-" },
                    vehicleViewModel.modelYear.orEmpty().ifBlank { "-" }
                )
            )
            appendLine(
                getString(
                    R.string.vehicle_summary_photos,
                    vehicleViewModel.photoCount(),
                    vehicleViewModel.highlightTags.joinToString(", ").ifBlank {
                        getString(R.string.vehicle_summary_not_informed)
                    }
                )
            )
            appendLine(
                getString(
                    R.string.vehicle_summary_price_and_condition,
                    vehicleViewModel.dailyPrice.orEmpty().ifBlank {
                        getString(R.string.vehicle_summary_not_informed)
                    },
                    conditionLabel(vehicleViewModel.condition)
                )
            )
            appendLine(rulesSummary)
            append(
                getString(
                    R.string.vehicle_summary_location,
                    vehicleViewModel.cityState.orEmpty().ifBlank {
                        getString(R.string.vehicle_summary_not_informed)
                    },
                    vehicleViewModel.neighborhood.orEmpty().ifBlank {
                        getString(R.string.vehicle_summary_not_informed)
                    }
                )
            )
        }

        tvSummary.text = summary

        cbChecklistPhotos.isChecked = vehicleViewModel.isPhotosChecklistOk()
        cbChecklistPrice.isChecked = vehicleViewModel.isPriceChecklistOk()
        cbChecklistRules.isChecked = vehicleViewModel.isRulesChecklistOk()
        cbChecklistLocation.isChecked = vehicleViewModel.isLocationChecklistOk()

        if (allChecklistItemsOk()) {
            tvChecklistError.visibility = View.GONE
        }
    }

    private fun conditionLabel(condition: String?): String {
        return when (condition) {
            VehicleRegisterViewModel.CONDITION_EXCELLENT -> getString(R.string.vehicle_condition_excellent)
            VehicleRegisterViewModel.CONDITION_GOOD -> getString(R.string.vehicle_condition_good)
            VehicleRegisterViewModel.CONDITION_OK -> getString(R.string.vehicle_condition_ok)
            VehicleRegisterViewModel.CONDITION_NEEDS_ATTENTION -> getString(R.string.vehicle_condition_needs_attention)
            else -> getString(R.string.vehicle_summary_not_informed)
        }
    }

    private fun boolLabel(value: Boolean?): String {
        return when (value) {
            true -> getString(R.string.common_yes)
            false -> getString(R.string.common_no)
            null -> getString(R.string.vehicle_summary_not_informed)
        }
    }

    private fun allChecklistItemsOk(): Boolean {
        return cbChecklistPhotos.isChecked &&
            cbChecklistPrice.isChecked &&
            cbChecklistRules.isChecked &&
            cbChecklistLocation.isChecked
    }

    override fun validateStep(showErrors: Boolean): Boolean {
        refreshReview()
        val ok = allChecklistItemsOk()
        if (showErrors) {
            tvChecklistError.visibility = if (ok) View.GONE else View.VISIBLE
        }
        return ok
    }
}
