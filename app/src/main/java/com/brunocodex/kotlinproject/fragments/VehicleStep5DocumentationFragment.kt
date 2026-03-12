package com.brunocodex.kotlinproject.fragments

import android.os.Bundle
import android.view.View
import android.widget.RadioGroup
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
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class VehicleStep5DocumentationFragment :
    Fragment(R.layout.fragment_vehicle_step5_documentation),
    StepValidatable {

    private val vehicleViewModel: VehicleRegisterViewModel by activityViewModels()

    private lateinit var rgDocs: RadioGroup
    private lateinit var rgIpva: RadioGroup
    private lateinit var rgInsurance: RadioGroup
    private lateinit var rgAllowPet: RadioGroup
    private lateinit var rgAllowSmoking: RadioGroup
    private lateinit var rgAllowTrip: RadioGroup

    private lateinit var tvDocsError: TextView
    private lateinit var tvIpvaError: TextView
    private lateinit var tvInsuranceError: TextView
    private lateinit var tvAllowPetError: TextView
    private lateinit var tvAllowSmokingError: TextView
    private lateinit var tvAllowTripError: TextView
    private lateinit var tvTripTypesError: TextView
    private lateinit var stepSubtitle: TextView
    private lateinit var tvAllowPetLabel: TextView
    private lateinit var tvAllowSmokingLabel: TextView

    private lateinit var insuranceTypeLayout: TextInputLayout
    private lateinit var minDriverAgeLayout: TextInputLayout
    private lateinit var minCnhYearsLayout: TextInputLayout
    private lateinit var chipGroupTripTypes: ChipGroup
    private lateinit var driverRequirementsRow: View
    private var lastRenderedVehicleType: String? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<TextView>(R.id.stepHeadline).text =
            getString(R.string.vehicle_step5_headline)
        stepSubtitle = view.findViewById(R.id.stepSubtitle)

        rgDocs = view.findViewById(R.id.rgDocsUpToDate)
        rgIpva = view.findViewById(R.id.rgIpva)
        rgInsurance = view.findViewById(R.id.rgInsurance)
        rgAllowPet = view.findViewById(R.id.rgAllowPet)
        rgAllowSmoking = view.findViewById(R.id.rgAllowSmoking)
        rgAllowTrip = view.findViewById(R.id.rgAllowTrip)

        tvDocsError = view.findViewById(R.id.tvDocsError)
        tvIpvaError = view.findViewById(R.id.tvIpvaError)
        tvInsuranceError = view.findViewById(R.id.tvInsuranceError)
        tvAllowPetError = view.findViewById(R.id.tvAllowPetError)
        tvAllowSmokingError = view.findViewById(R.id.tvAllowSmokingError)
        tvAllowTripError = view.findViewById(R.id.tvAllowTripError)
        tvTripTypesError = view.findViewById(R.id.tvTripTypesError)
        tvAllowPetLabel = view.findViewById(R.id.tvAllowPetLabel)
        tvAllowSmokingLabel = view.findViewById(R.id.tvAllowSmokingLabel)

        insuranceTypeLayout = view.findViewById(R.id.insuranceTypeLayout)
        minDriverAgeLayout = view.findViewById(R.id.minDriverAgeLayout)
        minCnhYearsLayout = view.findViewById(R.id.minCnhYearsLayout)
        chipGroupTripTypes = view.findViewById(R.id.chipGroupTripTypes)
        driverRequirementsRow = view.findViewById(R.id.driverRequirementsRow)

        bindText(view, R.id.insuranceTypeInput, vehicleViewModel.insuranceType) {
            vehicleViewModel.insuranceType = it
        }
        bindText(view, R.id.minDriverAgeInput, vehicleViewModel.minimumDriverAge) {
            vehicleViewModel.minimumDriverAge = it
        }
        bindText(view, R.id.minCnhYearsInput, vehicleViewModel.minimumLicenseYears) {
            vehicleViewModel.minimumLicenseYears = it
        }

        setupBooleanGroups()
        setupTripTypeChips()
        updateInsuranceVisibility(vehicleViewModel.hasInsurance == true)
        updateTripTypeVisibility(vehicleViewModel.allowTrip == true)
        refreshVehicleTypeDependentUi(force = true)
    }

    override fun onResume() {
        super.onResume()
        refreshVehicleTypeDependentUi()
    }

    private fun refreshVehicleTypeDependentUi(force: Boolean = false) {
        val currentType = vehicleViewModel.vehicleType
        if (!force && currentType == lastRenderedVehicleType) return
        lastRenderedVehicleType = currentType

        val isMotorcycle = isMotorcycleRegistration()
        stepSubtitle.text = getString(
            if (isMotorcycle) {
                R.string.vehicle_step5_subtitle_motorcycle
            } else {
                R.string.vehicle_step5_subtitle
            }
        )

        applyOwnerRulesUiByVehicleType(isMotorcycle)
    }

    private fun applyOwnerRulesUiByVehicleType(isMotorcycle: Boolean) {
        val showCarOnlyRules = !isMotorcycle

        tvAllowPetLabel.visibility = if (showCarOnlyRules) View.VISIBLE else View.GONE
        rgAllowPet.visibility = if (showCarOnlyRules) View.VISIBLE else View.GONE
        tvAllowPetError.visibility = View.GONE

        tvAllowSmokingLabel.visibility = if (showCarOnlyRules) View.VISIBLE else View.GONE
        rgAllowSmoking.visibility = if (showCarOnlyRules) View.VISIBLE else View.GONE
        tvAllowSmokingError.visibility = View.GONE

        driverRequirementsRow.visibility = if (showCarOnlyRules) View.VISIBLE else View.GONE
        minDriverAgeLayout.tag = if (showCarOnlyRules) "required" else null
        minCnhYearsLayout.tag = if (showCarOnlyRules) "required" else null

        if (isMotorcycle) {
            vehicleViewModel.allowPet = null
            vehicleViewModel.allowSmoking = null
            vehicleViewModel.minimumDriverAge = null
            vehicleViewModel.minimumLicenseYears = null
            rgAllowPet.clearCheck()
            rgAllowSmoking.clearCheck()
            minDriverAgeLayout.editText?.setText("")
            minDriverAgeLayout.error = null
            minCnhYearsLayout.editText?.setText("")
            minCnhYearsLayout.error = null
        }
    }

    private fun isMotorcycleRegistration(): Boolean {
        return vehicleViewModel.vehicleType == VehicleRegisterViewModel.TYPE_MOTORCYCLE
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

    private fun setupBooleanGroups() {
        bindBooleanGroup(
            group = rgDocs,
            yesId = R.id.rbDocsYes,
            noId = R.id.rbDocsNo,
            initial = vehicleViewModel.documentsUpToDate
        ) {
            vehicleViewModel.documentsUpToDate = it
            tvDocsError.visibility = View.GONE
        }

        bindBooleanGroup(
            group = rgIpva,
            yesId = R.id.rbIpvaYes,
            noId = R.id.rbIpvaNo,
            initial = vehicleViewModel.ipvaLicensingOk
        ) {
            vehicleViewModel.ipvaLicensingOk = it
            tvIpvaError.visibility = View.GONE
        }

        bindBooleanGroup(
            group = rgInsurance,
            yesId = R.id.rbInsuranceYes,
            noId = R.id.rbInsuranceNo,
            initial = vehicleViewModel.hasInsurance
        ) {
            vehicleViewModel.hasInsurance = it
            tvInsuranceError.visibility = View.GONE
            updateInsuranceVisibility(it == true)
        }

        bindBooleanGroup(
            group = rgAllowPet,
            yesId = R.id.rbAllowPetYes,
            noId = R.id.rbAllowPetNo,
            initial = vehicleViewModel.allowPet
        ) {
            vehicleViewModel.allowPet = it
            tvAllowPetError.visibility = View.GONE
        }

        bindBooleanGroup(
            group = rgAllowSmoking,
            yesId = R.id.rbAllowSmokingYes,
            noId = R.id.rbAllowSmokingNo,
            initial = vehicleViewModel.allowSmoking
        ) {
            vehicleViewModel.allowSmoking = it
            tvAllowSmokingError.visibility = View.GONE
        }

        bindBooleanGroup(
            group = rgAllowTrip,
            yesId = R.id.rbAllowTripYes,
            noId = R.id.rbAllowTripNo,
            initial = vehicleViewModel.allowTrip
        ) {
            vehicleViewModel.allowTrip = it
            tvAllowTripError.visibility = View.GONE
            updateTripTypeVisibility(it == true)
            if (it != true) {
                vehicleViewModel.allowedTripTypes.clear()
                syncTripTypeChips()
                tvTripTypesError.visibility = View.GONE
            }
        }
    }

    private fun bindBooleanGroup(
        group: RadioGroup,
        yesId: Int,
        noId: Int,
        initial: Boolean?,
        onValueChanged: (Boolean?) -> Unit
    ) {
        when (initial) {
            true -> group.check(yesId)
            false -> group.check(noId)
            null -> group.clearCheck()
        }

        group.setOnCheckedChangeListener { _, checkedId ->
            val value = when (checkedId) {
                yesId -> true
                noId -> false
                else -> null
            }
            onValueChanged(value)
        }
    }

    private fun updateInsuranceVisibility(visible: Boolean) {
        insuranceTypeLayout.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun setupTripTypeChips() {
        val tripTypes = resources.getStringArray(R.array.vehicle_trip_types)
        chipGroupTripTypes.removeAllViews()

        tripTypes.forEach { tripType ->
            val chip = Chip(requireContext()).apply {
                text = tripType
                isCheckable = true
                isChecked = vehicleViewModel.allowedTripTypes.contains(tripType)
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        vehicleViewModel.allowedTripTypes.add(tripType)
                    } else {
                        vehicleViewModel.allowedTripTypes.remove(tripType)
                    }
                    if (vehicleViewModel.allowedTripTypes.isNotEmpty()) {
                        tvTripTypesError.visibility = View.GONE
                    }
                }
            }
            chipGroupTripTypes.addView(chip)
        }
    }

    private fun syncTripTypeChips() {
        for (i in 0 until chipGroupTripTypes.childCount) {
            val chip = chipGroupTripTypes.getChildAt(i) as? Chip ?: continue
            chip.isChecked = vehicleViewModel.allowedTripTypes.contains(chip.text?.toString().orEmpty())
        }
    }

    private fun updateTripTypeVisibility(visible: Boolean) {
        chipGroupTripTypes.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) {
            tvTripTypesError.visibility = View.GONE
        }
    }

    override fun validateStep(showErrors: Boolean): Boolean {
        val root = view ?: return false
        val isMotorcycle = isMotorcycleRegistration()
        val textFieldsOk = StepValidationUtils.validateRequiredFields(root, showErrors)

        val docsOk = vehicleViewModel.documentsUpToDate != null
        val ipvaOk = vehicleViewModel.ipvaLicensingOk != null
        val insuranceOk = vehicleViewModel.hasInsurance != null
        val petOk = isMotorcycle || vehicleViewModel.allowPet != null
        val smokingOk = isMotorcycle || vehicleViewModel.allowSmoking != null
        val tripOk = vehicleViewModel.allowTrip != null
        val tripTypeOk = vehicleViewModel.allowTrip != true || vehicleViewModel.allowedTripTypes.isNotEmpty()

        if (showErrors) {
            tvDocsError.visibility = if (docsOk) View.GONE else View.VISIBLE
            tvIpvaError.visibility = if (ipvaOk) View.GONE else View.VISIBLE
            tvInsuranceError.visibility = if (insuranceOk) View.GONE else View.VISIBLE
            tvAllowPetError.visibility =
                if (isMotorcycle || petOk) View.GONE else View.VISIBLE
            tvAllowSmokingError.visibility =
                if (isMotorcycle || smokingOk) View.GONE else View.VISIBLE
            tvAllowTripError.visibility = if (tripOk) View.GONE else View.VISIBLE
            tvTripTypesError.visibility = if (tripTypeOk) View.GONE else View.VISIBLE
        }

        return textFieldsOk &&
            docsOk &&
            ipvaOk &&
            insuranceOk &&
            petOk &&
            smokingOk &&
            tripOk &&
            tripTypeOk
    }
}
