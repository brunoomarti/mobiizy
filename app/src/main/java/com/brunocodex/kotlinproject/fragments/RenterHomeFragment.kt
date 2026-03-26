package com.brunocodex.kotlinproject.fragments

import android.Manifest
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Filter
import android.widget.Filterable
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.brunocodex.kotlinproject.R
import com.brunocodex.kotlinproject.adapters.ProviderVehicleCardUi
import com.brunocodex.kotlinproject.adapters.ProviderVehiclesAdapter
import com.brunocodex.kotlinproject.services.NearbyVehiclesRepository
import com.brunocodex.kotlinproject.services.SQLiteConfiguration
import com.brunocodex.kotlinproject.utils.ApiClient
import com.brunocodex.kotlinproject.utils.NominatimResult
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.material.color.MaterialColors
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.OnMapReadyCallback
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconAnchor
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale
import java.util.function.Consumer
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import org.maplibre.android.style.expressions.Expression.get

class RenterHomeFragment : Fragment(R.layout.fragment_renter_home), OnMapReadyCallback {

    private var mapView: MapView? = null
    private var mapLibreMap: MapLibreMap? = null
    private var mapViewSavedState: Bundle? = null

    private var collapsedMapContainer: FrameLayout? = null
    private var fullscreenOverlay: FrameLayout? = null
    private var fullscreenMapContainer: FrameLayout? = null
    private var closeFullscreenButton: View? = null
    private var fullscreenLocationControls: View? = null
    private var fullscreenChangeLocationButton: View? = null
    private var fullscreenLocationSearchContainer: View? = null
    private var fullscreenLocationTitle: TextView? = null

    private var homeLocationSearchInput: MaterialAutoCompleteTextView? = null
    private var fullscreenLocationSearchInput: MaterialAutoCompleteTextView? = null
    private var homeUseCurrentLocationButton: View? = null
    private var fullscreenUseCurrentLocationButton: View? = null
    private var homeAddressDropAdapter: AddressDropAdapter? = null
    private var fullscreenAddressDropAdapter: AddressDropAdapter? = null

    private var isMapExpanded = false
    private var isManualLocationOverride = false
    private var isUpdatingSearchTextProgrammatically = false
    private var isCurrentLocationRequestInFlight = false
    private var lastCurrentLocationRequestAtMs = 0L
    private var hasRequestedFreshLocationThisSession = false
    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var currentLocationTokenSource: CancellationTokenSource? = null
    private var locationSearchDebounceJob: Job? = null
    private var resolveLocationAddressJob: Job? = null
    private var hasShownLocationDisabledMessage = false
    private var nearbyVehiclesRepository: NearbyVehiclesRepository? = null
    private var nearbyVehiclesFetchJob: Job? = null
    private var selectedSearchRadiusMeters: Double = NEARBY_SEARCH_RADIUS_METERS
    private val markerBitmapByName = mutableMapOf<String, Bitmap>()
    private lateinit var nearbyVehiclesAdapter: ProviderVehiclesAdapter
    private lateinit var rvNearbyVehicles: RecyclerView
    private lateinit var tvNearbyVehiclesEmpty: TextView
    private lateinit var tvNearbySearchRadius: TextView

    private sealed class DropRow {
        data object Loading : DropRow()
        data object Empty : DropRow()
        data class Result(val item: NominatimResult) : DropRow()
    }

    private class AddressDropAdapter(
        private val ctx: Context,
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

        override fun isEnabled(position: Int): Boolean = getItem(position) is DropRow.Result

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val rowView = convertView ?: inflater.inflate(R.layout.item_address_dropdown, parent, false)
            val rowText = rowView.findViewById<TextView>(R.id.rowText)
            val rowProgress = rowView.findViewById<ProgressBar>(R.id.rowProgress)

            when (val row = getItem(position)) {
                is DropRow.Loading -> {
                    rowText.text = ctx.getString(R.string.address_dropdown_loading)
                    rowText.alpha = 0.9f
                    rowProgress.visibility = View.VISIBLE
                }

                is DropRow.Empty -> {
                    rowText.text = ctx.getString(R.string.address_dropdown_empty)
                    rowText.alpha = 0.6f
                    rowProgress.visibility = View.GONE
                }

                is DropRow.Result -> {
                    rowText.text = row.item.displayName.orEmpty()
                    rowText.alpha = 1f
                    rowProgress.visibility = View.GONE
                }
            }

            return rowView
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

    private val backPressCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            collapseMap(animated = true)
        }
    }

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results.values.any { it }) {
                centerMapOnCurrentLocation(forceFreshRequest = true)
            } else {
                ensureLocationReady(shouldRequestPermission = false)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(requireContext().applicationContext)
        mapViewSavedState = savedInstanceState?.getBundle(MAP_VIEW_STATE_KEY)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        collapsedMapContainer = view.findViewById(R.id.renterHomeMapContainer)
        mapView = view.findViewById(R.id.renterHomeMapView)
        rvNearbyVehicles = view.findViewById(R.id.rvNearbyVehicles)
        tvNearbyVehiclesEmpty = view.findViewById(R.id.tvNearbyVehiclesEmpty)
        tvNearbySearchRadius = view.findViewById(R.id.tvNearbySearchRadius)
        homeLocationSearchInput = view.findViewById(R.id.renterLocationSearchInput)
        homeUseCurrentLocationButton = view.findViewById(R.id.btnRenterUseCurrentLocation)

        fullscreenOverlay = activity?.findViewById(R.id.fullscreenMapOverlay)
        fullscreenMapContainer = activity?.findViewById(R.id.fullscreenMapContainer)
        closeFullscreenButton = activity?.findViewById(R.id.btnCloseFullscreenMap)
        fullscreenLocationControls = activity?.findViewById(R.id.fullscreenLocationControls)
        fullscreenChangeLocationButton = activity?.findViewById(R.id.btnFullscreenChangeLocation)
        fullscreenLocationSearchContainer = activity?.findViewById(R.id.fullscreenLocationSearchContainer)
        fullscreenLocationSearchInput = activity?.findViewById(R.id.fullscreenLocationSearchInput)
        fullscreenUseCurrentLocationButton = activity?.findViewById(R.id.btnFullscreenUseCurrentLocation)
        fullscreenLocationTitle = activity?.findViewById(R.id.tvFullscreenLocationTitle)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())
        nearbyVehiclesRepository = NearbyVehiclesRepository(requireContext().applicationContext)
        nearbyVehiclesAdapter = ProviderVehiclesAdapter {
            if (!isAdded) return@ProviderVehiclesAdapter
            Toast.makeText(requireContext(), getString(R.string.feature_coming_soon), Toast.LENGTH_SHORT).show()
        }
        rvNearbyVehicles.layoutManager = LinearLayoutManager(requireContext())
        rvNearbyVehicles.adapter = nearbyVehiclesAdapter
        rvNearbyVehicles.isNestedScrollingEnabled = false
        rvNearbyVehicles.isVisible = false
        tvNearbyVehiclesEmpty.isVisible = false
        tvNearbySearchRadius.text = getString(
            R.string.renter_home_nearby_search_radius,
            formatSearchRadiusKm(selectedSearchRadiusMeters)
        )
        isManualLocationOverride = readManualLocationOverride()

        val cachedLocation = readCachedLocation()
        val initialLocationLabel = cachedLocation?.addressLabel
            ?.takeIf { it.isNotBlank() }
            ?: getString(R.string.renter_home_location_default_title)
        applyCurrentLocationLabel(initialLocationLabel, forceSearchTextUpdate = false)

        homeAddressDropAdapter = AddressDropAdapter(requireContext(), layoutInflater)
        fullscreenAddressDropAdapter = AddressDropAdapter(requireContext(), layoutInflater)
        setupLocationSearchInput(
            input = homeLocationSearchInput,
            adapter = homeAddressDropAdapter,
            hideFullscreenSearchAfterPick = false
        )
        setupLocationSearchInput(
            input = fullscreenLocationSearchInput,
            adapter = fullscreenAddressDropAdapter,
            hideFullscreenSearchAfterPick = true
        )
        homeUseCurrentLocationButton?.setOnClickListener { useCurrentLocationSelection() }
        fullscreenUseCurrentLocationButton?.setOnClickListener { useCurrentLocationSelection() }
        fullscreenChangeLocationButton?.setOnClickListener { setFullscreenLocationSearchVisible(true) }

        mapView?.onCreate(mapViewSavedState)
        mapView?.getMapAsync(this)

        view.findViewById<View>(R.id.cardRenterMapPreview).setOnClickListener { expandMap() }
        view.findViewById<View>(R.id.mapCardTouchOverlay).setOnClickListener { expandMap() }
        closeFullscreenButton?.setOnClickListener { collapseMap(animated = true) }
        setFullscreenLocationSearchVisible(false)
        updateFullscreenLocationControlsVisibility()

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backPressCallback)
    }

    override fun onMapReady(map: MapLibreMap) {
        mapLibreMap = map
        applyMapVisualStyle(map)
    }

    override fun onStart() {
        super.onStart()
        mapView?.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView?.onResume()
        if (mapLibreMap != null) {
            ensureLocationReady(shouldRequestPermission = false)
        }
    }

    override fun onPause() {
        nearbyVehiclesFetchJob?.cancel()
        nearbyVehiclesFetchJob = null
        locationSearchDebounceJob?.cancel()
        locationSearchDebounceJob = null
        resolveLocationAddressJob?.cancel()
        resolveLocationAddressJob = null
        synchronized(NEARBY_SESSION_LOCK) {
            isLoadingNearbyVehiclesInSession = false
        }
        mapView?.onPause()
        super.onPause()
    }

    override fun onStop() {
        mapView?.onStop()
        super.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView?.onLowMemory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val mapState = Bundle()
        mapView?.onSaveInstanceState(mapState)
        outState.putBundle(MAP_VIEW_STATE_KEY, mapState)
    }

    override fun onDestroyView() {
        collapseMap(animated = false)
        closeFullscreenButton?.setOnClickListener(null)
        homeUseCurrentLocationButton?.setOnClickListener(null)
        fullscreenUseCurrentLocationButton?.setOnClickListener(null)
        fullscreenChangeLocationButton?.setOnClickListener(null)
        mapView?.onDestroy()
        mapView = null
        mapLibreMap = null
        nearbyVehiclesFetchJob?.cancel()
        nearbyVehiclesFetchJob = null
        locationSearchDebounceJob?.cancel()
        locationSearchDebounceJob = null
        resolveLocationAddressJob?.cancel()
        resolveLocationAddressJob = null
        synchronized(NEARBY_SESSION_LOCK) {
            isLoadingNearbyVehiclesInSession = false
        }
        clearNearbyVehicleMarkers()
        nearbyVehiclesRepository = null
        clearMarkerBitmapCache()
        currentLocationTokenSource?.cancel()
        currentLocationTokenSource = null
        fusedLocationClient = null
        hasShownLocationDisabledMessage = false
        isCurrentLocationRequestInFlight = false
        lastCurrentLocationRequestAtMs = 0L
        hasRequestedFreshLocationThisSession = false
        if (::rvNearbyVehicles.isInitialized) {
            rvNearbyVehicles.adapter = null
        }
        homeAddressDropAdapter = null
        fullscreenAddressDropAdapter = null
        homeLocationSearchInput = null
        fullscreenLocationSearchInput = null
        homeUseCurrentLocationButton = null
        fullscreenUseCurrentLocationButton = null
        fullscreenChangeLocationButton = null
        fullscreenLocationTitle = null
        fullscreenLocationSearchContainer = null
        fullscreenLocationControls = null
        collapsedMapContainer = null
        fullscreenOverlay = null
        fullscreenMapContainer = null
        closeFullscreenButton = null
        super.onDestroyView()
    }

    private fun expandMap() {
        if (isMapExpanded) return

        val targetContainer = fullscreenMapContainer ?: return
        moveMapViewTo(targetContainer)

        fullscreenOverlay?.apply {
            alpha = 0f
            visibility = View.VISIBLE
            animate().alpha(1f).setDuration(180L).start()
        }

        isMapExpanded = true
        backPressCallback.isEnabled = true
        setFullscreenLocationSearchVisible(false)
        applyMapUiState()
        ensureLocationReady(shouldRequestPermission = true)
    }

    private fun collapseMap(animated: Boolean) {
        if (!isMapExpanded) return

        moveMapViewTo(collapsedMapContainer)

        val overlay = fullscreenOverlay
        if (animated && overlay?.visibility == View.VISIBLE) {
            overlay.animate()
                .alpha(0f)
                .setDuration(160L)
                .withEndAction {
                    overlay.visibility = View.GONE
                    overlay.alpha = 1f
                }
                .start()
        } else {
            overlay?.visibility = View.GONE
            overlay?.alpha = 1f
        }

        isMapExpanded = false
        backPressCallback.isEnabled = false
        setFullscreenLocationSearchVisible(false)
        applyMapUiState()
    }

    private fun moveMapViewTo(target: ViewGroup?) {
        val map = mapView ?: return
        val destination = target ?: return
        val currentParent = map.parent as? ViewGroup
        if (currentParent === destination) return
        currentParent?.removeView(map)
        destination.addView(map)
    }

    private fun applyMapUiState() {
        mapLibreMap?.uiSettings?.let { uiSettings ->
            uiSettings.setCompassEnabled(isMapExpanded)
            uiSettings.setRotateGesturesEnabled(isMapExpanded)
            uiSettings.setScrollGesturesEnabled(isMapExpanded)
            uiSettings.setTiltGesturesEnabled(isMapExpanded)
            uiSettings.setZoomGesturesEnabled(isMapExpanded)
            uiSettings.setDoubleTapGesturesEnabled(isMapExpanded)
            uiSettings.setQuickZoomGesturesEnabled(isMapExpanded)
        }
        updateFullscreenLocationControlsVisibility()
    }

    private fun updateFullscreenLocationControlsVisibility() {
        fullscreenLocationControls?.isVisible = isMapExpanded
        if (!isMapExpanded) {
            fullscreenChangeLocationButton?.isVisible = false
            fullscreenLocationSearchContainer?.isVisible = false
            return
        }

        val isSearchVisible = fullscreenLocationSearchContainer?.isVisible == true
        fullscreenChangeLocationButton?.isVisible = !isSearchVisible
    }

    private fun setFullscreenLocationSearchVisible(visible: Boolean) {
        val showSearch = visible && isMapExpanded
        fullscreenLocationSearchContainer?.isVisible = showSearch
        fullscreenChangeLocationButton?.isVisible = isMapExpanded && !showSearch

        if (showSearch) {
            val input = fullscreenLocationSearchInput ?: return
            input.requestFocus()
            input.post {
                val textLength = input.text?.length ?: 0
                input.setSelection(textLength)
                if (textLength >= MIN_ADDRESS_QUERY_LENGTH) {
                    input.showDropDown()
                }
            }
        } else {
            fullscreenLocationSearchInput?.clearFocus()
        }
    }

    private fun useCurrentLocationSelection() {
        setFullscreenLocationSearchVisible(false)
        saveManualLocationOverride(false)
        if (hasLocationPermission()) {
            centerMapOnCurrentLocation(forceFreshRequest = true)
        } else {
            requestLocationPermission()
        }
    }

    private fun setupLocationSearchInput(
        input: MaterialAutoCompleteTextView?,
        adapter: AddressDropAdapter?,
        hideFullscreenSearchAfterPick: Boolean
    ) {
        val field = input ?: return
        val searchAdapter = adapter ?: return

        field.setAdapter(searchAdapter)
        field.threshold = 1

        field.doOnTextChanged { text, _, _, _ ->
            if (isUpdatingSearchTextProgrammatically) return@doOnTextChanged
            scheduleAddressSearch(
                input = field,
                adapter = searchAdapter,
                rawQuery = text?.toString().orEmpty()
            )
        }

        field.setOnItemClickListener { parent, _, position, _ ->
            val row = parent.getItemAtPosition(position) as? DropRow ?: return@setOnItemClickListener
            if (row !is DropRow.Result) return@setOnItemClickListener
            onLocationSearchResultSelected(row.item, hideFullscreenSearchAfterPick)
        }
    }

    private fun scheduleAddressSearch(
        input: MaterialAutoCompleteTextView,
        adapter: AddressDropAdapter,
        rawQuery: String
    ) {
        locationSearchDebounceJob?.cancel()
        val query = rawQuery.trim()

        if (query.length < MIN_ADDRESS_QUERY_LENGTH) {
            adapter.submit(emptyList())
            input.dismissDropDown()
            return
        }

        locationSearchDebounceJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(ADDRESS_SEARCH_DEBOUNCE_MS)
            if (!isAdded || view == null) return@launch
            if (!input.hasFocus()) return@launch

            adapter.submit(listOf(DropRow.Loading))
            input.post { if (input.hasFocus()) input.showDropDown() }

            val merged = searchMerged(raw = query, city = "", uf = "")
            val ranked = rankResults(query, merged)
                .filter { !it.displayName.isNullOrBlank() }
                .take(MAX_ADDRESS_SUGGESTIONS)

            if (!isAdded || view == null) return@launch
            if (!input.hasFocus()) return@launch

            if (ranked.isEmpty()) {
                adapter.submit(listOf(DropRow.Empty))
                input.post { if (input.hasFocus()) input.showDropDown() }
                return@launch
            }

            adapter.submit(ranked.map { DropRow.Result(it) })
            input.post { if (input.hasFocus()) input.showDropDown() }
        }
    }

    private fun onLocationSearchResultSelected(
        picked: NominatimResult,
        hideFullscreenSearchAfterPick: Boolean
    ) {
        val coordinates = parseLatLngFromResult(picked)
        if (coordinates == null) {
            if (!isAdded) return
            Toast.makeText(
                requireContext(),
                getString(R.string.renter_home_location_pick_failed),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val selectedCenter = LatLng(coordinates.first, coordinates.second)
        val selectedLabel = picked.displayName
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: formatLatLngLabel(selectedCenter)

        applyManualLocationSelection(
            center = selectedCenter,
            addressLabel = selectedLabel,
            hideFullscreenSearchAfterPick = hideFullscreenSearchAfterPick
        )
    }

    private fun applyManualLocationSelection(
        center: LatLng,
        addressLabel: String,
        hideFullscreenSearchAfterPick: Boolean
    ) {
        saveManualLocationOverride(true)
        resolveLocationAddressJob?.cancel()
        val now = System.currentTimeMillis()
        saveCachedLocation(
            latitude = center.latitude,
            longitude = center.longitude,
            timestampMs = now,
            addressLabel = addressLabel
        )
        mapLibreMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(center, USER_ZOOM))
        loadNearbyVehiclesForCurrentHomeEntry(center)
        applyCurrentLocationLabel(addressLabel, forceSearchTextUpdate = true)
        if (hideFullscreenSearchAfterPick) {
            setFullscreenLocationSearchVisible(false)
        }
    }

    private fun applyCurrentLocationLabel(rawLabel: String?, forceSearchTextUpdate: Boolean) {
        val resolvedLabel = rawLabel
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: getString(R.string.renter_home_location_default_title)

        fullscreenLocationTitle?.text = resolvedLabel

        val shouldSyncSearchText = forceSearchTextUpdate ||
            (homeLocationSearchInput?.hasFocus() != true && fullscreenLocationSearchInput?.hasFocus() != true)
        if (!shouldSyncSearchText) return

        isUpdatingSearchTextProgrammatically = true
        homeLocationSearchInput?.setText(resolvedLabel, false)
        fullscreenLocationSearchInput?.setText(resolvedLabel, false)
        isUpdatingSearchTextProgrammatically = false
    }

    private fun updateLocationLabelForCenter(
        center: LatLng,
        preferredLabel: String?,
        forceSearchTextUpdate: Boolean
    ) {
        val label = preferredLabel?.trim().orEmpty()
        if (label.isNotBlank()) {
            applyCurrentLocationLabel(label, forceSearchTextUpdate)
            return
        }

        val fallbackLabel = formatLatLngLabel(center)
        applyCurrentLocationLabel(fallbackLabel, forceSearchTextUpdate)

        resolveLocationAddressJob?.cancel()
        resolveLocationAddressJob = viewLifecycleOwner.lifecycleScope.launch {
            val resolved = resolveAddressLabel(center) ?: fallbackLabel
            if (!isAdded || view == null) return@launch
            applyCurrentLocationLabel(resolved, forceSearchTextUpdate = false)
            saveCachedLocation(
                latitude = center.latitude,
                longitude = center.longitude,
                timestampMs = System.currentTimeMillis(),
                addressLabel = resolved
            )
        }
    }

    private suspend fun resolveAddressLabel(center: LatLng): String? {
        val appContext = context ?: return null
        if (!Geocoder.isPresent()) return null

        return withContext(Dispatchers.IO) {
            runCatching {
                val geocoder = Geocoder(appContext, Locale.forLanguageTag("pt-BR"))
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(center.latitude, center.longitude, 1)
                    ?.firstOrNull()
                    ?.getAddressLine(0)
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            }.getOrNull()
        }
    }

    private fun formatLatLngLabel(center: LatLng): String {
        return String.format(
            Locale.getDefault(),
            "%.5f, %.5f",
            center.latitude,
            center.longitude
        )
    }

    private suspend fun searchMerged(raw: String, city: String, uf: String): List<NominatimResult> {
        val searchContext = buildSearchContext(city, uf)
        val firstQuery = withSearchContext(raw, searchContext)
        val firstResults = safeSearch(firstQuery)

        val needAccentFallback = shouldTryAccentFallback(raw, firstResults)
        if (!needAccentFallback) {
            return firstResults.distinctBy { it.displayName.orEmpty() }
        }

        val fallbackQueries = buildAccentFallbackQueries(raw, maxVariants = 8)
        if (fallbackQueries.isEmpty()) return firstResults.distinctBy { it.displayName.orEmpty() }

        val merged = firstResults.toMutableList()
        for (query in fallbackQueries) {
            delay(220)
            merged += safeSearch(withSearchContext(query, searchContext))
        }

        return merged.distinctBy { it.displayName.orEmpty() }
    }

    private suspend fun safeSearch(query: String): List<NominatimResult> {
        return runCatching { ApiClient.nominatim.search(query = query) }.getOrElse { emptyList() }
    }

    private fun shouldTryAccentFallback(raw: String, results: List<NominatimResult>): Boolean {
        val lastToken = normalizeForSearch(raw).lowercase().split(Regex("\\s+")).lastOrNull().orEmpty()
        if (lastToken.length < 5) return false
        if (results.isEmpty()) return true

        return results.none { result ->
            val normalizedDisplay = normalizeForSearch(result.displayName.orEmpty()).lowercase()
            normalizedDisplay.contains(lastToken)
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

        return variants
            .asSequence()
            .filter { it != last }
            .distinct()
            .take(maxVariants)
            .map { token ->
                parts[parts.lastIndex] = token
                parts.joinToString(" ")
            }
            .toList()
    }

    private fun buildSearchContext(city: String, uf: String): String {
        val parts = mutableListOf<String>()
        if (city.isNotBlank()) parts += city
        if (uf.isNotBlank()) parts += uf
        parts += "Brasil"
        return parts.joinToString(", ")
    }

    private fun withSearchContext(query: String, context: String): String {
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
                val regex = Regex("""\b${Regex.escape(lastToken)}\b""")
                if (regex.containsMatchIn(normalizedDisplay)) points += 50
            }
            return points
        }

        return items
            .distinctBy { it.displayName.orEmpty() }
            .sortedByDescending { score(it.displayName.orEmpty()) }
    }

    private fun normalizeForSearch(input: String): String {
        val nfd = Normalizer.normalize(input, Normalizer.Form.NFD)
        return nfd.replace("\\p{Mn}+".toRegex(), "").replace(Regex("\\s+"), " ").trim()
    }

    private fun parseLatLngFromResult(result: NominatimResult): Pair<Double, Double>? {
        val latitude = result.lat?.toDoubleOrNull() ?: return null
        val longitude = result.lon?.toDoubleOrNull() ?: return null
        return latitude to longitude
    }

    private fun applyMapVisualStyle(map: MapLibreMap) {
        map.setStyle(Style.Builder().fromJson(buildOsmRasterStyleJson())) {
            applyMapUiState()
            ensureLocationReady(shouldRequestPermission = true)
        }
    }

    private fun buildOsmRasterStyleJson(): String {
        val isNightModeActive =
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val baseTileVariant = if (isNightModeActive) "dark_nolabels" else "light_nolabels"
        val labelTileVariant = if (isNightModeActive) "dark_only_labels" else "light_only_labels"
        val saturation = if (isNightModeActive) -0.35 else -0.55
        val contrast = if (isNightModeActive) 0.05 else -0.05
        val brightnessMin = if (isNightModeActive) 0.03 else 0.18
        val brightnessMax = if (isNightModeActive) 0.70 else 0.96
        val labelsOpacity = if (isNightModeActive) 0.88 else 0.82

        return """
            {
              "version": 8,
              "name": "Calm Raster",
              "sources": {
                "calm-raster": {
                  "type": "raster",
                  "tiles": [
                    "https://a.basemaps.cartocdn.com/$baseTileVariant/{z}/{x}/{y}.png",
                    "https://b.basemaps.cartocdn.com/$baseTileVariant/{z}/{x}/{y}.png",
                    "https://c.basemaps.cartocdn.com/$baseTileVariant/{z}/{x}/{y}.png",
                    "https://d.basemaps.cartocdn.com/$baseTileVariant/{z}/{x}/{y}.png"
                  ],
                  "tileSize": 256,
                  "attribution": "(c) OpenStreetMap contributors, (c) CARTO"
                },
                "calm-labels": {
                  "type": "raster",
                  "tiles": [
                    "https://a.basemaps.cartocdn.com/$labelTileVariant/{z}/{x}/{y}.png",
                    "https://b.basemaps.cartocdn.com/$labelTileVariant/{z}/{x}/{y}.png",
                    "https://c.basemaps.cartocdn.com/$labelTileVariant/{z}/{x}/{y}.png",
                    "https://d.basemaps.cartocdn.com/$labelTileVariant/{z}/{x}/{y}.png"
                  ],
                  "tileSize": 256,
                  "attribution": "(c) OpenStreetMap contributors, (c) CARTO"
                }
              },
              "layers": [
                {
                  "id": "calm-raster-layer",
                  "type": "raster",
                  "source": "calm-raster",
                  "minzoom": 0,
                  "maxzoom": 20,
                  "paint": {
                    "raster-saturation": $saturation,
                    "raster-contrast": $contrast,
                    "raster-brightness-min": $brightnessMin,
                    "raster-brightness-max": $brightnessMax
                  }
                },
                {
                  "id": "calm-labels-layer",
                  "type": "raster",
                  "source": "calm-labels",
                  "minzoom": 0,
                  "maxzoom": 20,
                  "paint": {
                    "raster-opacity": $labelsOpacity
                  }
                }
              ]
            }
        """.trimIndent()
    }

    private fun ensureLocationReady(shouldRequestPermission: Boolean) {
        val cachedLocation = readCachedLocation()
        if (isManualLocationOverride && cachedLocation != null) {
            val manualCenter = LatLng(cachedLocation.latitude, cachedLocation.longitude)
            mapLibreMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(manualCenter, USER_ZOOM))
            loadNearbyVehiclesForCurrentHomeEntry(manualCenter)
            updateLocationLabelForCenter(
                center = manualCenter,
                preferredLabel = cachedLocation.addressLabel,
                forceSearchTextUpdate = false
            )
            return
        }

        if (isManualLocationOverride && cachedLocation == null) {
            saveManualLocationOverride(false)
        }

        if (hasLocationPermission()) {
            centerMapOnCurrentLocation(forceFreshRequest = false)
            return
        }

        val fallbackCenter = cachedLocation?.let { LatLng(it.latitude, it.longitude) } ?: DEFAULT_LOCATION
        val fallbackZoom = if (cachedLocation != null) USER_ZOOM else DEFAULT_ZOOM
        mapLibreMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(fallbackCenter, fallbackZoom))
        loadNearbyVehiclesForCurrentHomeEntry(fallbackCenter)
        updateLocationLabelForCenter(
            center = fallbackCenter,
            preferredLabel = cachedLocation?.addressLabel,
            forceSearchTextUpdate = false
        )
        if (shouldRequestPermission && !isManualLocationOverride) {
            requestLocationPermission()
        }
    }

    private fun requestLocationPermission() {
        locationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    private fun centerMapOnCurrentLocation(forceFreshRequest: Boolean) {
        val map = mapLibreMap ?: return
        var candidateSearchCenter: LatLng? = null
        var candidateAddressLabel: String? = null

        if (!hasLocationPermission()) {
            val fallbackCenter = resolveFallbackSearchCenter()
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(fallbackCenter, DEFAULT_ZOOM))
            loadNearbyVehiclesForCurrentHomeEntry(fallbackCenter)
            val fallbackLabel = readCachedLocation()?.addressLabel
            updateLocationLabelForCenter(
                center = fallbackCenter,
                preferredLabel = fallbackLabel,
                forceSearchTextUpdate = false
            )
            return
        }

        val locationManager = context?.getSystemService(LocationManager::class.java)
        val isLocationEnabled = isLocationServiceEnabled(locationManager)

        val now = System.currentTimeMillis()
        val cached = readCachedLocation()
        val isCachedFresh = cached != null && (now - cached.timestampMs) <= LOCATION_CACHE_MAX_AGE_MS

        if (isCachedFresh) {
            candidateSearchCenter = LatLng(cached.latitude, cached.longitude)
            candidateAddressLabel = cached.addressLabel
            map.moveCamera(
                CameraUpdateFactory.newLatLngZoom(
                    candidateSearchCenter,
                    USER_ZOOM
                )
            )
        }

        val lastKnown = locationManager?.let(::findBestLastKnownLocation)
        val cacheDistanceFromLastKnown = if (cached != null && lastKnown != null) {
            distanceMeters(
                cached.latitude,
                cached.longitude,
                lastKnown.latitude,
                lastKnown.longitude
            )
        } else {
            null
        }

        if (lastKnown != null) {
            val shouldUseLastKnown = forceFreshRequest ||
                !isCachedFresh ||
                (cacheDistanceFromLastKnown != null && cacheDistanceFromLastKnown > LOCATION_CACHE_RADIUS_METERS)

            if (shouldUseLastKnown) {
                candidateSearchCenter = LatLng(lastKnown.latitude, lastKnown.longitude)
                candidateAddressLabel = null
                map.moveCamera(
                    CameraUpdateFactory.newLatLngZoom(
                        candidateSearchCenter,
                        USER_ZOOM
                    )
                )
            } else if (candidateSearchCenter == null) {
                candidateSearchCenter = LatLng(lastKnown.latitude, lastKnown.longitude)
                candidateAddressLabel = cached?.addressLabel
            }
            saveCachedLocation(
                lastKnown.latitude,
                lastKnown.longitude,
                normalizeLocationTimestamp(lastKnown.time, now),
                addressLabel = candidateAddressLabel
            )
        } else if (!isCachedFresh) {
            candidateSearchCenter = DEFAULT_LOCATION
            candidateAddressLabel = null
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(DEFAULT_LOCATION, DEFAULT_ZOOM))
        }

        candidateSearchCenter?.let { center ->
            loadNearbyVehiclesForCurrentHomeEntry(center)
            updateLocationLabelForCenter(
                center = center,
                preferredLabel = candidateAddressLabel,
                forceSearchTextUpdate = false
            )
        }

        if (!isLocationEnabled) {
            showLocationDisabledMessageOnce()
            return
        }

        val shouldRefreshCurrentLocation = forceFreshRequest || shouldRequestFreshLocation(now)
        if (shouldRefreshCurrentLocation) {
            requestCurrentLocation(locationManager, now)
        }
    }

    private fun hasLocationPermission(): Boolean {
        val context = context ?: return false
        val fineGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fineGranted || coarseGranted
    }

    private fun resolveFallbackSearchCenter(): LatLng {
        val now = System.currentTimeMillis()
        val cached = readCachedLocation()
        val hasFreshCache = cached != null && (now - cached.timestampMs) in 0L..LOCATION_CACHE_MAX_AGE_MS
        return if (hasFreshCache) {
            LatLng(cached.latitude, cached.longitude)
        } else {
            DEFAULT_LOCATION
        }
    }

    private fun findBestLastKnownLocation(locationManager: LocationManager): Location? {
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )

        return providers
            .mapNotNull { provider ->
                runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
            }
            .maxByOrNull { it.time }
    }

    private fun requestCurrentLocation(locationManager: LocationManager?, requestTimestampMs: Long) {
        val now = requestTimestampMs
        if (isCurrentLocationRequestInFlight) return
        if ((now - lastCurrentLocationRequestAtMs) < MIN_CURRENT_LOCATION_REQUEST_INTERVAL_MS) return

        isCurrentLocationRequestInFlight = true
        hasRequestedFreshLocationThisSession = true
        lastCurrentLocationRequestAtMs = now
        saveLastRefreshRequestTimestamp(now)

        val client = fusedLocationClient
        if (client == null) {
            Log.w(TAG, "FusedLocationProviderClient unavailable, trying LocationManager fallback.")
            requestCurrentLocationFromLocationManager(locationManager)
            isCurrentLocationRequestInFlight = false
            return
        }

        currentLocationTokenSource?.cancel()
        val tokenSource = CancellationTokenSource()
        currentLocationTokenSource = tokenSource
        val priority = if (hasFineLocationPermission()) {
            Priority.PRIORITY_HIGH_ACCURACY
        } else {
            Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }

        client.getCurrentLocation(priority, tokenSource.token)
            .addOnSuccessListener { location ->
                if (location != null) {
                    applyFreshLocation(location)
                    return@addOnSuccessListener
                }
                Log.w(TAG, "Fused location returned null, trying LocationManager fallback.")
                requestCurrentLocationFromLocationManager(locationManager)
            }
            .addOnFailureListener { throwable ->
                Log.w(TAG, "Fused location failed, trying LocationManager fallback.", throwable)
                requestCurrentLocationFromLocationManager(locationManager)
            }
            .addOnCompleteListener {
                if (currentLocationTokenSource === tokenSource) {
                    currentLocationTokenSource = null
                }
                isCurrentLocationRequestInFlight = false
            }
    }

    private fun requestCurrentLocationFromLocationManager(locationManager: LocationManager?) {
        val manager = locationManager ?: return
        val provider = when {
            isProviderEnabled(manager, LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            isProviderEnabled(manager, LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        } ?: return

        manager.getCurrentLocation(
            provider,
            null,
            ContextCompat.getMainExecutor(requireContext()),
            Consumer { location: Location? ->
                if (location == null) {
                    Log.w(TAG, "LocationManager fallback also returned null location.")
                    return@Consumer
                }
                applyFreshLocation(location)
            })
    }

    private fun applyFreshLocation(location: Location) {
        if (!isAdded || view == null) return
        if (isManualLocationOverride) return

        val freshCenter = LatLng(location.latitude, location.longitude)

        saveCachedLocation(
            location.latitude,
            location.longitude,
            normalizeLocationTimestamp(location.time, System.currentTimeMillis()),
            addressLabel = null
        )
        mapLibreMap?.animateCamera(
            CameraUpdateFactory.newLatLngZoom(
                freshCenter,
                USER_ZOOM
            )
        )
        loadNearbyVehiclesForCurrentHomeEntry(freshCenter)
        updateLocationLabelForCenter(
            center = freshCenter,
            preferredLabel = null,
            forceSearchTextUpdate = false
        )
    }

    private fun hasFineLocationPermission(): Boolean {
        val context = context ?: return false
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun normalizeLocationTimestamp(locationTimestampMs: Long, fallbackTimestampMs: Long): Long {
        return if (locationTimestampMs > 0L && locationTimestampMs <= fallbackTimestampMs) {
            locationTimestampMs
        } else {
            fallbackTimestampMs
        }
    }

    private fun shouldRequestFreshLocation(nowMs: Long): Boolean {
        if (!hasRequestedFreshLocationThisSession) return true
        val lastRefreshRequestMs = readLastRefreshRequestTimestamp()
        return (nowMs - lastRefreshRequestMs) >= LOCATION_REFRESH_INTERVAL_MS
    }

    private fun saveCachedLocation(
        latitude: Double,
        longitude: Double,
        timestampMs: Long,
        addressLabel: String?
    ) {
        val prefs = context?.getSharedPreferences(LOCATION_CACHE_PREFS, 0) ?: return
        val normalizedLabel = addressLabel?.trim().orEmpty()
        prefs.edit()
            .putLong(KEY_CACHE_TIMESTAMP_MS, timestampMs)
            .putString(KEY_CACHE_LATITUDE, latitude.toString())
            .putString(KEY_CACHE_LONGITUDE, longitude.toString())
            .putString(KEY_CACHE_ADDRESS_LABEL, normalizedLabel)
            .apply()
    }

    private fun saveLastRefreshRequestTimestamp(timestampMs: Long) {
        val prefs = context?.getSharedPreferences(LOCATION_CACHE_PREFS, 0) ?: return
        prefs.edit()
            .putLong(KEY_LAST_REFRESH_REQUEST_MS, timestampMs)
            .apply()
    }

    private fun saveManualLocationOverride(enabled: Boolean) {
        isManualLocationOverride = enabled
        val prefs = context?.getSharedPreferences(LOCATION_CACHE_PREFS, 0) ?: return
        prefs.edit()
            .putBoolean(KEY_MANUAL_LOCATION_SELECTED, enabled)
            .apply()
    }

    private fun readCachedLocation(): CachedLocation? {
        val prefs = context?.getSharedPreferences(LOCATION_CACHE_PREFS, 0) ?: return null
        if (!prefs.contains(KEY_CACHE_TIMESTAMP_MS)) return null

        val latitude = prefs.getString(KEY_CACHE_LATITUDE, null)?.toDoubleOrNull() ?: return null
        val longitude = prefs.getString(KEY_CACHE_LONGITUDE, null)?.toDoubleOrNull() ?: return null
        val timestamp = prefs.getLong(KEY_CACHE_TIMESTAMP_MS, 0L)
        if (timestamp <= 0L) return null
        val addressLabel = prefs.getString(KEY_CACHE_ADDRESS_LABEL, null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        return CachedLocation(
            latitude = latitude,
            longitude = longitude,
            timestampMs = timestamp,
            addressLabel = addressLabel
        )
    }

    private fun readLastRefreshRequestTimestamp(): Long {
        val prefs = context?.getSharedPreferences(LOCATION_CACHE_PREFS, 0) ?: return 0L
        return prefs.getLong(KEY_LAST_REFRESH_REQUEST_MS, 0L)
    }

    private fun readManualLocationOverride(): Boolean {
        val prefs = context?.getSharedPreferences(LOCATION_CACHE_PREFS, 0) ?: return false
        return prefs.getBoolean(KEY_MANUAL_LOCATION_SELECTED, false)
    }

    private fun isLocationServiceEnabled(locationManager: LocationManager?): Boolean {
        val manager = locationManager ?: return false
        return runCatching { LocationManagerCompat.isLocationEnabled(manager) }.getOrDefault(false)
    }

    private fun isProviderEnabled(locationManager: LocationManager, provider: String): Boolean {
        return runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false)
    }

    private fun showLocationDisabledMessageOnce() {
        if (hasShownLocationDisabledMessage || !isAdded) return
        hasShownLocationDisabledMessage = true
        Toast.makeText(
            requireContext(),
            "Ative a localizacao do dispositivo para ver sua posicao atual no mapa.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun distanceMeters(
        fromLatitude: Double,
        fromLongitude: Double,
        toLatitude: Double,
        toLongitude: Double
    ): Float {
        val result = FloatArray(1)
        Location.distanceBetween(fromLatitude, fromLongitude, toLatitude, toLongitude, result)
        return result[0]
    }

    private fun loadNearbyVehiclesForCurrentHomeEntry(center: LatLng) {
        val map = mapLibreMap

        data class SessionCacheSnapshot(
            val vehicles: List<NearbyVehiclesRepository.NearbyVehicle>,
            val canUseCache: Boolean,
            val isLoading: Boolean,
            val effectiveRadiusMeters: Double,
            val usedFallbackRadius: Boolean
        )

        val snapshot = synchronized(NEARBY_SESSION_LOCK) {
            val now = System.currentTimeMillis()
            val cacheAgeMs = now - nearbyCacheLastLoadedAtMs
            val effectiveCacheTtlMs = if (cachedNearbyVehiclesInSession.isEmpty()) {
                NEARBY_EMPTY_CACHE_TTL_MS
            } else {
                NEARBY_SESSION_CACHE_TTL_MS
            }
            val cacheFresh = hasLoadedNearbyVehiclesInSession &&
                cacheAgeMs in 0..effectiveCacheTtlMs

            val cachedCenter = nearbyCacheCenterInSession
            val centerDistance = if (cachedCenter != null) {
                distanceMeters(
                    fromLatitude = center.latitude,
                    fromLongitude = center.longitude,
                    toLatitude = cachedCenter.latitude,
                    toLongitude = cachedCenter.longitude
                )
            } else {
                Float.MAX_VALUE
            }
            val centerStillValid = cachedCenter != null &&
                centerDistance <= NEARBY_SESSION_CACHE_CENTER_DELTA_METERS

            SessionCacheSnapshot(
                vehicles = cachedNearbyVehiclesInSession,
                canUseCache = cacheFresh && centerStillValid,
                isLoading = isLoadingNearbyVehiclesInSession,
                effectiveRadiusMeters = cachedNearbySearchRadiusMetersInSession,
                usedFallbackRadius = cachedNearbyUsedFallbackRadiusInSession
            )
        }

        if (snapshot.canUseCache) {
            map?.let { renderNearbyVehicleBalloons(it, snapshot.vehicles, center) }
            renderNearbyVehicleList(
                vehicles = snapshot.vehicles,
                effectiveRadiusMeters = snapshot.effectiveRadiusMeters,
                usedFallbackRadius = snapshot.usedFallbackRadius
            )
            return
        }

        if (snapshot.isLoading) {
            if (snapshot.vehicles.isNotEmpty()) {
                map?.let { renderNearbyVehicleBalloons(it, snapshot.vehicles, center) }
                renderNearbyVehicleList(
                    vehicles = snapshot.vehicles,
                    effectiveRadiusMeters = snapshot.effectiveRadiusMeters,
                    usedFallbackRadius = snapshot.usedFallbackRadius
                )
            }
            return
        }

        synchronized(NEARBY_SESSION_LOCK) {
            if (isLoadingNearbyVehiclesInSession) return
            isLoadingNearbyVehiclesInSession = true
        }

        nearbyVehiclesFetchJob?.cancel()
        nearbyVehiclesFetchJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val repository = nearbyVehiclesRepository ?: run {
                    synchronized(NEARBY_SESSION_LOCK) {
                        isLoadingNearbyVehiclesInSession = false
                    }
                    return@launch
                }

                val primaryRadiusMeters = selectedSearchRadiusMeters
                val primaryNearbyVehicles = repository.getNearbyPublishedVehicles(
                    centerLatitude = center.latitude,
                    centerLongitude = center.longitude,
                    radiusMeters = primaryRadiusMeters
                )
                val (nearbyVehicles, effectiveRadiusMeters, usedFallbackRadius) =
                    if (primaryNearbyVehicles.isNotEmpty()) {
                        Triple(primaryNearbyVehicles, primaryRadiusMeters, false)
                    } else {
                        val fallbackNearbyVehicles = repository.getNearbyPublishedVehicles(
                            centerLatitude = center.latitude,
                            centerLongitude = center.longitude,
                            radiusMeters = NEARBY_FALLBACK_SEARCH_RADIUS_METERS
                        )
                        Log.d(
                            TAG,
                            "Nearby search fallback used: primaryRadius=$primaryRadiusMeters, fallbackRadius=$NEARBY_FALLBACK_SEARCH_RADIUS_METERS, count=${fallbackNearbyVehicles.size}"
                        )
                        Triple(fallbackNearbyVehicles, NEARBY_FALLBACK_SEARCH_RADIUS_METERS, true)
                    }
                Log.d(
                    TAG,
                    "Nearby vehicles loaded: count=${nearbyVehicles.size}, center=${center.latitude},${center.longitude}, radius=$effectiveRadiusMeters"
                )

                synchronized(NEARBY_SESSION_LOCK) {
                    cachedNearbyVehiclesInSession = nearbyVehicles
                    hasLoadedNearbyVehiclesInSession = true
                    nearbyCacheCenterInSession = center
                    nearbyCacheLastLoadedAtMs = System.currentTimeMillis()
                    cachedNearbySearchRadiusMetersInSession = effectiveRadiusMeters
                    cachedNearbyUsedFallbackRadiusInSession = usedFallbackRadius
                    isLoadingNearbyVehiclesInSession = false
                }

                if (!isAdded || view == null) return@launch
                mapLibreMap?.let { activeMap ->
                    renderNearbyVehicleBalloons(
                        map = activeMap,
                        vehicles = nearbyVehicles,
                        searchCenter = center
                    )
                }
                renderNearbyVehicleList(
                    vehicles = nearbyVehicles,
                    effectiveRadiusMeters = effectiveRadiusMeters,
                    usedFallbackRadius = usedFallbackRadius
                )
            } catch (cancelled: CancellationException) {
                synchronized(NEARBY_SESSION_LOCK) {
                    isLoadingNearbyVehiclesInSession = false
                }
                throw cancelled
            } catch (throwable: Throwable) {
                synchronized(NEARBY_SESSION_LOCK) {
                    isLoadingNearbyVehiclesInSession = false
                }
                Log.w(TAG, "Failed to load nearby vehicles for map.", throwable)
            }
        }
    }

    private fun renderNearbyVehicleList(
        vehicles: List<NearbyVehiclesRepository.NearbyVehicle>,
        effectiveRadiusMeters: Double,
        usedFallbackRadius: Boolean
    ) {
        if (!::nearbyVehiclesAdapter.isInitialized) return
        if (!::rvNearbyVehicles.isInitialized) return
        if (!::tvNearbyVehiclesEmpty.isInitialized) return
        if (!::tvNearbySearchRadius.isInitialized) return

        tvNearbySearchRadius.text = buildSearchRadiusLabel(
            radiusMeters = effectiveRadiusMeters,
            usedFallbackRadius = usedFallbackRadius
        )

        val cards = vehicles
            .sortedBy { it.distanceMeters }
            .map { nearbyVehicle -> nearbyVehicle.toProviderCardUi() }
        nearbyVehiclesAdapter.submitList(cards)

        val hasCards = cards.isNotEmpty()
        rvNearbyVehicles.isVisible = hasCards
        tvNearbyVehiclesEmpty.isVisible = !hasCards
    }

    private fun NearbyVehiclesRepository.NearbyVehicle.toProviderCardUi(): ProviderVehicleCardUi {
        val payload = runCatching { JSONObject(payloadJson) }.getOrNull()

        val brand = this.brand.orEmpty().trim().ifBlank { payload?.optString("brand").orEmpty().trim() }
        val model = this.model.orEmpty().trim().ifBlank { payload?.optString("model").orEmpty().trim() }
        val color = this.color.orEmpty().trim().ifBlank { payload?.optString("color").orEmpty().trim() }
        val resolvedVehicleType = normalizeVehicleTypeForCard(vehicleType)
            ?: normalizeVehicleTypeForCard(payload?.optString("vehicleType"))
            ?: VEHICLE_TYPE_CAR
        val bodyTypeValue = bodyType
            ?: payload?.optString("bodyType").orEmpty().trim().ifBlank { null }
        val dailyPrice = this.dailyPrice.orEmpty().trim()
            .ifBlank { payload?.optString("dailyPrice").orEmpty().trim() }

        val title = listOf(brand, model)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { getString(R.string.renter_home_nearby_unknown_vehicle) }

        val colorLabel = getString(
            R.string.renter_home_nearby_color_line,
            color.ifBlank { "-" }
        )

        return ProviderVehicleCardUi(
            vehicleId = vehicleId,
            payloadJson = payloadJson,
            title = title,
            plate = "",
            plateLabel = colorLabel,
            updatedAtTimestamp = distanceMeters.roundToLong(),
            updatedAtLabel = formatDistanceLabel(distanceMeters),
            statusLabel = getString(R.string.renter_home_nearby_status_available),
            status = SQLiteConfiguration.STATUS_PUBLISHED,
            hasPendingSync = false,
            pendingSyncLabel = "",
            vehicleType = resolvedVehicleType,
            bodyType = bodyTypeValue,
            sideTagLabel = if (dailyPrice.isNotBlank()) {
                getString(R.string.renter_home_nearby_daily_price, dailyPrice)
            } else {
                null
            }
        )
    }

    private fun normalizeVehicleTypeForCard(raw: String?): String? {
        return when (raw?.trim()?.lowercase(Locale.ROOT)) {
            VEHICLE_TYPE_MOTORCYCLE, "moto" -> VEHICLE_TYPE_MOTORCYCLE
            VEHICLE_TYPE_CAR -> VEHICLE_TYPE_CAR
            else -> null
        }
    }

    private fun formatDistanceLabel(distanceMeters: Double): String {
        val safeDistance = distanceMeters.coerceAtLeast(0.0)
        return if (safeDistance < 1000.0) {
            getString(R.string.renter_home_nearby_distance_meters, safeDistance)
        } else {
            getString(R.string.renter_home_nearby_distance_km, safeDistance / 1000.0)
        }
    }

    private fun buildSearchRadiusLabel(radiusMeters: Double, usedFallbackRadius: Boolean): String {
        val radiusLabel = formatSearchRadiusKm(radiusMeters)
        return if (usedFallbackRadius) {
            getString(R.string.renter_home_nearby_search_radius_expanded, radiusLabel)
        } else {
            getString(R.string.renter_home_nearby_search_radius, radiusLabel)
        }
    }

    private fun formatSearchRadiusKm(radiusMeters: Double): String {
        val radiusKm = radiusMeters / 1000.0
        val hasDecimals = radiusKm % 1.0 != 0.0
        return if (hasDecimals) {
            String.format(Locale.getDefault(), "%.1f km", radiusKm)
        } else {
            String.format(Locale.getDefault(), "%.0f km", radiusKm)
        }
    }

    private fun renderNearbyVehicleBalloons(
        map: MapLibreMap,
        vehicles: List<NearbyVehiclesRepository.NearbyVehicle>,
        searchCenter: LatLng
    ) {
        val style = map.style
        if (style == null) {
            Log.w(TAG, "Map style not ready, skipping nearby vehicle render.")
            return
        }

        ensureNearbyVehicleStyleLayer(style)

        val source = style.getSourceAs<GeoJsonSource>(NEARBY_VEHICLES_SOURCE_ID)
        if (source == null) {
            Log.w(TAG, "Nearby vehicle source missing, skipping nearby vehicle render.")
            return
        }

        if (vehicles.isEmpty()) {
            source.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            Log.d(TAG, "No nearby vehicles to render on map.")
            return
        }

        val features = vehicles.map { vehicle ->
            Feature.fromGeometry(
                Point.fromLngLat(
                    vehicle.pickupLongitude,
                    vehicle.pickupLatitude
                )
            ).apply {
                addStringProperty(
                    NEARBY_VEHICLE_ICON_PROPERTY,
                    markerImageIdForVehicleType(vehicle.vehicleType)
                )
            }
        }

        source.setGeoJson(FeatureCollection.fromFeatures(features))
        map.triggerRepaint()
        frameCollapsedPreviewToNearbyVehicles(searchCenter, vehicles)
        Log.d(TAG, "Rendered ${vehicles.size} nearby vehicle markers.")
    }

    private fun frameCollapsedPreviewToNearbyVehicles(
        searchCenter: LatLng,
        vehicles: List<NearbyVehiclesRepository.NearbyVehicle>
    ) {
        if (isMapExpanded || vehicles.isEmpty()) return

        val map = mapLibreMap ?: return
        val mapHost = mapView ?: return
        val previewPoints = vehicles
            .asSequence()
            .take(MAX_PREVIEW_MARKERS_FOR_CAMERA_FRAME)
            .map { LatLng(it.pickupLatitude, it.pickupLongitude) }
            .toList()
        if (previewPoints.isEmpty()) return

        val paddingPx = (PREVIEW_CAMERA_PADDING_DP * resources.displayMetrics.density)
            .roundToInt()
            .coerceAtLeast(1)

        mapHost.post {
            if (!isAdded || isMapExpanded || mapView == null) return@post
            val bounds = LatLngBounds.Builder()
                .include(searchCenter)
                .includes(previewPoints)
                .build()

            runCatching {
                map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, paddingPx))
            }.onFailure { throwable ->
                Log.w(TAG, "Failed to frame collapsed map preview to nearby vehicles.", throwable)
            }
        }
    }

    private fun clearNearbyVehicleMarkers() {
        val source = mapLibreMap
            ?.style
            ?.getSourceAs<GeoJsonSource>(NEARBY_VEHICLES_SOURCE_ID)
            ?: return
        source.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
    }

    private fun markerImageIdForVehicleType(vehicleType: String?): String {
        return if (vehicleType.equals(VEHICLE_TYPE_MOTORCYCLE, ignoreCase = true)) {
            NEARBY_ICON_IMAGE_MOTORCYCLE_ID
        } else {
            NEARBY_ICON_IMAGE_CAR_ID
        }
    }

    private fun ensureNearbyVehicleStyleLayer(style: Style) {
        addNearbyVehicleImageIfNeeded(
            style = style,
            imageId = NEARBY_ICON_IMAGE_CAR_ID,
            iconName = MAP_BALLOON_ICON_CAR
        )
        addNearbyVehicleImageIfNeeded(
            style = style,
            imageId = NEARBY_ICON_IMAGE_MOTORCYCLE_ID,
            iconName = MAP_BALLOON_ICON_MOTORCYCLE
        )

        var source = style.getSourceAs<GeoJsonSource>(NEARBY_VEHICLES_SOURCE_ID)
        if (source == null) {
            source = GeoJsonSource(NEARBY_VEHICLES_SOURCE_ID, FeatureCollection.fromFeatures(emptyList()))
            style.addSource(source)
        }

        if (style.getLayer(NEARBY_VEHICLES_LAYER_ID) == null) {
            style.addLayer(
                SymbolLayer(NEARBY_VEHICLES_LAYER_ID, NEARBY_VEHICLES_SOURCE_ID)
                    .withProperties(
                        iconImage(get(NEARBY_VEHICLE_ICON_PROPERTY)),
                        iconAnchor("bottom"),
                        iconAllowOverlap(true),
                        iconIgnorePlacement(true)
                    )
            )
        }
    }

    private fun addNearbyVehicleImageIfNeeded(style: Style, imageId: String, iconName: String) {
        if (style.getImage(imageId) != null) return
        val bitmap = markerBitmapByName[iconName] ?: buildVehicleBalloonBitmap(iconName)?.also {
            markerBitmapByName[iconName] = it
        } ?: return
        style.addImage(imageId, bitmap)
    }

    private fun clearMarkerBitmapCache() {
        markerBitmapByName.values.forEach { bitmap ->
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
        markerBitmapByName.clear()
    }

    private fun buildVehicleBalloonBitmap(iconName: String): Bitmap? {
        val context = context ?: return null
        val density = resources.displayMetrics.density

        val bitmapWidth = (BALLOON_BITMAP_WIDTH_DP * density).roundToInt().coerceAtLeast(1)
        val bitmapHeight = (BALLOON_BITMAP_HEIGHT_DP * density).roundToInt().coerceAtLeast(1)
        val bubbleCornerRadius = BALLOON_CORNER_RADIUS_DP * density
        val pointerHeight = BALLOON_POINTER_HEIGHT_DP * density
        val pointerHalfWidth = BALLOON_POINTER_HALF_WIDTH_DP * density
        val bubbleBottom = bitmapHeight.toFloat() - pointerHeight
        val bubbleCenterX = bitmapWidth / 2f

        val fillColor = MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorSecondary,
            ContextCompat.getColor(context, android.R.color.holo_green_light)
        )
        val strokeColor = MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorOutline,
            ContextCompat.getColor(context, android.R.color.darker_gray)
        )
        val iconColor = MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorPrimary,
            ContextCompat.getColor(context, android.R.color.holo_blue_dark)
        )

        val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val balloonPath = Path().apply {
            addRoundRect(
                RectF(0f, 0f, bitmapWidth.toFloat(), bubbleBottom),
                bubbleCornerRadius,
                bubbleCornerRadius,
                Path.Direction.CW
            )
            moveTo(bubbleCenterX - pointerHalfWidth, bubbleBottom - 1f)
            lineTo(bubbleCenterX, bitmapHeight.toFloat())
            lineTo(bubbleCenterX + pointerHalfWidth, bubbleBottom - 1f)
            close()
        }

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = fillColor
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = strokeColor
            strokeWidth = BALLOON_STROKE_DP * density
        }
        canvas.drawPath(balloonPath, fillPaint)
        canvas.drawPath(balloonPath, strokePaint)

        val iconTypeface = ResourcesCompat.getFont(context, R.font.material_symbols_rounded)
        val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = iconTypeface
            color = iconColor
            textSize = BALLOON_ICON_SIZE_SP * resources.displayMetrics.scaledDensity
            fontFeatureSettings = "'liga'"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                fontVariationSettings = "'FILL' 1, 'wght' 600, 'GRAD' 0, 'opsz' 24"
            }
        }

        val iconCenterY = (bubbleBottom / 2f) - ((iconPaint.descent() + iconPaint.ascent()) / 2f)
        canvas.drawText(iconName, bubbleCenterX, iconCenterY, iconPaint)

        return bitmap
    }

    companion object {
        private data class CachedLocation(
            val latitude: Double,
            val longitude: Double,
            val timestampMs: Long,
            val addressLabel: String?
        )

        private val NEARBY_SESSION_LOCK = Any()
        @Volatile
        private var hasLoadedNearbyVehiclesInSession = false
        @Volatile
        private var isLoadingNearbyVehiclesInSession = false
        @Volatile
        private var nearbyCacheLastLoadedAtMs = 0L
        @Volatile
        private var nearbyCacheCenterInSession: LatLng? = null
        @Volatile
        private var cachedNearbyVehiclesInSession = emptyList<NearbyVehiclesRepository.NearbyVehicle>()
        @Volatile
        private var cachedNearbySearchRadiusMetersInSession = NEARBY_SEARCH_RADIUS_METERS
        @Volatile
        private var cachedNearbyUsedFallbackRadiusInSession = false

        private const val MAP_VIEW_STATE_KEY = "renter_home_map_view_state"
        private const val LOCATION_CACHE_PREFS = "renter_map_cache"
        private const val KEY_CACHE_LATITUDE = "cache_latitude"
        private const val KEY_CACHE_LONGITUDE = "cache_longitude"
        private const val KEY_CACHE_TIMESTAMP_MS = "cache_timestamp_ms"
        private const val KEY_CACHE_ADDRESS_LABEL = "cache_address_label"
        private const val KEY_LAST_REFRESH_REQUEST_MS = "last_refresh_request_ms"
        private const val KEY_MANUAL_LOCATION_SELECTED = "manual_location_selected"
        private const val LOCATION_CACHE_MAX_AGE_MS = 5L * 24L * 60L * 60L * 1000L
        private const val LOCATION_CACHE_RADIUS_METERS = 1500f
        private const val LOCATION_REFRESH_INTERVAL_MS = 30L * 60L * 1000L
        private const val MIN_CURRENT_LOCATION_REQUEST_INTERVAL_MS = 60_000L
        private const val MIN_ADDRESS_QUERY_LENGTH = 3
        private const val ADDRESS_SEARCH_DEBOUNCE_MS = 650L
        private const val MAX_ADDRESS_SUGGESTIONS = 8
        private const val NEARBY_SEARCH_RADIUS_METERS = 5000.0
        private const val NEARBY_FALLBACK_SEARCH_RADIUS_METERS = 100_000.0
        private const val NEARBY_SESSION_CACHE_TTL_MS = 10L * 60L * 1000L
        private const val NEARBY_EMPTY_CACHE_TTL_MS = 20_000L
        private const val NEARBY_SESSION_CACHE_CENTER_DELTA_METERS = 1200f
        private const val PREVIEW_CAMERA_PADDING_DP = 22f
        private const val MAX_PREVIEW_MARKERS_FOR_CAMERA_FRAME = 8
        private const val BALLOON_BITMAP_WIDTH_DP = 30f
        private const val BALLOON_BITMAP_HEIGHT_DP = 38f
        private const val BALLOON_CORNER_RADIUS_DP = 7f
        private const val BALLOON_POINTER_HEIGHT_DP = 6f
        private const val BALLOON_POINTER_HALF_WIDTH_DP = 5f
        private const val BALLOON_STROKE_DP = 1f
        private const val BALLOON_ICON_SIZE_SP = 13f
        private const val MAP_BALLOON_ICON_CAR = "directions_car"
        private const val MAP_BALLOON_ICON_MOTORCYCLE = "two_wheeler"
        private const val NEARBY_VEHICLES_SOURCE_ID = "nearby-vehicles-source"
        private const val NEARBY_VEHICLES_LAYER_ID = "nearby-vehicles-layer"
        private const val NEARBY_VEHICLE_ICON_PROPERTY = "marker_icon_id"
        private const val NEARBY_ICON_IMAGE_CAR_ID = "nearby-icon-car"
        private const val NEARBY_ICON_IMAGE_MOTORCYCLE_ID = "nearby-icon-motorcycle"
        private const val VEHICLE_TYPE_CAR = "car"
        private const val VEHICLE_TYPE_MOTORCYCLE = "motorcycle"
        private val DEFAULT_LOCATION = LatLng(-23.55052, -46.63331)
        private const val DEFAULT_ZOOM = 11.0
        private const val USER_ZOOM = 16.0
        private const val TAG = "RenterHomeFragment"
    }
}
