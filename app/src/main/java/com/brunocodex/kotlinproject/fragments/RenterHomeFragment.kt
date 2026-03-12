package com.brunocodex.kotlinproject.fragments

import android.Manifest
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.location.LocationManagerCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.brunocodex.kotlinproject.R
import com.brunocodex.kotlinproject.services.NearbyVehiclesRepository
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.function.Consumer
import kotlin.math.roundToInt

class RenterHomeFragment : Fragment(R.layout.fragment_renter_home), OnMapReadyCallback {

    private var mapView: MapView? = null
    private var googleMap: GoogleMap? = null
    private var mapViewSavedState: Bundle? = null

    private var collapsedMapContainer: FrameLayout? = null
    private var fullscreenOverlay: FrameLayout? = null
    private var fullscreenMapContainer: FrameLayout? = null
    private var closeFullscreenButton: View? = null

    private var isMapExpanded = false
    private var isCurrentLocationRequestInFlight = false
    private var lastCurrentLocationRequestAtMs = 0L
    private var hasRequestedFreshLocationThisSession = false
    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var currentLocationTokenSource: CancellationTokenSource? = null
    private var hasShownLocationDisabledMessage = false
    private var nearbyVehiclesRepository: NearbyVehiclesRepository? = null
    private var nearbyVehiclesFetchJob: Job? = null
    private val nearbyVehicleMarkers = mutableListOf<Marker>()
    private val balloonDescriptorByIconName = mutableMapOf<String, BitmapDescriptor>()

    private val backPressCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            collapseMap(animated = true)
        }
    }

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results.values.any { it }) {
                centerMapOnCurrentLocation()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mapViewSavedState = savedInstanceState?.getBundle(MAP_VIEW_STATE_KEY)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Mapa temporariamente desativado na home do renter.
        // collapsedMapContainer = view.findViewById(R.id.renterHomeMapContainer)
        // mapView = view.findViewById(R.id.renterHomeMapView)

        fullscreenOverlay = activity?.findViewById(R.id.fullscreenMapOverlay)
        fullscreenMapContainer = activity?.findViewById(R.id.fullscreenMapContainer)
        closeFullscreenButton = activity?.findViewById(R.id.btnCloseFullscreenMap)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())
        nearbyVehiclesRepository = NearbyVehiclesRepository(requireContext().applicationContext)

        // Mapa temporariamente desativado na home do renter.
        // mapView?.onCreate(mapViewSavedState)
        // mapView?.getMapAsync(this)

        // Mapa temporariamente desativado na home do renter.
        // view.findViewById<View>(R.id.cardRenterMapPreview).setOnClickListener { expandMap() }
        // view.findViewById<View>(R.id.mapCardTouchOverlay).setOnClickListener { expandMap() }
        closeFullscreenButton?.setOnClickListener { collapseMap(animated = true) }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backPressCallback)
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        applyMapVisualStyle(map)
        applyMapUiState()
        ensureLocationReady(shouldRequestPermission = true)
    }

    override fun onStart() {
        super.onStart()
        mapView?.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView?.onResume()
        if (googleMap != null) {
            ensureLocationReady(shouldRequestPermission = false)
        }
    }

    override fun onPause() {
        nearbyVehiclesFetchJob?.cancel()
        nearbyVehiclesFetchJob = null
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
        mapView?.onDestroy()
        mapView = null
        googleMap = null
        nearbyVehiclesFetchJob?.cancel()
        nearbyVehiclesFetchJob = null
        synchronized(NEARBY_SESSION_LOCK) {
            isLoadingNearbyVehiclesInSession = false
        }
        clearNearbyVehicleMarkers()
        nearbyVehiclesRepository = null
        balloonDescriptorByIconName.clear()
        currentLocationTokenSource?.cancel()
        currentLocationTokenSource = null
        fusedLocationClient = null
        hasShownLocationDisabledMessage = false
        isCurrentLocationRequestInFlight = false
        lastCurrentLocationRequestAtMs = 0L
        hasRequestedFreshLocationThisSession = false
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
        googleMap?.uiSettings?.apply {
            isCompassEnabled = isMapExpanded
            isMapToolbarEnabled = isMapExpanded
            isRotateGesturesEnabled = isMapExpanded
            isScrollGesturesEnabled = isMapExpanded
            isTiltGesturesEnabled = isMapExpanded
            isZoomControlsEnabled = isMapExpanded
            isZoomGesturesEnabled = isMapExpanded
        }
    }

    private fun applyMapVisualStyle(map: GoogleMap) {
        val styleRes = if (isNightModeActive()) {
            R.raw.map_style_renter_dark
        } else {
            R.raw.map_style_renter_light
        }

        val applied = runCatching {
            map.setMapStyle(MapStyleOptions.loadRawResourceStyle(requireContext(), styleRes))
        }.getOrElse { throwable ->
            Log.w(TAG, "Failed to apply map style.", throwable)
            false
        }

        if (!applied) {
            Log.w(TAG, "Map style resource was not applied.")
        }
    }

    private fun isNightModeActive(): Boolean {
        val uiMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return uiMode == Configuration.UI_MODE_NIGHT_YES
    }

    private fun ensureLocationReady(shouldRequestPermission: Boolean) {
        if (hasLocationPermission()) {
            centerMapOnCurrentLocation()
            return
        }

        googleMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(DEFAULT_LOCATION, DEFAULT_ZOOM))
        if (shouldRequestPermission) {
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

    private fun centerMapOnCurrentLocation() {
        val map = googleMap ?: return
        var candidateSearchCenter: LatLng? = null

        if (!hasLocationPermission()) {
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(DEFAULT_LOCATION, DEFAULT_ZOOM))
            return
        }

        runCatching { map.isMyLocationEnabled = true }

        val locationManager = context?.getSystemService(LocationManager::class.java)
        val isLocationEnabled = isLocationServiceEnabled(locationManager)

        val now = System.currentTimeMillis()
        val cached = readCachedLocation()
        val isCachedFresh = cached != null && (now - cached.timestampMs) <= LOCATION_CACHE_MAX_AGE_MS

        if (isCachedFresh) {
            candidateSearchCenter = LatLng(cached.latitude, cached.longitude)
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
            val shouldUseLastKnown = !isCachedFresh ||
                (cacheDistanceFromLastKnown != null && cacheDistanceFromLastKnown > LOCATION_CACHE_RADIUS_METERS)

            if (shouldUseLastKnown) {
                candidateSearchCenter = LatLng(lastKnown.latitude, lastKnown.longitude)
                map.moveCamera(
                    CameraUpdateFactory.newLatLngZoom(
                        candidateSearchCenter,
                        USER_ZOOM
                    )
                )
            } else if (candidateSearchCenter == null) {
                candidateSearchCenter = LatLng(lastKnown.latitude, lastKnown.longitude)
            }
            saveCachedLocation(
                lastKnown.latitude,
                lastKnown.longitude,
                normalizeLocationTimestamp(lastKnown.time, now)
            )
        } else if (!isCachedFresh) {
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(DEFAULT_LOCATION, DEFAULT_ZOOM))
        }

        candidateSearchCenter?.let(::loadNearbyVehiclesForCurrentHomeEntry)

        if (!isLocationEnabled) {
            showLocationDisabledMessageOnce()
            return
        }

        val shouldRefreshCurrentLocation = shouldRequestFreshLocation(now)
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

        saveCachedLocation(
            location.latitude,
            location.longitude,
            normalizeLocationTimestamp(location.time, System.currentTimeMillis())
        )
        googleMap?.animateCamera(
            CameraUpdateFactory.newLatLngZoom(
                LatLng(location.latitude, location.longitude),
                USER_ZOOM
            )
        )
        loadNearbyVehiclesForCurrentHomeEntry(LatLng(location.latitude, location.longitude))
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

    private fun saveCachedLocation(latitude: Double, longitude: Double, timestampMs: Long) {
        val prefs = context?.getSharedPreferences(LOCATION_CACHE_PREFS, 0) ?: return
        prefs.edit()
            .putLong(KEY_CACHE_TIMESTAMP_MS, timestampMs)
            .putString(KEY_CACHE_LATITUDE, latitude.toString())
            .putString(KEY_CACHE_LONGITUDE, longitude.toString())
            .apply()
    }

    private fun saveLastRefreshRequestTimestamp(timestampMs: Long) {
        val prefs = context?.getSharedPreferences(LOCATION_CACHE_PREFS, 0) ?: return
        prefs.edit()
            .putLong(KEY_LAST_REFRESH_REQUEST_MS, timestampMs)
            .apply()
    }

    private fun readCachedLocation(): CachedLocation? {
        val prefs = context?.getSharedPreferences(LOCATION_CACHE_PREFS, 0) ?: return null
        if (!prefs.contains(KEY_CACHE_TIMESTAMP_MS)) return null

        val latitude = prefs.getString(KEY_CACHE_LATITUDE, null)?.toDoubleOrNull() ?: return null
        val longitude = prefs.getString(KEY_CACHE_LONGITUDE, null)?.toDoubleOrNull() ?: return null
        val timestamp = prefs.getLong(KEY_CACHE_TIMESTAMP_MS, 0L)
        if (timestamp <= 0L) return null

        return CachedLocation(latitude = latitude, longitude = longitude, timestampMs = timestamp)
    }

    private fun readLastRefreshRequestTimestamp(): Long {
        val prefs = context?.getSharedPreferences(LOCATION_CACHE_PREFS, 0) ?: return 0L
        return prefs.getLong(KEY_LAST_REFRESH_REQUEST_MS, 0L)
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
        val map = googleMap ?: return

        data class SessionCacheSnapshot(
            val vehicles: List<NearbyVehiclesRepository.NearbyVehicle>,
            val canUseCache: Boolean,
            val isLoading: Boolean
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
                isLoading = isLoadingNearbyVehiclesInSession
            )
        }

        if (snapshot.canUseCache) {
            renderNearbyVehicleBalloons(map, snapshot.vehicles)
            return
        }

        if (snapshot.isLoading) {
            if (snapshot.vehicles.isNotEmpty()) {
                renderNearbyVehicleBalloons(map, snapshot.vehicles)
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

                val nearbyVehicles = repository.getNearbyPublishedVehicles(
                    center,
                    NEARBY_SEARCH_RADIUS_METERS
                )

                synchronized(NEARBY_SESSION_LOCK) {
                    cachedNearbyVehiclesInSession = nearbyVehicles
                    hasLoadedNearbyVehiclesInSession = true
                    nearbyCacheCenterInSession = center
                    nearbyCacheLastLoadedAtMs = System.currentTimeMillis()
                    isLoadingNearbyVehiclesInSession = false
                }

                if (!isAdded || view == null) return@launch
                renderNearbyVehicleBalloons(googleMap ?: return@launch, nearbyVehicles)
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

    private fun renderNearbyVehicleBalloons(
        map: GoogleMap,
        vehicles: List<NearbyVehiclesRepository.NearbyVehicle>
    ) {
        clearNearbyVehicleMarkers()
        if (vehicles.isEmpty()) return

        vehicles.forEach { vehicle ->
            val iconName = markerIconNameForVehicleType(vehicle.vehicleType)
            val descriptor = balloonDescriptorByIconName[iconName]
                ?: buildVehicleBalloonDescriptor(iconName)?.also {
                    balloonDescriptorByIconName[iconName] = it
                }
                ?: return@forEach
            val position = LatLng(vehicle.pickupLatitude, vehicle.pickupLongitude)
            val marker = map.addMarker(
                MarkerOptions()
                    .position(position)
                    .icon(descriptor)
                    .anchor(0.5f, 1f)
                    .zIndex(VEHICLE_BALLOON_Z_INDEX)
                    .flat(false)
            )
            marker?.let(nearbyVehicleMarkers::add)
        }
    }

    private fun clearNearbyVehicleMarkers() {
        nearbyVehicleMarkers.forEach { it.remove() }
        nearbyVehicleMarkers.clear()
    }

    private fun markerIconNameForVehicleType(vehicleType: String?): String {
        return if (vehicleType.equals(VEHICLE_TYPE_MOTORCYCLE, ignoreCase = true)) {
            MAP_BALLOON_ICON_MOTORCYCLE
        } else {
            MAP_BALLOON_ICON_CAR
        }
    }

    private fun buildVehicleBalloonDescriptor(iconName: String): BitmapDescriptor? {
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

        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }

    companion object {
        private data class CachedLocation(
            val latitude: Double,
            val longitude: Double,
            val timestampMs: Long
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

        private const val MAP_VIEW_STATE_KEY = "renter_home_map_view_state"
        private const val LOCATION_CACHE_PREFS = "renter_map_cache"
        private const val KEY_CACHE_LATITUDE = "cache_latitude"
        private const val KEY_CACHE_LONGITUDE = "cache_longitude"
        private const val KEY_CACHE_TIMESTAMP_MS = "cache_timestamp_ms"
        private const val KEY_LAST_REFRESH_REQUEST_MS = "last_refresh_request_ms"
        private const val LOCATION_CACHE_MAX_AGE_MS = 5L * 24L * 60L * 60L * 1000L
        private const val LOCATION_CACHE_RADIUS_METERS = 1500f
        private const val LOCATION_REFRESH_INTERVAL_MS = 30L * 60L * 1000L
        private const val MIN_CURRENT_LOCATION_REQUEST_INTERVAL_MS = 60_000L
        private const val NEARBY_SEARCH_RADIUS_METERS = 5000.0
        private const val NEARBY_SESSION_CACHE_TTL_MS = 10L * 60L * 1000L
        private const val NEARBY_EMPTY_CACHE_TTL_MS = 20_000L
        private const val NEARBY_SESSION_CACHE_CENTER_DELTA_METERS = 1200f
        private const val VEHICLE_BALLOON_Z_INDEX = 30f
        private const val BALLOON_BITMAP_WIDTH_DP = 28f
        private const val BALLOON_BITMAP_HEIGHT_DP = 36f
        private const val BALLOON_CORNER_RADIUS_DP = 7f
        private const val BALLOON_POINTER_HEIGHT_DP = 6f
        private const val BALLOON_POINTER_HALF_WIDTH_DP = 5f
        private const val BALLOON_STROKE_DP = 1f
        private const val BALLOON_ICON_SIZE_SP = 12f
        private const val MAP_BALLOON_ICON_CAR = "directions_car"
        private const val MAP_BALLOON_ICON_MOTORCYCLE = "two_wheeler"
        private const val VEHICLE_TYPE_MOTORCYCLE = "motorcycle"
        private val DEFAULT_LOCATION = LatLng(-23.55052, -46.63331)
        private const val DEFAULT_ZOOM = 11f
        private const val USER_ZOOM = 16f
        private const val TAG = "RenterHomeFragment"
    }
}
