package com.brunocodex.kotlinproject.fragments

import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.annotation.ArrayRes
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
import com.google.android.material.textfield.TextInputLayout
import java.text.Normalizer
import java.util.Locale

class VehicleStep2IdentificationFragment :
    Fragment(R.layout.fragment_vehicle_step2_identification),
    StepValidatable {

    private val vehicleViewModel: VehicleRegisterViewModel by activityViewModels()

    private lateinit var stepSubtitle: TextView
    private lateinit var chipGroupHighlights: ChipGroup
    private lateinit var tvHighlightsError: TextView
    private lateinit var bodyTypeLayout: TextInputLayout
    private lateinit var doorsLayout: TextInputLayout
    private lateinit var seatsLayout: TextInputLayout
    private lateinit var doorsSeatsRow: View
    private lateinit var doorsSeatsSpacer: View
    private lateinit var bodyTypeInput: MaterialAutoCompleteTextView
    private lateinit var fuelTypeInput: MaterialAutoCompleteTextView
    private lateinit var transmissionInput: MaterialAutoCompleteTextView
    private var bindingsInitialized = false
    private var lastRenderedVehicleType: String? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<TextView>(R.id.stepHeadline).text =
            getString(R.string.vehicle_step2_headline)
        stepSubtitle = view.findViewById(R.id.stepSubtitle)

        chipGroupHighlights = view.findViewById(R.id.chipGroupHighlights)
        tvHighlightsError = view.findViewById(R.id.tvHighlightsError)
        bodyTypeLayout = view.findViewById(R.id.bodyTypeLayout)
        doorsLayout = view.findViewById(R.id.doorsLayout)
        seatsLayout = view.findViewById(R.id.seatsLayout)
        doorsSeatsRow = view.findViewById(R.id.doorsSeatsRow)
        doorsSeatsSpacer = view.findViewById(R.id.doorsSeatsSpacer)
        bodyTypeInput = view.findViewById(R.id.bodyTypeInput)
        fuelTypeInput = view.findViewById(R.id.fuelTypeInput)
        transmissionInput = view.findViewById(R.id.transmissionInput)

        initializeBindingsOnce(view)
        refreshVehicleTypeDependentUi(force = true)
    }

    override fun onResume() {
        super.onResume()
        refreshVehicleTypeDependentUi()
    }

    private fun initializeBindingsOnce(view: View) {
        if (bindingsInitialized) return
        setupTextBindings(view)
        setupDropdownBindings()
        bindingsInitialized = true
    }

    private fun refreshVehicleTypeDependentUi(force: Boolean = false) {
        val currentType = vehicleViewModel.vehicleType
        if (!force && currentType == lastRenderedVehicleType) return
        lastRenderedVehicleType = currentType

        val isMotorcycle = isMotorcycleRegistration()

        stepSubtitle.text = getString(
            if (isMotorcycle) {
                R.string.vehicle_step2_subtitle_motorcycle
            } else {
                R.string.vehicle_step2_subtitle
            }
        )

        configureVehicleTypeSpecificUi(isMotorcycle)
        applyDropdownOptionsForCurrentType(isMotorcycle)
        setupHighlightTags(isMotorcycle)
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

    private fun applyDropdownOptionsForCurrentType(isMotorcycle: Boolean) {
        val bodyTypeResId =
            if (isMotorcycle) R.array.vehicle_body_types_motorcycle else R.array.vehicle_body_types
        val fuelTypeResId =
            if (isMotorcycle) R.array.vehicle_fuel_types_motorcycle else R.array.vehicle_fuel_types
        val transmissionResId = if (isMotorcycle) {
            R.array.vehicle_transmission_types_motorcycle
        } else {
            R.array.vehicle_transmission_types
        }

        val bodyTypeOptions = resources.getStringArray(bodyTypeResId)
        val fuelTypeOptions = resources.getStringArray(fuelTypeResId)
        val transmissionOptions = resources.getStringArray(transmissionResId)

        vehicleViewModel.bodyType = resolveOptionForCurrentLocale(
            value = vehicleViewModel.bodyType,
            currentOptions = bodyTypeOptions,
            optionsResId = bodyTypeResId
        )
        vehicleViewModel.fuelType = resolveOptionForCurrentLocale(
            value = vehicleViewModel.fuelType,
            currentOptions = fuelTypeOptions,
            optionsResId = fuelTypeResId
        )
        vehicleViewModel.transmissionType = resolveOptionForCurrentLocale(
            value = vehicleViewModel.transmissionType,
            currentOptions = transmissionOptions,
            optionsResId = transmissionResId
        )

        applyDropdownOptions(
            input = bodyTypeInput,
            options = bodyTypeOptions,
            selectedValue = vehicleViewModel.bodyType
        )

        applyDropdownOptions(
            input = fuelTypeInput,
            options = fuelTypeOptions,
            selectedValue = vehicleViewModel.fuelType
        )

        applyDropdownOptions(
            input = transmissionInput,
            options = transmissionOptions,
            selectedValue = vehicleViewModel.transmissionType
        )
    }

    private fun setupHighlightTags(isMotorcycle: Boolean) {
        val tagsResId =
            if (isMotorcycle) R.array.vehicle_highlight_tags_motorcycle else R.array.vehicle_highlight_tags
        val tags = resources.getStringArray(tagsResId)
        val localizedTags = resolveTagsForCurrentLocale(
            selectedTags = vehicleViewModel.highlightTags,
            currentOptions = tags,
            optionsResId = tagsResId
        )
        vehicleViewModel.highlightTags.clear()
        vehicleViewModel.highlightTags.addAll(localizedTags)
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

    private fun configureVehicleTypeSpecificUi(isMotorcycle: Boolean) {
        bodyTypeLayout.hint = getString(
            if (isMotorcycle) {
                R.string.vehicle_field_body_type_motorcycle
            } else {
                R.string.vehicle_field_body_type
            }
        )

        if (isMotorcycle) {
            doorsSeatsRow.visibility = View.VISIBLE
            doorsLayout.visibility = View.GONE
            doorsSeatsSpacer.visibility = View.GONE
            seatsLayout.visibility = View.VISIBLE
            doorsLayout.tag = null
            seatsLayout.tag = "required"
            doorsLayout.editText?.setText("")
            doorsLayout.error = null
            vehicleViewModel.doors = null
        } else {
            doorsSeatsRow.visibility = View.VISIBLE
            doorsLayout.visibility = View.VISIBLE
            doorsSeatsSpacer.visibility = View.VISIBLE
            seatsLayout.visibility = View.VISIBLE
            doorsLayout.tag = "required"
            seatsLayout.tag = "required"
        }
    }

    private fun isMotorcycleRegistration(): Boolean {
        return vehicleViewModel.vehicleType == VehicleRegisterViewModel.TYPE_MOTORCYCLE
    }

    private fun resolveOptionForCurrentLocale(
        value: String?,
        currentOptions: Array<String>,
        @ArrayRes optionsResId: Int
    ): String? {
        val candidate = value?.trim().orEmpty()
        if (candidate.isBlank()) return null

        val currentIndex = findOptionIndex(currentOptions, candidate)
        if (currentIndex >= 0) return currentOptions[currentIndex]

        val fallbackIndex = listOf("pt-BR", "en")
            .asSequence()
            .map { localeTag ->
                findOptionIndex(
                    options = getStringArrayForLocale(optionsResId, localeTag),
                    value = candidate
                )
            }
            .firstOrNull { it >= 0 }
            ?: -1

        return if (fallbackIndex in currentOptions.indices) {
            currentOptions[fallbackIndex]
        } else {
            candidate
        }
    }

    private fun resolveTagsForCurrentLocale(
        selectedTags: Set<String>,
        currentOptions: Array<String>,
        @ArrayRes optionsResId: Int
    ): LinkedHashSet<String> {
        val resolved = linkedSetOf<String>()
        selectedTags.forEach { tag ->
            val mapped = resolveOptionForCurrentLocale(tag, currentOptions, optionsResId) ?: return@forEach
            val index = findOptionIndex(currentOptions, mapped)
            if (index >= 0) {
                resolved += currentOptions[index]
            }
        }
        return resolved
    }

    private fun getStringArrayForLocale(@ArrayRes resId: Int, localeTag: String): Array<String> {
        val configuration = Configuration(resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(localeTag))
        val localizedContext = requireContext().createConfigurationContext(configuration)
        return localizedContext.resources.getStringArray(resId)
    }

    private fun findOptionIndex(options: Array<String>, value: String): Int {
        val normalizedValue = normalizeForCompare(value)
        if (normalizedValue.isBlank()) return -1
        return options.indexOfFirst { option ->
            normalizeForCompare(option) == normalizedValue
        }
    }

    private fun normalizeForCompare(value: String): String {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
        return normalized
            .replace("\\p{Mn}+".toRegex(), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase(Locale.ROOT)
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

    private fun setupDropdownBindings() {
        bindDropdownValue(
            input = bodyTypeInput,
            onValue = { vehicleViewModel.bodyType = it }
        )
        bindDropdownValue(
            input = fuelTypeInput,
            onValue = { vehicleViewModel.fuelType = it }
        )
        bindDropdownValue(
            input = transmissionInput,
            onValue = { vehicleViewModel.transmissionType = it }
        )
    }

    private fun bindDropdownValue(
        input: MaterialAutoCompleteTextView,
        onValue: (String?) -> Unit
    ) {
        input.setOnItemClickListener { parent, _, position, _ ->
            onValue(parent.getItemAtPosition(position)?.toString())
        }
        input.doAfterTextChanged { text ->
            val value = text?.toString()?.trim().orEmpty()
            onValue(value.ifBlank { null })
        }
    }

    private fun applyDropdownOptions(
        input: MaterialAutoCompleteTextView,
        options: Array<String>,
        selectedValue: String?
    ) {
        input.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, options))
        val value = selectedValue.orEmpty()
        val currentInput = input.text?.toString().orEmpty()
        if (currentInput != value) {
            input.setText(value, false)
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
