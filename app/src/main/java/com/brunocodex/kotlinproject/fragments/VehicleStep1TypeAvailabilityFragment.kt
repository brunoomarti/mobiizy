package com.brunocodex.kotlinproject.fragments

import android.os.Bundle
import android.view.View
import android.widget.RadioGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.brunocodex.kotlinproject.R
import com.brunocodex.kotlinproject.utils.StepValidatable
import com.brunocodex.kotlinproject.viewmodels.VehicleRegisterViewModel

class VehicleStep1TypeAvailabilityFragment :
    Fragment(R.layout.fragment_vehicle_step1_type_availability),
    StepValidatable {

    private val vehicleViewModel: VehicleRegisterViewModel by activityViewModels()

    private lateinit var rgVehicleType: RadioGroup
    private lateinit var rgAvailability: RadioGroup
    private lateinit var tvVehicleTypeError: TextView
    private lateinit var tvAvailabilityError: TextView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<TextView>(R.id.stepHeadline).text =
            getString(R.string.vehicle_step1_headline)
        view.findViewById<TextView>(R.id.stepSubtitle).text =
            getString(R.string.vehicle_step1_subtitle)

        rgVehicleType = view.findViewById(R.id.rgVehicleType)
        rgAvailability = view.findViewById(R.id.rgAvailability)
        tvVehicleTypeError = view.findViewById(R.id.tvVehicleTypeError)
        tvAvailabilityError = view.findViewById(R.id.tvAvailabilityError)

        restoreState(view)
        bindListeners()
    }

    private fun restoreState(view: View) {
        when (vehicleViewModel.vehicleType) {
            VehicleRegisterViewModel.TYPE_CAR -> rgVehicleType.check(R.id.rbTypeCar)
            VehicleRegisterViewModel.TYPE_MOTORCYCLE -> rgVehicleType.check(R.id.rbTypeMotorcycle)
        }

        when (vehicleViewModel.availabilityMode) {
            VehicleRegisterViewModel.AVAILABILITY_IMMEDIATE ->
                rgAvailability.check(R.id.rbAvailabilityImmediate)

            VehicleRegisterViewModel.AVAILABILITY_DRAFT ->
                rgAvailability.check(R.id.rbAvailabilityDraft)
        }
    }

    private fun bindListeners() {
        rgVehicleType.setOnCheckedChangeListener { _, checkedId ->
            vehicleViewModel.vehicleType = when (checkedId) {
                R.id.rbTypeCar -> VehicleRegisterViewModel.TYPE_CAR
                R.id.rbTypeMotorcycle -> VehicleRegisterViewModel.TYPE_MOTORCYCLE
                else -> null
            }
            tvVehicleTypeError.visibility = View.GONE
        }

        rgAvailability.setOnCheckedChangeListener { _, checkedId ->
            vehicleViewModel.availabilityMode = when (checkedId) {
                R.id.rbAvailabilityImmediate -> VehicleRegisterViewModel.AVAILABILITY_IMMEDIATE
                R.id.rbAvailabilityDraft -> VehicleRegisterViewModel.AVAILABILITY_DRAFT
                else -> null
            }
            tvAvailabilityError.visibility = View.GONE
        }
    }

    override fun validateStep(showErrors: Boolean): Boolean {
        val hasType = !vehicleViewModel.vehicleType.isNullOrBlank()
        val hasAvailability = !vehicleViewModel.availabilityMode.isNullOrBlank()

        if (showErrors) {
            tvVehicleTypeError.visibility = if (hasType) View.GONE else View.VISIBLE
            tvAvailabilityError.visibility = if (hasAvailability) View.GONE else View.VISIBLE
        }

        return hasType && hasAvailability
    }
}
