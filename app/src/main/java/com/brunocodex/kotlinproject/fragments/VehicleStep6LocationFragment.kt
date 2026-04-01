package com.brunocodex.kotlinproject.fragments

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Filter
import android.widget.Filterable
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.brunocodex.kotlinproject.R
import com.brunocodex.kotlinproject.components.BankPriceInputEditText
import com.brunocodex.kotlinproject.utils.ApiClient
import com.brunocodex.kotlinproject.utils.NominatimResult
import com.brunocodex.kotlinproject.utils.StepValidatable
import com.brunocodex.kotlinproject.viewmodels.VehicleRegisterViewModel
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.text.Normalizer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class VehicleStep6LocationFragment :
    Fragment(R.layout.fragment_vehicle_step6_location),
    StepValidatable {

    private val vehicleViewModel: VehicleRegisterViewModel by activityViewModels()
    private val firebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val firestore by lazy { FirebaseFirestore.getInstance() }

    private data class DayRowViews(
        val startLayout: TextInputLayout,
        val endLayout: TextInputLayout
    )

    private data class ProfileAddress(
        val street: String?,
        val number: String?,
        val neighborhood: String?,
        val city: String?,
        val state: String?,
        val cep: String?,
        val latitude: Double? = null,
        val longitude: Double? = null
    ) {
        fun isComplete(): Boolean {
            return !street.isNullOrBlank() &&
                !number.isNullOrBlank() &&
                !neighborhood.isNullOrBlank() &&
                !city.isNullOrBlank() &&
                !state.isNullOrBlank() &&
                !cep.isNullOrBlank()
        }

        fun hasAnyValue(): Boolean {
            return listOf(street, number, neighborhood, city, state, cep).any { !it.isNullOrBlank() }
        }

        fun formatted(): String {
            val line1 = listOf(street, number).mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
                .joinToString(", ")
            val line2Left = neighborhood?.trim().orEmpty()
            val line2Right = listOf(city, state).mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
                .joinToString("/")
            val line2 = listOf(line2Left, line2Right).filter { it.isNotBlank() }.joinToString(" - ")
            val zip = cep?.trim().orEmpty()

            return listOf(line1, line2, zip).filter { it.isNotBlank() }.joinToString("\n")
        }
    }

    private sealed class DropRow {
        data object Loading : DropRow()
        data object Empty : DropRow()
        data class Result(val item: NominatimResult) : DropRow()
    }

    private class AddressDropAdapter(
        private val context: Context,
        private val inflater: LayoutInflater
    ) : BaseAdapter(), Filterable {

        private val items = mutableListOf<DropRow>()

        fun submit(newItems: List<DropRow>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun getCount(): Int = items.size

        override fun getItem(position: Int): DropRow = items[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun isEnabled(position: Int): Boolean {
            return getItem(position) is DropRow.Result
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: inflater.inflate(R.layout.item_address_dropdown, parent, false)
            val rowText = view.findViewById<TextView>(R.id.rowText)
            val rowProgress = view.findViewById<ProgressBar>(R.id.rowProgress)

            when (val row = getItem(position)) {
                is DropRow.Loading -> {
                    rowText.text = context.getString(R.string.address_dropdown_loading)
                    rowText.alpha = 0.9f
                    rowProgress.visibility = View.VISIBLE
                }

                is DropRow.Empty -> {
                    rowText.text = context.getString(R.string.address_dropdown_empty)
                    rowText.alpha = 0.6f
                    rowProgress.visibility = View.GONE
                }

                is DropRow.Result -> {
                    rowText.text = row.item.displayName.orEmpty()
                    rowText.alpha = 1f
                    rowProgress.visibility = View.GONE
                }
            }

            return view
        }

        private val noFilter = object : Filter() {
            override fun performFiltering(constraint: CharSequence?): FilterResults {
                return FilterResults().apply {
                    values = items
                    count = items.size
                }
            }

            override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                notifyDataSetChanged()
            }
        }

        override fun getFilter(): Filter = noFilter
    }

    private val queryFlow = MutableStateFlow("")
    private var ignoreAddressTextChanges = false
    private var profileAddress: ProfileAddress? = null
    private val dayRows: LinkedHashMap<String, DayRowViews> = linkedMapOf()

    private lateinit var pickupOptionsContainer: View
    private lateinit var pickupProfileAddressContainer: View
    private lateinit var pickupSpecificAddressContainer: View
    private lateinit var deliveryFeeContainer: View

    private lateinit var cbPickupOnLocation: MaterialCheckBox
    private lateinit var cbDeliveryByFee: MaterialCheckBox
    private lateinit var cbPickupProfileAddressConfirm: MaterialCheckBox

    private lateinit var rgPickupAddressMode: RadioGroup

    private lateinit var deliveryRadiusLayout: TextInputLayout
    private lateinit var deliveryFeeLayout: TextInputLayout
    private lateinit var deliveryFeeInput: BankPriceInputEditText
    private lateinit var deliveryRadiusInput: TextInputEditText

    private lateinit var pickupStreetLayout: TextInputLayout
    private lateinit var pickupNumberLayout: TextInputLayout
    private lateinit var pickupNeighborhoodLayout: TextInputLayout
    private lateinit var pickupCityLayout: TextInputLayout
    private lateinit var pickupStateLayout: TextInputLayout
    private lateinit var pickupCepLayout: TextInputLayout

    private lateinit var pickupAddressSearchInput: MaterialAutoCompleteTextView
    private lateinit var pickupStreetInput: TextInputEditText
    private lateinit var pickupNumberInput: TextInputEditText
    private lateinit var pickupNeighborhoodInput: TextInputEditText
    private lateinit var pickupCityInput: TextInputEditText
    private lateinit var pickupStateInput: TextInputEditText
    private lateinit var pickupCepInput: TextInputEditText

    private lateinit var tvProfileAddressValue: TextView
    private lateinit var tvDeliveryOptionError: TextView
    private lateinit var tvPickupAddressModeError: TextView
    private lateinit var tvPickupProfileAddressError: TextView
    private lateinit var tvScheduleError: TextView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<TextView>(R.id.stepHeadline).text = getString(R.string.vehicle_step6_headline)
        view.findViewById<TextView>(R.id.stepSubtitle).text = getString(R.string.vehicle_step6_subtitle)

        bindViews(view)
        restoreInitialState()
        setupDeliveryOptions()
        setupPickupAddressMode()
        setupSpecificAddressAutoComplete()
        setupScheduleRows(view.findViewById(R.id.scheduleContainer))
        loadProfileAddress()
    }

    private fun bindViews(root: View) {
        pickupOptionsContainer = root.findViewById(R.id.pickupOptionsContainer)
        pickupProfileAddressContainer = root.findViewById(R.id.pickupProfileAddressContainer)
        pickupSpecificAddressContainer = root.findViewById(R.id.pickupSpecificAddressContainer)
        deliveryFeeContainer = root.findViewById(R.id.deliveryFeeContainer)

        cbPickupOnLocation = root.findViewById(R.id.cbPickupOnLocation)
        cbDeliveryByFee = root.findViewById(R.id.cbDeliveryByFee)
        cbPickupProfileAddressConfirm = root.findViewById(R.id.cbPickupProfileAddressConfirm)
        rgPickupAddressMode = root.findViewById(R.id.rgPickupAddressMode)

        deliveryRadiusLayout = root.findViewById(R.id.deliveryRadiusLayout)
        deliveryFeeLayout = root.findViewById(R.id.deliveryFeeLayout)
        deliveryFeeInput = root.findViewById(R.id.deliveryFeeInput)
        deliveryRadiusInput = root.findViewById(R.id.deliveryRadiusInput)

        pickupStreetLayout = root.findViewById(R.id.pickupStreetLayout)
        pickupNumberLayout = root.findViewById(R.id.pickupNumberLayout)
        pickupNeighborhoodLayout = root.findViewById(R.id.pickupNeighborhoodLayout)
        pickupCityLayout = root.findViewById(R.id.pickupCityLayout)
        pickupStateLayout = root.findViewById(R.id.pickupStateLayout)
        pickupCepLayout = root.findViewById(R.id.pickupCepLayout)

        pickupAddressSearchInput = root.findViewById(R.id.pickupAddressSearchInput)
        pickupStreetInput = root.findViewById(R.id.pickupStreetInput)
        pickupNumberInput = root.findViewById(R.id.pickupNumberInput)
        pickupNeighborhoodInput = root.findViewById(R.id.pickupNeighborhoodInput)
        pickupCityInput = root.findViewById(R.id.pickupCityInput)
        pickupStateInput = root.findViewById(R.id.pickupStateInput)
        pickupCepInput = root.findViewById(R.id.pickupCepInput)

        tvProfileAddressValue = root.findViewById(R.id.tvPickupProfileAddressValue)
        tvDeliveryOptionError = root.findViewById(R.id.tvDeliveryOptionError)
        tvPickupAddressModeError = root.findViewById(R.id.tvPickupAddressModeError)
        tvPickupProfileAddressError = root.findViewById(R.id.tvPickupProfileAddressError)
        tvScheduleError = root.findViewById(R.id.tvScheduleError)
    }

    private fun restoreInitialState() {
        cbPickupOnLocation.isChecked = vehicleViewModel.pickupOnLocation
        cbDeliveryByFee.isChecked = vehicleViewModel.deliveryByFee
        updateDeliveryFeeVisibility(vehicleViewModel.deliveryByFee)

        bindText(pickupStreetInput, vehicleViewModel.pickupStreet) {
            vehicleViewModel.pickupStreet = it
            vehicleViewModel.pickupLatitude = null
            vehicleViewModel.pickupLongitude = null
            if (!it.isNullOrBlank()) pickupStreetLayout.error = null
        }
        bindText(pickupNumberInput, vehicleViewModel.pickupNumber) {
            vehicleViewModel.pickupNumber = it
            vehicleViewModel.pickupLatitude = null
            vehicleViewModel.pickupLongitude = null
            if (!it.isNullOrBlank()) pickupNumberLayout.error = null
        }
        bindText(pickupNeighborhoodInput, vehicleViewModel.pickupNeighborhood) {
            vehicleViewModel.pickupNeighborhood = it
            vehicleViewModel.pickupLatitude = null
            vehicleViewModel.pickupLongitude = null
            if (!it.isNullOrBlank()) pickupNeighborhoodLayout.error = null
        }
        bindText(pickupCityInput, vehicleViewModel.pickupCity) {
            vehicleViewModel.pickupCity = it
            vehicleViewModel.pickupLatitude = null
            vehicleViewModel.pickupLongitude = null
            if (!it.isNullOrBlank()) pickupCityLayout.error = null
        }
        bindText(pickupStateInput, vehicleViewModel.pickupState) {
            vehicleViewModel.pickupState = it
            vehicleViewModel.pickupLatitude = null
            vehicleViewModel.pickupLongitude = null
            if (!it.isNullOrBlank()) pickupStateLayout.error = null
        }
        bindText(pickupCepInput, vehicleViewModel.pickupCep) {
            vehicleViewModel.pickupCep = it
            vehicleViewModel.pickupLatitude = null
            vehicleViewModel.pickupLongitude = null
            if (!it.isNullOrBlank()) pickupCepLayout.error = null
        }

        ignoreAddressTextChanges = true
        pickupAddressSearchInput.setText(vehicleViewModel.pickupSearchQuery.orEmpty(), false)
        ignoreAddressTextChanges = false
        queryFlow.value = vehicleViewModel.pickupSearchQuery.orEmpty()

        pickupAddressSearchInput.doOnTextChanged { text, _, _, _ ->
            if (ignoreAddressTextChanges) return@doOnTextChanged
            val value = text?.toString().orEmpty()
            vehicleViewModel.pickupSearchQuery = value
            queryFlow.value = value
        }

        bindText(deliveryRadiusInput, vehicleViewModel.deliveryRadiusKm) {
            vehicleViewModel.deliveryRadiusKm = it
            if (!it.isNullOrBlank()) deliveryRadiusLayout.error = null
        }
        bindBankPrice(deliveryFeeInput, vehicleViewModel.deliveryFee) { value ->
            if (vehicleViewModel.deliveryByFee) {
                vehicleViewModel.deliveryFee = value
                if (!value.isNullOrBlank()) deliveryFeeLayout.error = null
            }
        }

        cbPickupProfileAddressConfirm.isChecked = vehicleViewModel.pickupProfileAddressConfirmed
        cbPickupProfileAddressConfirm.setOnCheckedChangeListener { _, isChecked ->
            vehicleViewModel.pickupProfileAddressConfirmed = isChecked
            if (isChecked) tvPickupProfileAddressError.visibility = View.GONE
        }

        if (vehicleViewModel.pickupOnLocation && vehicleViewModel.pickupAddressMode.isNullOrBlank()) {
            vehicleViewModel.pickupAddressMode = VehicleRegisterViewModel.PICKUP_ADDRESS_MODE_PROFILE
        }
        restorePickupModeSelection()
        refreshPickupSections()
    }

    private fun bindText(
        input: TextInputEditText,
        initial: String?,
        onChange: (String?) -> Unit
    ) {
        input.setText(initial.orEmpty())
        input.doAfterTextChanged { onChange(it?.toString()?.trim()) }
    }

    private fun bindBankPrice(
        input: BankPriceInputEditText,
        initialValue: String?,
        onChange: (String?) -> Unit
    ) {
        input.bindBankPrice(initialValue) { formattedValue ->
            onChange(formattedValue)
        }
    }

    private fun setupDeliveryOptions() {
        cbPickupOnLocation.setOnCheckedChangeListener { _, isChecked ->
            vehicleViewModel.pickupOnLocation = isChecked
            if (isChecked && vehicleViewModel.pickupAddressMode.isNullOrBlank()) {
                vehicleViewModel.pickupAddressMode = VehicleRegisterViewModel.PICKUP_ADDRESS_MODE_PROFILE
                restorePickupModeSelection()
            }
            refreshPickupSections()
            if (isChecked || vehicleViewModel.deliveryByFee) {
                tvDeliveryOptionError.visibility = View.GONE
            }
        }

        cbDeliveryByFee.setOnCheckedChangeListener { _, isChecked ->
            vehicleViewModel.deliveryByFee = isChecked
            updateDeliveryFeeVisibility(isChecked)

            if (!isChecked) {
                deliveryRadiusLayout.error = null
                deliveryFeeLayout.error = null
                vehicleViewModel.deliveryRadiusKm = null
                vehicleViewModel.deliveryFee = null
            } else {
                vehicleViewModel.deliveryFee = deliveryFeeInput.text?.toString()?.trim().orEmpty()
                    .ifBlank { null }
                if (!vehicleViewModel.deliveryFee.isNullOrBlank()) {
                    deliveryFeeLayout.error = null
                }
            }

            if (vehicleViewModel.pickupOnLocation || isChecked) {
                tvDeliveryOptionError.visibility = View.GONE
            }
        }
    }

    private fun setupPickupAddressMode() {
        rgPickupAddressMode.setOnCheckedChangeListener { _, checkedId ->
            val previousMode = vehicleViewModel.pickupAddressMode
            vehicleViewModel.pickupAddressMode = when (checkedId) {
                R.id.rbPickupProfileAddress -> VehicleRegisterViewModel.PICKUP_ADDRESS_MODE_PROFILE
                R.id.rbPickupSpecificAddress -> VehicleRegisterViewModel.PICKUP_ADDRESS_MODE_SPECIFIC
                else -> null
            }

            tvPickupAddressModeError.visibility = View.GONE

            if (vehicleViewModel.pickupAddressMode == VehicleRegisterViewModel.PICKUP_ADDRESS_MODE_PROFILE) {
                applyProfileAddressToViewModel()
                clearSpecificAddressErrors()
            } else {
                if (previousMode == VehicleRegisterViewModel.PICKUP_ADDRESS_MODE_PROFILE) {
                    clearSpecificPickupAddressFields()
                }
                vehicleViewModel.pickupProfileAddressConfirmed = false
                cbPickupProfileAddressConfirm.isChecked = false
                tvPickupProfileAddressError.visibility = View.GONE
            }

            refreshPickupSections()
        }
    }

    private fun restorePickupModeSelection() {
        when (vehicleViewModel.pickupAddressMode) {
            VehicleRegisterViewModel.PICKUP_ADDRESS_MODE_PROFILE -> {
                rgPickupAddressMode.check(R.id.rbPickupProfileAddress)
            }

            VehicleRegisterViewModel.PICKUP_ADDRESS_MODE_SPECIFIC -> {
                rgPickupAddressMode.check(R.id.rbPickupSpecificAddress)
            }

            else -> rgPickupAddressMode.clearCheck()
        }
    }

    private fun refreshPickupSections() {
        val pickupEnabled = vehicleViewModel.pickupOnLocation
        pickupOptionsContainer.visibility = if (pickupEnabled) View.VISIBLE else View.GONE

        val profileMode = pickupEnabled &&
            vehicleViewModel.pickupAddressMode == VehicleRegisterViewModel.PICKUP_ADDRESS_MODE_PROFILE
        val specificMode = pickupEnabled &&
            vehicleViewModel.pickupAddressMode == VehicleRegisterViewModel.PICKUP_ADDRESS_MODE_SPECIFIC

        pickupProfileAddressContainer.visibility = if (profileMode) View.VISIBLE else View.GONE
        pickupSpecificAddressContainer.visibility = if (specificMode) View.VISIBLE else View.GONE

        if (!pickupEnabled) {
            tvPickupAddressModeError.visibility = View.GONE
            tvPickupProfileAddressError.visibility = View.GONE
            clearSpecificAddressErrors()
        }

        updateProfileAddressUi()
    }

    private fun updateDeliveryFeeVisibility(visible: Boolean) {
        deliveryFeeContainer.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun loadProfileAddress() {
        val uid = firebaseAuth.currentUser?.uid.orEmpty()
        if (uid.isBlank()) {
            profileAddress = null
            updateProfileAddressUi()
            return
        }

        firestore.collection("users").document(uid).get()
            .addOnSuccessListener { doc ->
                val address = ProfileAddress(
                    street = doc.getString("street"),
                    number = doc.getString("number"),
                    neighborhood = doc.getString("neighborhood"),
                    city = doc.getString("city"),
                    state = doc.getString("state"),
                    cep = doc.getString("cep"),
                    latitude = readDoubleFromAny(
                        doc.getDouble("latitude"),
                        doc.getDouble("lat"),
                        (doc.get("latitude") as? Number)?.toDouble(),
                        (doc.get("lat") as? Number)?.toDouble(),
                        (doc.get("latitude") as? String)?.toDoubleOrNull(),
                        (doc.get("lat") as? String)?.toDoubleOrNull()
                    ),
                    longitude = readDoubleFromAny(
                        doc.getDouble("longitude"),
                        doc.getDouble("lng"),
                        doc.getDouble("lon"),
                        (doc.get("longitude") as? Number)?.toDouble(),
                        (doc.get("lng") as? Number)?.toDouble(),
                        (doc.get("lon") as? Number)?.toDouble(),
                        (doc.get("longitude") as? String)?.toDoubleOrNull(),
                        (doc.get("lng") as? String)?.toDoubleOrNull(),
                        (doc.get("lon") as? String)?.toDoubleOrNull()
                    )
                )
                profileAddress = address.takeIf { it.hasAnyValue() }
                updateProfileAddressUi()

                if ((profileAddress?.latitude == null || profileAddress?.longitude == null) &&
                    profileAddress?.isComplete() == true
                ) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        val resolved = resolveCoordinatesForAddress(profileAddress ?: return@launch)
                        if (resolved != null) {
                            profileAddress = profileAddress?.copy(
                                latitude = resolved.first,
                                longitude = resolved.second
                            )
                            updateProfileAddressUi()
                        }
                    }
                }
            }
            .addOnFailureListener {
                profileAddress = null
                updateProfileAddressUi()
            }
    }

    private fun updateProfileAddressUi() {
        val address = profileAddress
        val addressText = if (address?.isComplete() == true) {
            address.formatted()
        } else {
            getString(R.string.vehicle_pickup_profile_address_unavailable)
        }
        tvProfileAddressValue.text = addressText

        if (vehicleViewModel.pickupAddressMode == VehicleRegisterViewModel.PICKUP_ADDRESS_MODE_PROFILE &&
            vehicleViewModel.pickupOnLocation
        ) {
            applyProfileAddressToViewModel()
        }
    }

    private fun applyProfileAddressToViewModel() {
        val address = profileAddress
        if (address?.isComplete() != true) {
            vehicleViewModel.pickupStreet = null
            vehicleViewModel.pickupNumber = null
            vehicleViewModel.pickupNeighborhood = null
            vehicleViewModel.pickupCity = null
            vehicleViewModel.pickupState = null
            vehicleViewModel.pickupCep = null
            vehicleViewModel.pickupLatitude = null
            vehicleViewModel.pickupLongitude = null
            return
        }

        vehicleViewModel.pickupStreet = address.street?.trim()
        vehicleViewModel.pickupNumber = address.number?.trim()
        vehicleViewModel.pickupNeighborhood = address.neighborhood?.trim()
        vehicleViewModel.pickupCity = address.city?.trim()
        vehicleViewModel.pickupState = address.state?.trim()
        vehicleViewModel.pickupCep = address.cep?.trim()
        vehicleViewModel.pickupLatitude = address.latitude
        vehicleViewModel.pickupLongitude = address.longitude
    }

    private fun setupSpecificAddressAutoComplete() {
        val adapter = AddressDropAdapter(requireContext(), layoutInflater)
        pickupAddressSearchInput.setAdapter(adapter)
        pickupAddressSearchInput.threshold = 1

        pickupAddressSearchInput.setOnItemClickListener { parent, _, position, _ ->
            val row = parent.getItemAtPosition(position) as? DropRow ?: return@setOnItemClickListener
            if (row !is DropRow.Result) return@setOnItemClickListener

            val picked = row.item
            ignoreAddressTextChanges = true
            pickupAddressSearchInput.setText(picked.displayName.orEmpty(), false)
            ignoreAddressTextChanges = false
            pickupAddressSearchInput.dismissDropDown()

            vehicleViewModel.pickupSearchQuery = picked.displayName.orEmpty()
            applyPickedAddress(picked)

            if (pickupNumberInput.text.isNullOrBlank()) {
                pickupNumberInput.requestFocus()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            queryFlow
                .map { it.trim() }
                .distinctUntilChanged()
                .debounce(650)
                .collectLatest { raw ->
                    if (!pickupAddressSearchInput.hasFocus()) return@collectLatest
                    if (vehicleViewModel.pickupAddressMode != VehicleRegisterViewModel.PICKUP_ADDRESS_MODE_SPECIFIC) {
                        return@collectLatest
                    }

                    if (raw.length < 3) {
                        adapter.submit(emptyList())
                        pickupAddressSearchInput.dismissDropDown()
                        return@collectLatest
                    }

                    adapter.submit(listOf(DropRow.Loading))
                    pickupAddressSearchInput.post {
                        if (pickupAddressSearchInput.hasFocus()) pickupAddressSearchInput.showDropDown()
                    }

                    val city = pickupCityInput.text?.toString().orEmpty().trim()
                    val uf = pickupStateInput.text?.toString().orEmpty().trim()
                    val merged = searchMerged(raw, city, uf)
                    val ranked = rankResults(raw, merged)
                        .filter { !it.displayName.isNullOrBlank() }
                        .take(8)

                    if (ranked.isEmpty()) {
                        adapter.submit(listOf(DropRow.Empty))
                        pickupAddressSearchInput.post {
                            if (pickupAddressSearchInput.hasFocus()) pickupAddressSearchInput.showDropDown()
                        }
                        return@collectLatest
                    }

                    adapter.submit(ranked.map { DropRow.Result(it) })
                    pickupAddressSearchInput.post {
                        if (pickupAddressSearchInput.hasFocus()) pickupAddressSearchInput.showDropDown()
                    }
                }
        }
    }

    private fun applyPickedAddress(picked: NominatimResult) {
        val address = picked.address
        pickupStreetInput.setText(address?.road.orEmpty())
        pickupNumberInput.setText(address?.houseNumber.orEmpty())
        pickupNeighborhoodInput.setText(address?.suburb ?: address?.neighbourhood ?: "")
        pickupCityInput.setText(address?.city ?: address?.town ?: address?.municipality ?: "")
        pickupStateInput.setText(toUf(address?.state.orEmpty()))
        pickupCepInput.setText(formatCep(address?.postcode.orEmpty()))
        val coords = parseLatLngFromResult(picked)
        vehicleViewModel.pickupLatitude = coords?.first
        vehicleViewModel.pickupLongitude = coords?.second
    }

    private suspend fun searchMerged(raw: String, city: String, uf: String): List<NominatimResult> {
        val context = buildContext(city, uf)
        val results1 = safeSearch(withContext(raw, context))
        val needFallback = shouldTryAccentFallback(raw, results1)
        if (!needFallback) {
            return results1.distinctBy { it.displayName.orEmpty() }
        }

        val fallbacks = buildAccentFallbackQueries(raw, maxVariants = 8)
        if (fallbacks.isEmpty()) {
            return results1.distinctBy { it.displayName.orEmpty() }
        }

        val merged = results1.toMutableList()
        for (query in fallbacks) {
            delay(220)
            merged += safeSearch(withContext(query, context))
        }
        return merged.distinctBy { it.displayName.orEmpty() }
    }

    private suspend fun safeSearch(query: String): List<NominatimResult> {
        return runCatching { ApiClient.nominatim.search(query = query) }.getOrElse { emptyList() }
    }

    private suspend fun resolveCoordinatesForAddress(address: ProfileAddress): Pair<Double, Double>? {
        val query = listOf(
            address.street,
            address.number,
            address.neighborhood,
            address.city,
            address.state,
            address.cep,
            "Brasil"
        ).mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
            .joinToString(", ")
        if (query.isBlank()) return null

        return safeSearch(query)
            .asSequence()
            .mapNotNull { parseLatLngFromResult(it) }
            .firstOrNull()
    }

    private fun parseLatLngFromResult(result: NominatimResult): Pair<Double, Double>? {
        val latitude = result.lat?.toDoubleOrNull() ?: return null
        val longitude = result.lon?.toDoubleOrNull() ?: return null
        return latitude to longitude
    }

    private fun readDoubleFromAny(vararg values: Double?): Double? {
        return values.firstOrNull { it != null }
    }

    private fun shouldTryAccentFallback(raw: String, results: List<NominatimResult>): Boolean {
        val lastToken = normalizeForSearch(raw).lowercase().split(Regex("\\s+")).lastOrNull().orEmpty()
        if (lastToken.length < 5) return false
        if (results.isEmpty()) return true
        return results.none { result ->
            val display = normalizeForSearch(result.displayName.orEmpty()).lowercase()
            display.contains(lastToken)
        }
    }

    private fun buildAccentFallbackQueries(input: String, maxVariants: Int): List<String> {
        val parts = input.trim().split(Regex("\\s+")).toMutableList()
        if (parts.isEmpty()) return emptyList()

        val last = parts.last()
        val variants = mutableListOf<String>()

        for (index in last.indices) {
            val current = last[index]
            val options = when (current.lowercaseChar()) {
                'a' -> charArrayOf('\u00E1', '\u00E0', '\u00E2', '\u00E3')
                'e' -> charArrayOf('\u00E9', '\u00EA')
                'i' -> charArrayOf('\u00ED')
                'o' -> charArrayOf('\u00F3', '\u00F4', '\u00F5')
                'u' -> charArrayOf('\u00FA')
                'c' -> charArrayOf('\u00E7')
                else -> null
            } ?: continue

            for (option in options) {
                val output = if (current.isUpperCase()) option.uppercaseChar() else option
                val updated = StringBuilder(last).apply { setCharAt(index, output) }.toString()
                variants += updated
            }
        }

        return variants.asSequence()
            .filter { it != last }
            .distinct()
            .take(maxVariants)
            .map { token ->
                parts[parts.lastIndex] = token
                parts.joinToString(" ")
            }
            .toList()
    }

    private fun buildContext(city: String, uf: String): String {
        val parts = mutableListOf<String>()
        if (city.isNotBlank()) parts += city
        if (uf.isNotBlank()) parts += uf
        parts += "Brasil"
        return parts.joinToString(", ")
    }

    private fun withContext(query: String, context: String): String {
        return if (context.isBlank()) query else "$query, $context"
    }

    private fun rankResults(userQuery: String, items: List<NominatimResult>): List<NominatimResult> {
        val normalizedQuery = normalizeForSearch(userQuery).lowercase()
        val lastToken = normalizedQuery.split(Regex("\\s+")).lastOrNull().orEmpty()

        fun score(display: String): Int {
            val normalizedDisplay = normalizeForSearch(display).lowercase()
            var points = 0
            if (normalizedDisplay.contains(normalizedQuery)) points += 60
            if (normalizedDisplay.startsWith(normalizedQuery)) points += 25
            if (lastToken.isNotBlank()) {
                val regex = Regex("""\\b${Regex.escape(lastToken)}\\b""")
                if (regex.containsMatchIn(normalizedDisplay)) points += 50
            }
            return points
        }

        return items.distinctBy { it.displayName.orEmpty() }
            .sortedByDescending { score(it.displayName.orEmpty()) }
    }

    private fun normalizeForSearch(input: String): String {
        val nfd = Normalizer.normalize(input, Normalizer.Form.NFD)
        return nfd.replace("\\p{Mn}+".toRegex(), "").replace(Regex("\\s+"), " ").trim()
    }

    private fun formatCep(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return if (digits.length == 8) {
            "${digits.substring(0, 5)}-${digits.substring(5)}"
        } else {
            raw
        }
    }

    private fun toUf(state: String): String {
        val value = normalizeForSearch(state).lowercase()
        val map = mapOf(
            "acre" to "AC",
            "alagoas" to "AL",
            "amapa" to "AP",
            "amazonas" to "AM",
            "bahia" to "BA",
            "ceara" to "CE",
            "distrito federal" to "DF",
            "espirito santo" to "ES",
            "goias" to "GO",
            "maranhao" to "MA",
            "mato grosso" to "MT",
            "mato grosso do sul" to "MS",
            "minas gerais" to "MG",
            "para" to "PA",
            "paraiba" to "PB",
            "parana" to "PR",
            "pernambuco" to "PE",
            "piaui" to "PI",
            "rio de janeiro" to "RJ",
            "rio grande do norte" to "RN",
            "rio grande do sul" to "RS",
            "rondonia" to "RO",
            "roraima" to "RR",
            "santa catarina" to "SC",
            "sao paulo" to "SP",
            "sergipe" to "SE",
            "tocantins" to "TO"
        )
        return map[value] ?: state.take(2).uppercase()
    }

    private fun setupScheduleRows(container: LinearLayout) {
        container.removeAllViews()
        dayRows.clear()

        val days = listOf(
            VehicleRegisterViewModel.DAY_MON to getString(R.string.vehicle_day_mon),
            VehicleRegisterViewModel.DAY_TUE to getString(R.string.vehicle_day_tue),
            VehicleRegisterViewModel.DAY_WED to getString(R.string.vehicle_day_wed),
            VehicleRegisterViewModel.DAY_THU to getString(R.string.vehicle_day_thu),
            VehicleRegisterViewModel.DAY_FRI to getString(R.string.vehicle_day_fri),
            VehicleRegisterViewModel.DAY_SAT to getString(R.string.vehicle_day_sat),
            VehicleRegisterViewModel.DAY_SUN to getString(R.string.vehicle_day_sun)
        )

        days.forEach { (dayKey, dayLabel) ->
            val row = layoutInflater.inflate(R.layout.item_day_schedule, container, false)
            val schedule = vehicleViewModel.weeklySchedule[dayKey] ?: VehicleRegisterViewModel.DaySchedule()
            vehicleViewModel.weeklySchedule[dayKey] = schedule

            val tvDayName = row.findViewById<TextView>(R.id.tvDayName)
            val cbEnabled = row.findViewById<MaterialCheckBox>(R.id.cbDayEnabled)
            val cbAllDay = row.findViewById<MaterialCheckBox>(R.id.cbDayAllDay)
            val timeContainer = row.findViewById<View>(R.id.timeInputsContainer)
            val startLayout = row.findViewById<TextInputLayout>(R.id.startTimeLayout)
            val endLayout = row.findViewById<TextInputLayout>(R.id.endTimeLayout)
            val startInput = row.findViewById<TextInputEditText>(R.id.startTimeInput)
            val endInput = row.findViewById<TextInputEditText>(R.id.endTimeInput)

            tvDayName.text = dayLabel
            cbEnabled.isChecked = schedule.enabled
            cbAllDay.isChecked = schedule.allDay
            startInput.setText(schedule.startTime)
            endInput.setText(schedule.endTime)
            timeContainer.visibility = if (schedule.enabled) View.VISIBLE else View.GONE
            updateTimeInputsEnabledState(
                schedule = schedule,
                startLayout = startLayout,
                endLayout = endLayout,
                startInput = startInput,
                endInput = endInput
            )

            cbEnabled.setOnCheckedChangeListener { _, isChecked ->
                schedule.enabled = isChecked
                timeContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
                updateTimeInputsEnabledState(
                    schedule = schedule,
                    startLayout = startLayout,
                    endLayout = endLayout,
                    startInput = startInput,
                    endInput = endInput
                )
                if (!isChecked) {
                    startLayout.error = null
                    endLayout.error = null
                }
                if (isAtLeastOneDayEnabled()) {
                    tvScheduleError.visibility = View.GONE
                }
            }

            cbAllDay.setOnCheckedChangeListener { _, isChecked ->
                schedule.allDay = isChecked
                if (isChecked) {
                    schedule.startTime = ""
                    schedule.endTime = ""
                    startInput.setText("")
                    endInput.setText("")
                    startLayout.error = null
                    endLayout.error = null
                }
                updateTimeInputsEnabledState(
                    schedule = schedule,
                    startLayout = startLayout,
                    endLayout = endLayout,
                    startInput = startInput,
                    endInput = endInput
                )
                if (isAtLeastOneDayEnabled()) {
                    tvScheduleError.visibility = View.GONE
                }
            }

            startInput.doAfterTextChanged {
                schedule.startTime = it?.toString()?.trim().orEmpty()
                if (schedule.startTime.isNotBlank()) startLayout.error = null
            }

            endInput.doAfterTextChanged {
                schedule.endTime = it?.toString()?.trim().orEmpty()
                if (schedule.endTime.isNotBlank()) endLayout.error = null
            }

            dayRows[dayKey] = DayRowViews(startLayout, endLayout)
            container.addView(row)
        }
    }

    private fun updateTimeInputsEnabledState(
        schedule: VehicleRegisterViewModel.DaySchedule,
        startLayout: TextInputLayout,
        endLayout: TextInputLayout,
        startInput: TextInputEditText,
        endInput: TextInputEditText
    ) {
        val timeInputsEnabled = schedule.enabled && !schedule.allDay
        startLayout.isEnabled = timeInputsEnabled
        endLayout.isEnabled = timeInputsEnabled
        startInput.isEnabled = timeInputsEnabled
        endInput.isEnabled = timeInputsEnabled
    }

    private fun isAtLeastOneDayEnabled(): Boolean {
        return vehicleViewModel.weeklySchedule.values.any { it.enabled }
    }

    override fun validateStep(showErrors: Boolean): Boolean {
        val deliveryOptionOk = vehicleViewModel.pickupOnLocation || vehicleViewModel.deliveryByFee

        val pickupModeOk = !vehicleViewModel.pickupOnLocation ||
            !vehicleViewModel.pickupAddressMode.isNullOrBlank()

        val pickupAddressOk = when {
            !vehicleViewModel.pickupOnLocation -> true
            vehicleViewModel.pickupAddressMode == VehicleRegisterViewModel.PICKUP_ADDRESS_MODE_PROFILE ->
                validateProfileAddress(showErrors)

            vehicleViewModel.pickupAddressMode == VehicleRegisterViewModel.PICKUP_ADDRESS_MODE_SPECIFIC ->
                validateSpecificAddress(showErrors)

            else -> false
        }

        var deliveryFieldsOk = true
        if (vehicleViewModel.deliveryByFee) {
            val radiusOk = !vehicleViewModel.deliveryRadiusKm.isNullOrBlank()
            val feeOk = !vehicleViewModel.deliveryFee.isNullOrBlank()
            deliveryFieldsOk = radiusOk && feeOk
            if (showErrors) {
                deliveryRadiusLayout.error = if (radiusOk) null else getString(R.string.error_required)
                deliveryFeeLayout.error = if (feeOk) null else getString(R.string.error_required)
            }
        } else if (showErrors) {
            deliveryRadiusLayout.error = null
            deliveryFeeLayout.error = null
        }

        val enabledSchedules = vehicleViewModel.weeklySchedule.filterValues { it.enabled }
        val scheduleOk = enabledSchedules.isNotEmpty() && enabledSchedules.values.all {
            it.allDay || (it.startTime.isNotBlank() && it.endTime.isNotBlank())
        }

        if (showErrors) {
            tvDeliveryOptionError.visibility = if (deliveryOptionOk) View.GONE else View.VISIBLE
            tvPickupAddressModeError.visibility = if (pickupModeOk || !vehicleViewModel.pickupOnLocation) {
                View.GONE
            } else {
                View.VISIBLE
            }
            if (vehicleViewModel.pickupAddressMode != VehicleRegisterViewModel.PICKUP_ADDRESS_MODE_PROFILE) {
                tvPickupProfileAddressError.visibility = View.GONE
            }
            tvScheduleError.visibility = if (scheduleOk) View.GONE else View.VISIBLE

            vehicleViewModel.weeklySchedule.forEach { (dayKey, schedule) ->
                val row = dayRows[dayKey] ?: return@forEach
                if (!schedule.enabled || schedule.allDay) {
                    row.startLayout.error = null
                    row.endLayout.error = null
                } else {
                    row.startLayout.error = if (schedule.startTime.isBlank()) {
                        getString(R.string.error_required)
                    } else {
                        null
                    }
                    row.endLayout.error = if (schedule.endTime.isBlank()) {
                        getString(R.string.error_required)
                    } else {
                        null
                    }
                }
            }
        }

        vehicleViewModel.refreshLegacyStep6Fields()

        return deliveryOptionOk && pickupModeOk && pickupAddressOk && deliveryFieldsOk && scheduleOk
    }

    private fun validateProfileAddress(showErrors: Boolean): Boolean {
        val hasProfileAddress = profileAddress?.isComplete() == true
        val confirmed = vehicleViewModel.pickupProfileAddressConfirmed
        val ok = hasProfileAddress && confirmed

        if (showErrors) {
            when {
                !hasProfileAddress -> {
                    tvPickupProfileAddressError.text =
                        getString(R.string.vehicle_error_profile_address_incomplete)
                    tvPickupProfileAddressError.visibility = View.VISIBLE
                }

                !confirmed -> {
                    tvPickupProfileAddressError.text =
                        getString(R.string.vehicle_error_confirm_profile_address)
                    tvPickupProfileAddressError.visibility = View.VISIBLE
                }

                else -> {
                    tvPickupProfileAddressError.visibility = View.GONE
                }
            }
        }

        return ok
    }

    private fun validateSpecificAddress(showErrors: Boolean): Boolean {
        val streetOk = !vehicleViewModel.pickupStreet.isNullOrBlank()
        val numberOk = !vehicleViewModel.pickupNumber.isNullOrBlank()
        val neighborhoodOk = !vehicleViewModel.pickupNeighborhood.isNullOrBlank()
        val cityOk = !vehicleViewModel.pickupCity.isNullOrBlank()
        val stateOk = !vehicleViewModel.pickupState.isNullOrBlank()
        val cepOk = !vehicleViewModel.pickupCep.isNullOrBlank()

        if (showErrors) {
            pickupStreetLayout.error = if (streetOk) null else getString(R.string.error_required)
            pickupNumberLayout.error = if (numberOk) null else getString(R.string.error_required)
            pickupNeighborhoodLayout.error = if (neighborhoodOk) null else getString(R.string.error_required)
            pickupCityLayout.error = if (cityOk) null else getString(R.string.error_required)
            pickupStateLayout.error = if (stateOk) null else getString(R.string.error_required)
            pickupCepLayout.error = if (cepOk) null else getString(R.string.error_required)
        }

        return streetOk && numberOk && neighborhoodOk && cityOk && stateOk && cepOk
    }

    private fun clearSpecificAddressErrors() {
        pickupStreetLayout.error = null
        pickupNumberLayout.error = null
        pickupNeighborhoodLayout.error = null
        pickupCityLayout.error = null
        pickupStateLayout.error = null
        pickupCepLayout.error = null
    }

    private fun clearSpecificPickupAddressFields() {
        vehicleViewModel.pickupSearchQuery = null
        vehicleViewModel.pickupStreet = null
        vehicleViewModel.pickupNumber = null
        vehicleViewModel.pickupNeighborhood = null
        vehicleViewModel.pickupCity = null
        vehicleViewModel.pickupState = null
        vehicleViewModel.pickupCep = null
        vehicleViewModel.pickupLatitude = null
        vehicleViewModel.pickupLongitude = null

        ignoreAddressTextChanges = true
        pickupAddressSearchInput.setText("", false)
        ignoreAddressTextChanges = false
        queryFlow.value = ""

        pickupStreetInput.setText("")
        pickupNumberInput.setText("")
        pickupNeighborhoodInput.setText("")
        pickupCityInput.setText("")
        pickupStateInput.setText("")
        pickupCepInput.setText("")
        clearSpecificAddressErrors()
    }
}
