package com.brunocodex.kotlinproject.activities

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.content.res.Configuration
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.helper.widget.Flow
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.brunocodex.kotlinproject.R
import com.brunocodex.kotlinproject.components.VehiclePhotoCarouselController
import com.brunocodex.kotlinproject.utils.RenterDateSelectionMemoryStore
import com.brunocodex.kotlinproject.viewmodels.VehicleRegisterViewModel
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.OnMapReadyCallback
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconAnchor
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Locale
import java.util.Random
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

class RenterVehicleDetailsActivity : AppCompatActivity(), OnMapReadyCallback {

    companion object {
        const val EXTRA_VEHICLE_ID = "extra_vehicle_id"
        const val EXTRA_PAYLOAD_JSON = "extra_payload_json"

        private const val MAX_RANGE_DAYS_FOR_BLOCKS = 420
        private const val DYNAMIC_HIGHLIGHT_TAG_MARKER = "dynamic_highlight_tag"
        private const val DYNAMIC_CONDITION_TAG_MARKER = "dynamic_condition_tag"
        private const val DYNAMIC_SAFETY_TAG_MARKER = "dynamic_safety_tag"
        private const val DYNAMIC_COMFORT_TAG_MARKER = "dynamic_comfort_tag"
        private const val DYNAMIC_DELIVERY_TAG_MARKER = "dynamic_delivery_tag"
        private const val DYNAMIC_TRIP_TAG_MARKER = "dynamic_trip_tag"
        private const val DYNAMIC_DOCUMENTS_TAG_MARKER = "dynamic_documents_tag"
        private const val APPROXIMATE_MAP_STATE_KEY = "renter_vehicle_approx_map_state"
        private const val APPROXIMATE_AREA_SOURCE_ID = "approximate-area-source"
        private const val APPROXIMATE_CENTER_SOURCE_ID = "approximate-center-source"
        private const val APPROXIMATE_AREA_FILL_LAYER_ID = "approximate-area-fill-layer"
        private const val APPROXIMATE_AREA_STROKE_LAYER_ID = "approximate-area-stroke-layer"
        private const val APPROXIMATE_CENTER_LAYER_ID = "approximate-center-layer"
        private const val APPROXIMATE_CENTER_IMAGE_ID = "approximate-center-image"
        private const val APPROXIMATE_AREA_LIGHT_BLEND_FACTOR = 0.62f
        private const val APPROXIMATE_AREA_FILL_OPACITY = 0.38f
        private const val APPROXIMATE_AREA_STROKE_WIDTH = 2f
        private const val OBFUSCATION_RADIUS_METERS = 500.0
        private const val MIN_OBFUSCATION_OFFSET_METERS = 220.0
        private const val MAX_OBFUSCATION_OFFSET_METERS = 500.0
        private const val EARTH_RADIUS_METERS = 6371000.0
        private const val APPROXIMATE_MAP_CAMERA_PADDING_DP = 34
        private const val BALLOON_BITMAP_WIDTH_DP = 30f
        private const val BALLOON_BITMAP_HEIGHT_DP = 38f
        private const val BALLOON_CORNER_RADIUS_DP = 7f
        private const val BALLOON_POINTER_HEIGHT_DP = 6f
        private const val BALLOON_POINTER_HALF_WIDTH_DP = 5f
        private const val BALLOON_STROKE_DP = 1f
        private const val BALLOON_ICON_SIZE_SP = 13f
        private const val MAP_BALLOON_ICON_CAR = "directions_car"
        private const val MAP_BALLOON_ICON_MOTORCYCLE = "two_wheeler"
        private const val VEHICLE_TYPE_MOTORCYCLE = "motorcycle"
    }

    private lateinit var tvVehicleTitle: TextView
    private lateinit var tvVehicleSubtitle: TextView
    private lateinit var tvDailyPriceValue: TextView
    private lateinit var tvAvailabilitySummary: TextView
    private lateinit var tvTagBodyType: TextView
    private lateinit var tvTagTransmission: TextView
    private lateinit var tvTagFuel: TextView
    private lateinit var tvTagSeats: TextView
    private lateinit var tvTagColor: TextView
    private lateinit var tagsContainer: ConstraintLayout
    private lateinit var detailsTagsFlow: Flow
    private lateinit var tvConditionTagsTitle: TextView
    private lateinit var conditionTagsContainer: ConstraintLayout
    private lateinit var conditionTagsFlow: Flow
    private lateinit var tvSafetyTagsTitle: TextView
    private lateinit var safetyTagsContainer: ConstraintLayout
    private lateinit var safetyTagsFlow: Flow
    private lateinit var tvComfortTagsTitle: TextView
    private lateinit var comfortTagsContainer: ConstraintLayout
    private lateinit var comfortTagsFlow: Flow
    private lateinit var tvDeliveryTagsTitle: TextView
    private lateinit var deliveryTagsContainer: ConstraintLayout
    private lateinit var deliveryTagsFlow: Flow
    private lateinit var tvTripTagsTitle: TextView
    private lateinit var tripTagsContainer: ConstraintLayout
    private lateinit var tripTagsFlow: Flow
    private lateinit var tvDocumentsTagsTitle: TextView
    private lateinit var documentsTagsContainer: ConstraintLayout
    private lateinit var documentsTagsFlow: Flow
    private lateinit var additionalInfoFrame: FrameLayout
    private lateinit var additionalInfoContainer: LinearLayout
    private lateinit var btnToggleMoreInfo: Button
    private lateinit var publicInfoContainer: LinearLayout
    private lateinit var tvBottomRentSubtitle: TextView
    private lateinit var btnSeeAvailability: Button
    private lateinit var photoCarouselController: VehiclePhotoCarouselController
    private var approximateMapView: MapView? = null
    private var approximateMap: MapLibreMap? = null
    private var approximateMapSavedState: Bundle? = null
    private var isApproximateMapInitialized = false
    private lateinit var cardApproximateLocationMap: MaterialCardView
    private lateinit var tvApproximateLocationUnavailable: TextView

    private var payloadJsonRaw: String = ""
    private var payload: JSONObject? = null
    private var dailyPriceValue: Double? = null
    private var isMoreInfoExpanded: Boolean = false
    private var selectedStartDate: LocalDate? = null
    private var selectedEndDate: LocalDate? = null
    private var obfuscatedCenter: LatLng? = null
    private var approximateMarkerIconName: String = MAP_BALLOON_ICON_CAR

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_renter_vehicle_details)
        MapLibre.getInstance(applicationContext)
        approximateMapSavedState = savedInstanceState?.getBundle(APPROXIMATE_MAP_STATE_KEY)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        tvVehicleTitle = findViewById(R.id.tvVehicleTitle)
        tvVehicleSubtitle = findViewById(R.id.tvVehicleSubtitle)
        tvDailyPriceValue = findViewById(R.id.tvDailyPriceValue)
        tvAvailabilitySummary = findViewById(R.id.tvAvailabilitySummary)
        tvTagBodyType = findViewById(R.id.tvTagBodyType)
        tvTagTransmission = findViewById(R.id.tvTagTransmission)
        tvTagFuel = findViewById(R.id.tvTagFuel)
        tvTagSeats = findViewById(R.id.tvTagSeats)
        tvTagColor = findViewById(R.id.tvTagColor)
        tagsContainer = findViewById(R.id.tagsContainer)
        detailsTagsFlow = findViewById(R.id.detailsTagsFlow)
        tvConditionTagsTitle = findViewById(R.id.tvConditionTagsTitle)
        conditionTagsContainer = findViewById(R.id.conditionTagsContainer)
        conditionTagsFlow = findViewById(R.id.conditionTagsFlow)
        tvSafetyTagsTitle = findViewById(R.id.tvSafetyTagsTitle)
        safetyTagsContainer = findViewById(R.id.safetyTagsContainer)
        safetyTagsFlow = findViewById(R.id.safetyTagsFlow)
        tvComfortTagsTitle = findViewById(R.id.tvComfortTagsTitle)
        comfortTagsContainer = findViewById(R.id.comfortTagsContainer)
        comfortTagsFlow = findViewById(R.id.comfortTagsFlow)
        tvDeliveryTagsTitle = findViewById(R.id.tvDeliveryTagsTitle)
        deliveryTagsContainer = findViewById(R.id.deliveryTagsContainer)
        deliveryTagsFlow = findViewById(R.id.deliveryTagsFlow)
        tvTripTagsTitle = findViewById(R.id.tvTripTagsTitle)
        tripTagsContainer = findViewById(R.id.tripTagsContainer)
        tripTagsFlow = findViewById(R.id.tripTagsFlow)
        tvDocumentsTagsTitle = findViewById(R.id.tvDocumentsTagsTitle)
        documentsTagsContainer = findViewById(R.id.documentsTagsContainer)
        documentsTagsFlow = findViewById(R.id.documentsTagsFlow)
        additionalInfoFrame = findViewById(R.id.additionalInfoFrame)
        additionalInfoContainer = findViewById(R.id.additionalInfoContainer)
        btnToggleMoreInfo = findViewById(R.id.btnToggleMoreInfo)
        publicInfoContainer = findViewById(R.id.publicInfoContainer)
        tvBottomRentSubtitle = findViewById(R.id.tvBottomRentSubtitle)
        btnSeeAvailability = findViewById(R.id.btnSeeAvailability)
        cardApproximateLocationMap = findViewById(R.id.cardApproximateLocationMap)
        approximateMapView = findViewById(R.id.renterVehicleApproxMapView)
        tvApproximateLocationUnavailable = findViewById(R.id.tvApproximateLocationUnavailable)
        photoCarouselController = VehiclePhotoCarouselController(
            context = this,
            lifecycleScope = lifecycleScope,
            photoView = findViewById(R.id.ivVehiclePhotoCarousel),
            emptyView = findViewById(R.id.tvVehiclePhotosEmpty),
            prevButton = findViewById(R.id.btnPrevPhoto),
            nextButton = findViewById(R.id.btnNextPhoto),
            dotsContainer = findViewById(R.id.photoDotsContainer),
            progress = findViewById(R.id.progressVehiclePhoto),
            emptyMessageRes = R.string.renter_vehicle_details_photos_empty,
            loadErrorMessageRes = R.string.vehicle_error_open_photo_failed
        )

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        btnSeeAvailability.setOnClickListener { handleBottomActionClick() }
        tvBottomRentSubtitle.setOnClickListener {
            if (hasDateSelection()) {
                showAvailabilityPopup()
            }
        }
        btnToggleMoreInfo.setOnClickListener {
            isMoreInfoExpanded = !isMoreInfoExpanded
            applyMoreInfoState()
        }

        payloadJsonRaw = intent.getStringExtra(EXTRA_PAYLOAD_JSON).orEmpty()
        payload = runCatching { JSONObject(payloadJsonRaw) }.getOrElse { JSONObject() }
        setupApproximateLocationMap(payload)

        restoreDateSelectionFromMemory()
        bindVehicleDetails()
    }

    override fun onStart() {
        super.onStart()
        if (isApproximateMapInitialized) {
            approximateMapView?.onStart()
        }
    }

    override fun onResume() {
        super.onResume()
        if (isApproximateMapInitialized) {
            approximateMapView?.onResume()
        }
        restoreDateSelectionFromMemory()
        updateBottomSubtitle()
    }

    override fun onPause() {
        if (isApproximateMapInitialized) {
            approximateMapView?.onPause()
        }
        super.onPause()
    }

    override fun onStop() {
        if (isApproximateMapInitialized) {
            approximateMapView?.onStop()
        }
        super.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (isApproximateMapInitialized) {
            approximateMapView?.onLowMemory()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!isApproximateMapInitialized) return
        val mapState = Bundle()
        approximateMapView?.onSaveInstanceState(mapState)
        outState.putBundle(APPROXIMATE_MAP_STATE_KEY, mapState)
    }

    override fun onDestroy() {
        if (isApproximateMapInitialized) {
            approximateMapView?.onDestroy()
        }
        isApproximateMapInitialized = false
        approximateMapView = null
        approximateMap = null
        super.onDestroy()
    }

    private fun bindVehicleDetails() {
        val payloadObject = payload

        tvVehicleTitle.text = buildVehicleTitle(payloadObject)
        val subtitle = buildVehicleSubtitle(payloadObject)
        tvVehicleSubtitle.text = subtitle
        tvVehicleSubtitle.isVisible = subtitle.isNotBlank()

        val rawDailyPrice = payloadObject?.optString("dailyPrice").orEmpty().trim()
        dailyPriceValue = parseCurrencyToDouble(rawDailyPrice)
        tvDailyPriceValue.text = if (rawDailyPrice.isNotBlank()) {
            getString(R.string.renter_vehicle_details_daily_price_value, rawDailyPrice)
        } else {
            getString(R.string.renter_vehicle_details_daily_price_not_informed)
        }
        val availabilitySummary = buildAvailabilitySummary(payloadObject?.optJSONObject("weeklySchedule"))
        tvAvailabilitySummary.text = availabilitySummary.orEmpty()
        tvAvailabilitySummary.isVisible = !availabilitySummary.isNullOrBlank()
        tvTagBodyType.text = tagText(
            payloadObject?.optString("bodyType")
        )
        tvTagTransmission.text = tagText(
            payloadObject?.optString("transmissionType")
        )
        tvTagFuel.text = tagText(
            payloadObject?.optString("fuelType")
        )
        tvTagSeats.text = seatsTagText(
            payloadObject?.optString("seats")
        )
        tvTagColor.text = prefixedTagText(
            prefix = getString(R.string.renter_vehicle_details_tag_prefix_color),
            value = payloadObject?.optString("color")
        )
        renderHighlightTags(payloadObject)
        renderConditionTags(payloadObject)
        renderSafetyTags(payloadObject)
        renderComfortTags(payloadObject)
        renderDeliveryTags(payloadObject)
        renderTripTags(payloadObject)
        renderDocumentsTags(payloadObject)

        renderPublicInfo(payloadObject)
        configureMoreInfoSection()
        photoCarouselController.bindFromPayload(payloadObject)
        updateBottomSubtitle()
    }

    override fun onMapReady(map: MapLibreMap) {
        approximateMap = map
        val center = obfuscatedCenter ?: return

        map.setStyle(Style.Builder().fromJson(buildOsmRasterStyleJson())) { style ->
            map.uiSettings.apply {
                setCompassEnabled(false)
                setRotateGesturesEnabled(false)
                setScrollGesturesEnabled(false)
                setTiltGesturesEnabled(false)
                setZoomGesturesEnabled(false)
                setDoubleTapGesturesEnabled(false)
                setQuickZoomGesturesEnabled(false)
            }
            renderApproximateLocationArea(style = style, center = center, radiusMeters = OBFUSCATION_RADIUS_METERS)
        }
    }

    private fun setupApproximateLocationMap(payloadObject: JSONObject?) {
        val mapView = approximateMapView ?: return
        val exactPickupLocation = resolveExactPickupLocation(payloadObject)
        if (exactPickupLocation == null) {
            isApproximateMapInitialized = false
            tvApproximateLocationUnavailable.isVisible = true
            mapView.isVisible = false
            return
        }

        val vehicleId = intent.getStringExtra(EXTRA_VEHICLE_ID).orEmpty()
        obfuscatedCenter = buildObfuscatedCenter(exactPickupLocation, vehicleId)
        approximateMarkerIconName = resolveApproximateMarkerIconName(payloadObject)

        tvApproximateLocationUnavailable.isVisible = false
        mapView.isVisible = true
        cardApproximateLocationMap.isVisible = true
        mapView.onCreate(approximateMapSavedState)
        isApproximateMapInitialized = true
        mapView.getMapAsync(this)
    }

    private fun resolveExactPickupLocation(payloadObject: JSONObject?): LatLng? {
        if (payloadObject == null) return null

        val latitude = payloadObject.optNullableDouble("pickupLatitude")
            ?: payloadObject.optNullableDouble("pickupLat")
            ?: payloadObject.optNullableDouble("latitude")
            ?: return null
        val longitude = payloadObject.optNullableDouble("pickupLongitude")
            ?: payloadObject.optNullableDouble("pickupLng")
            ?: payloadObject.optNullableDouble("pickupLon")
            ?: payloadObject.optNullableDouble("longitude")
            ?: return null

        if (latitude !in -90.0..90.0) return null
        if (longitude !in -180.0..180.0) return null
        return LatLng(latitude, longitude)
    }

    private fun resolveApproximateMarkerIconName(payloadObject: JSONObject?): String {
        val vehicleType = payloadObject
            ?.optString("vehicleType")
            .orEmpty()
            .trim()
            .lowercase(Locale.ROOT)

        return if (vehicleType == VEHICLE_TYPE_MOTORCYCLE) {
            MAP_BALLOON_ICON_MOTORCYCLE
        } else {
            MAP_BALLOON_ICON_CAR
        }
    }

    private fun buildObfuscatedCenter(exactLocation: LatLng, vehicleId: String): LatLng {
        val random = Random(calculateObfuscationSeed(exactLocation, vehicleId))
        val offsetMeters = MIN_OBFUSCATION_OFFSET_METERS +
            (random.nextDouble() * (MAX_OBFUSCATION_OFFSET_METERS - MIN_OBFUSCATION_OFFSET_METERS))
        val bearingDegrees = random.nextDouble() * 360.0

        return destinationPoint(
            start = exactLocation,
            distanceMeters = offsetMeters,
            bearingDegrees = bearingDegrees
        )
    }

    private fun calculateObfuscationSeed(exactLocation: LatLng, vehicleId: String): Long {
        val seedSource = buildString {
            append(vehicleId)
            append('|')
            append(String.format(Locale.US, "%.6f", exactLocation.latitude))
            append('|')
            append(String.format(Locale.US, "%.6f", exactLocation.longitude))
        }
        return seedSource.fold(1125899906842597L) { acc, char ->
            (acc * 31L) + char.code
        }
    }

    private fun destinationPoint(
        start: LatLng,
        distanceMeters: Double,
        bearingDegrees: Double
    ): LatLng {
        val angularDistance = distanceMeters / EARTH_RADIUS_METERS
        val bearingRad = Math.toRadians(bearingDegrees)
        val startLatRad = Math.toRadians(start.latitude)
        val startLngRad = Math.toRadians(start.longitude)

        val targetLatRad = asin(
            sin(startLatRad) * cos(angularDistance) +
                cos(startLatRad) * sin(angularDistance) * cos(bearingRad)
        )
        val targetLngRad = startLngRad + atan2(
            sin(bearingRad) * sin(angularDistance) * cos(startLatRad),
            cos(angularDistance) - sin(startLatRad) * sin(targetLatRad)
        )

        val targetLatitude = Math.toDegrees(targetLatRad)
        val targetLongitude = normalizeLongitude(Math.toDegrees(targetLngRad))
        return LatLng(targetLatitude, targetLongitude)
    }

    private fun normalizeLongitude(longitude: Double): Double {
        var normalized = longitude
        while (normalized > 180.0) normalized -= 360.0
        while (normalized < -180.0) normalized += 360.0
        return normalized
    }

    private fun renderApproximateLocationArea(style: Style, center: LatLng, radiusMeters: Double) {
        val areaRing = buildCircleRing(center = center, radiusMeters = radiusMeters, points = 72)
        if (areaRing.size < 4) {
            tvApproximateLocationUnavailable.isVisible = true
            approximateMapView?.isVisible = false
            return
        }

        val areaFeatureCollection = FeatureCollection.fromFeature(
            Feature.fromGeometry(Polygon.fromLngLats(listOf(areaRing)))
        )
        val centerFeatureCollection = FeatureCollection.fromFeature(
            Feature.fromGeometry(Point.fromLngLat(center.longitude, center.latitude))
        )

        var areaSource = style.getSourceAs<GeoJsonSource>(APPROXIMATE_AREA_SOURCE_ID)
        if (areaSource == null) {
            areaSource = GeoJsonSource(APPROXIMATE_AREA_SOURCE_ID, areaFeatureCollection)
            style.addSource(areaSource)
        } else {
            areaSource.setGeoJson(areaFeatureCollection)
        }

        var centerSource = style.getSourceAs<GeoJsonSource>(APPROXIMATE_CENTER_SOURCE_ID)
        if (centerSource == null) {
            centerSource = GeoJsonSource(APPROXIMATE_CENTER_SOURCE_ID, centerFeatureCollection)
            style.addSource(centerSource)
        } else {
            centerSource.setGeoJson(centerFeatureCollection)
        }

        val secondaryColor = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorSecondary,
            ContextCompat.getColor(this, android.R.color.holo_blue_light)
        )
        val areaFillColor = blendColorWithWhite(
            color = secondaryColor,
            whiteBlendFactor = APPROXIMATE_AREA_LIGHT_BLEND_FACTOR
        )

        if (style.getLayer(APPROXIMATE_AREA_FILL_LAYER_ID) == null) {
            style.addLayer(
                FillLayer(APPROXIMATE_AREA_FILL_LAYER_ID, APPROXIMATE_AREA_SOURCE_ID).withProperties(
                    fillColor(colorToHexRgb(areaFillColor)),
                    fillOpacity(APPROXIMATE_AREA_FILL_OPACITY)
                )
            )
        }
        if (style.getLayer(APPROXIMATE_AREA_STROKE_LAYER_ID) == null) {
            style.addLayer(
                LineLayer(APPROXIMATE_AREA_STROKE_LAYER_ID, APPROXIMATE_AREA_SOURCE_ID).withProperties(
                    lineColor(colorToHexRgb(secondaryColor)),
                    lineWidth(APPROXIMATE_AREA_STROKE_WIDTH)
                )
            )
        }
        buildApproximateCenterBalloonBitmap(iconName = approximateMarkerIconName)?.let { centerBitmap ->
            style.addImage(APPROXIMATE_CENTER_IMAGE_ID, centerBitmap)
        }
        if (style.getLayer(APPROXIMATE_CENTER_LAYER_ID) == null && style.getImage(APPROXIMATE_CENTER_IMAGE_ID) != null) {
            style.addLayer(
                SymbolLayer(APPROXIMATE_CENTER_LAYER_ID, APPROXIMATE_CENTER_SOURCE_ID).withProperties(
                    iconImage(APPROXIMATE_CENTER_IMAGE_ID),
                    iconAnchor("bottom"),
                    iconAllowOverlap(true),
                    iconIgnorePlacement(true)
                )
            )
        }

        approximateMap?.let { map ->
            val boundsBuilder = LatLngBounds.Builder().include(center)
            areaRing.forEach { point ->
                boundsBuilder.include(LatLng(point.latitude(), point.longitude()))
            }
            val paddingPx = dpToPx(APPROXIMATE_MAP_CAMERA_PADDING_DP)
            val bounds = boundsBuilder.build()
            map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, paddingPx))
        }
    }

    private fun buildCircleRing(center: LatLng, radiusMeters: Double, points: Int): List<Point> {
        if (points < 3) return emptyList()

        val ring = mutableListOf<Point>()
        val step = 360.0 / points
        var angle = 0.0
        while (angle < 360.0) {
            val point = destinationPoint(
                start = center,
                distanceMeters = radiusMeters,
                bearingDegrees = angle
            )
            ring += Point.fromLngLat(point.longitude, point.latitude)
            angle += step
        }
        ring += ring.first()
        return ring
    }

    private fun buildApproximateCenterBalloonBitmap(iconName: String): Bitmap? {
        val density = resources.displayMetrics.density
        val bitmapWidth = (BALLOON_BITMAP_WIDTH_DP * density).roundToInt().coerceAtLeast(1)
        val bitmapHeight = (BALLOON_BITMAP_HEIGHT_DP * density).roundToInt().coerceAtLeast(1)
        val bubbleCornerRadius = BALLOON_CORNER_RADIUS_DP * density
        val pointerHeight = BALLOON_POINTER_HEIGHT_DP * density
        val pointerHalfWidth = BALLOON_POINTER_HALF_WIDTH_DP * density
        val bubbleBottom = bitmapHeight.toFloat() - pointerHeight
        val bubbleCenterX = bitmapWidth / 2f

        val fillColor = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorSecondary,
            ContextCompat.getColor(this, android.R.color.holo_green_light)
        )
        val strokeColor = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorOutline,
            ContextCompat.getColor(this, android.R.color.darker_gray)
        )
        val iconColor = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorPrimary,
            ContextCompat.getColor(this, android.R.color.holo_blue_dark)
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

        val iconTypeface = ResourcesCompat.getFont(this, R.font.material_symbols_rounded)
        val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = iconTypeface ?: Typeface.DEFAULT
            color = iconColor
            textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                BALLOON_ICON_SIZE_SP,
                resources.displayMetrics
            )
            fontFeatureSettings = "'liga'"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                fontVariationSettings = "'FILL' 1, 'wght' 600, 'GRAD' 0, 'opsz' 24"
            }
        }

        val iconCenterY = (bubbleBottom / 2f) - ((iconPaint.descent() + iconPaint.ascent()) / 2f)
        canvas.drawText(iconName, bubbleCenterX, iconCenterY, iconPaint)

        return bitmap
    }

    private fun blendColorWithWhite(color: Int, whiteBlendFactor: Float): Int {
        val blend = whiteBlendFactor.coerceIn(0f, 1f)
        val inverse = 1f - blend
        val red = (Color.red(color) * inverse + 255f * blend).roundToInt().coerceIn(0, 255)
        val green = (Color.green(color) * inverse + 255f * blend).roundToInt().coerceIn(0, 255)
        val blue = (Color.blue(color) * inverse + 255f * blend).roundToInt().coerceIn(0, 255)
        return Color.rgb(red, green, blue)
    }

    private fun colorToHexRgb(color: Int): String {
        return String.format(
            Locale.US,
            "#%02X%02X%02X",
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
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
              "name": "Approximate Area Raster",
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

    private fun buildVehicleTitle(payloadObject: JSONObject?): String {
        val brand = payloadObject?.optString("brand").orEmpty().trim()
        val model = payloadObject?.optString("model").orEmpty().trim()
        val manufactureYear = payloadObject?.optString("manufactureYear").orEmpty().trim()
        val modelYear = payloadObject?.optString("modelYear").orEmpty().trim()
        val yearLabel = when {
            manufactureYear.isNotBlank() && modelYear.isNotBlank() -> "$manufactureYear/$modelYear"
            manufactureYear.isNotBlank() -> manufactureYear
            modelYear.isNotBlank() -> modelYear
            else -> ""
        }

        return listOf(brand, model, yearLabel)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { getString(R.string.renter_vehicle_details_unknown_vehicle) }
    }

    private fun buildVehicleSubtitle(payloadObject: JSONObject?): String {
        val cityState = payloadObject?.optString("cityState").orEmpty().trim()
        val neighborhood = payloadObject?.optString("neighborhood").orEmpty().trim()
        return listOf(neighborhood, cityState)
            .filter { it.isNotBlank() }
            .joinToString(" - ")
    }

    private fun renderPublicInfo(payloadObject: JSONObject?) {
        publicInfoContainer.removeAllViews()

        val lines = emptyList<String>()
        publicInfoContainer.isVisible = lines.isNotEmpty()
        if (lines.isEmpty()) return

        lines.forEachIndexed { index, line ->
            val tvLine = TextView(this).apply {
                text = line
                textSize = 14f
                setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
            }
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            if (index > 0) {
                params.topMargin = dpToPx(6)
            }
            publicInfoContainer.addView(tvLine, params)
        }
    }

    private fun configureMoreInfoSection() {
        val hasAdditionalContent = hasVisibleChildren(additionalInfoContainer)
        if (!hasAdditionalContent) {
            additionalInfoFrame.isVisible = false
            btnToggleMoreInfo.isVisible = false
            return
        }

        btnToggleMoreInfo.isVisible = true
        isMoreInfoExpanded = false
        applyMoreInfoState()
    }

    private fun applyMoreInfoState() {
        if (isMoreInfoExpanded) {
            additionalInfoFrame.isVisible = true
            btnToggleMoreInfo.text = getString(R.string.renter_vehicle_details_show_less_info)
        } else {
            additionalInfoFrame.isVisible = false
            btnToggleMoreInfo.text = getString(R.string.renter_vehicle_details_show_more_info)
        }
    }

    private fun hasVisibleChildren(container: ViewGroup): Boolean {
        for (index in 0 until container.childCount) {
            if (container.getChildAt(index).isVisible) {
                return true
            }
        }
        return false
    }

    private fun infoLine(label: String, value: String?): String {
        val safeValue = value.orEmpty().trim().ifBlank { getString(R.string.vehicle_summary_not_informed) }
        return getString(R.string.provider_vehicle_details_line, label, safeValue)
    }

    private fun tagText(value: String?): String {
        return value.orEmpty().trim().ifBlank { getString(R.string.vehicle_summary_not_informed) }
    }

    private fun seatsTagText(value: String?): String {
        val safeValue = value.orEmpty().trim()
        return if (safeValue.isBlank()) {
            getString(R.string.vehicle_summary_not_informed)
        } else {
            getString(R.string.renter_vehicle_details_tag_seats_format, safeValue)
        }
    }

    private fun prefixedTagText(prefix: String, value: String?): String {
        val safeValue = value.orEmpty().trim().ifBlank { getString(R.string.vehicle_summary_not_informed) }
        return "$prefix: $safeValue"
    }

    private fun mileageConditionTagText(value: String?): String {
        val safeValue = value.orEmpty().trim().ifBlank { getString(R.string.vehicle_summary_not_informed) }
        return getString(R.string.renter_vehicle_details_condition_mileage_tag, safeValue)
    }

    private fun renderHighlightTags(payloadObject: JSONObject?) {
        val tags = optStringList(payloadObject, "highlightTags")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        clearDynamicTags(tagsContainer, DYNAMIC_HIGHLIGHT_TAG_MARKER)

        val referencedIds = mutableListOf(
            R.id.tvTagBodyType,
            R.id.tvTagTransmission,
            R.id.tvTagFuel,
            R.id.tvTagSeats,
            R.id.tvTagColor
        )

        tags.forEach { tag ->
            val tagView = buildTagView(tag, DYNAMIC_HIGHLIGHT_TAG_MARKER)
            tagsContainer.addView(tagView)
            referencedIds += tagView.id
        }

        detailsTagsFlow.referencedIds = referencedIds.toIntArray()
    }

    private fun renderConditionTags(payloadObject: JSONObject?) {
        val tags = listOf(
            conditionLabel(payloadObject?.optString("condition")),
            mileageConditionTagText(payloadObject?.optString("mileage"))
        ).map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        clearDynamicTags(conditionTagsContainer, DYNAMIC_CONDITION_TAG_MARKER)

        val referencedIds = mutableListOf<Int>()
        tags.forEach { tag ->
            val tagView = buildTagView(tag, DYNAMIC_CONDITION_TAG_MARKER)
            conditionTagsContainer.addView(tagView)
            referencedIds += tagView.id
        }
        conditionTagsFlow.referencedIds = referencedIds.toIntArray()

        val hasTags = tags.isNotEmpty()
        tvConditionTagsTitle.isVisible = hasTags
        conditionTagsContainer.isVisible = hasTags
    }

    private fun renderSafetyTags(payloadObject: JSONObject?) {
        val tags = optStringList(payloadObject, "safetyItems")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toMutableList()

        val insuranceTag = when (optNullableBoolean(payloadObject, "hasInsurance")) {
            true -> getString(R.string.renter_vehicle_details_safety_insurance_yes)
            false -> getString(R.string.renter_vehicle_details_safety_insurance_no)
            null -> getString(R.string.renter_vehicle_details_safety_insurance_not_informed)
        }
        tags += insuranceTag

        val uniqueTags = tags.distinct()
        clearDynamicTags(safetyTagsContainer, DYNAMIC_SAFETY_TAG_MARKER)

        val referencedIds = mutableListOf<Int>()
        uniqueTags.forEach { tag ->
            val tagView = buildTagView(tag, DYNAMIC_SAFETY_TAG_MARKER)
            safetyTagsContainer.addView(tagView)
            referencedIds += tagView.id
        }
        safetyTagsFlow.referencedIds = referencedIds.toIntArray()

        val hasTags = uniqueTags.isNotEmpty()
        tvSafetyTagsTitle.isVisible = hasTags
        safetyTagsContainer.isVisible = hasTags
    }

    private fun renderComfortTags(payloadObject: JSONObject?) {
        val tags = optStringList(payloadObject, "comfortItems")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        clearDynamicTags(comfortTagsContainer, DYNAMIC_COMFORT_TAG_MARKER)

        val referencedIds = mutableListOf<Int>()
        tags.forEach { tag ->
            val tagView = buildTagView(tag, DYNAMIC_COMFORT_TAG_MARKER)
            comfortTagsContainer.addView(tagView)
            referencedIds += tagView.id
        }
        comfortTagsFlow.referencedIds = referencedIds.toIntArray()

        val hasTags = tags.isNotEmpty()
        tvComfortTagsTitle.isVisible = hasTags
        comfortTagsContainer.isVisible = hasTags
    }

    private fun renderDeliveryTags(payloadObject: JSONObject?) {
        val tags = mutableListOf<String>()

        val pickupOnLocation = optNullableBoolean(payloadObject, "pickupOnLocation")
        val deliveryByFee = optNullableBoolean(payloadObject, "deliveryByFee")

        if (pickupOnLocation == true) {
            tags += getString(R.string.vehicle_delivery_pickup_on_location)
        }
        if (deliveryByFee == true) {
            tags += getString(R.string.vehicle_delivery_by_fee)
        }
        if (tags.isEmpty()) {
            tags += getString(R.string.renter_vehicle_details_delivery_type_none)
        }

        tags += buildDeliveryRadiusTag(payloadObject?.optString("deliveryRadiusKm"))
        tags += buildDeliveryFeeTag(payloadObject?.optString("deliveryFee"))

        val uniqueTags = tags
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        clearDynamicTags(deliveryTagsContainer, DYNAMIC_DELIVERY_TAG_MARKER)

        val referencedIds = mutableListOf<Int>()
        uniqueTags.forEach { tag ->
            val tagView = buildTagView(tag, DYNAMIC_DELIVERY_TAG_MARKER)
            deliveryTagsContainer.addView(tagView)
            referencedIds += tagView.id
        }
        deliveryTagsFlow.referencedIds = referencedIds.toIntArray()

        val hasTags = uniqueTags.isNotEmpty()
        tvDeliveryTagsTitle.isVisible = hasTags
        deliveryTagsContainer.isVisible = hasTags
    }

    private fun renderTripTags(payloadObject: JSONObject?) {
        val tags = mutableListOf<String>()
        val allowTrip = optNullableBoolean(payloadObject, "allowTrip")

        val allowTripTag = when (allowTrip) {
            true -> getString(R.string.renter_vehicle_details_trip_allowed_yes)
            false -> getString(R.string.renter_vehicle_details_trip_allowed_no)
            null -> getString(R.string.renter_vehicle_details_trip_allowed_not_informed)
        }
        tags += allowTripTag

        if (allowTrip == true) {
            tags += optStringList(payloadObject, "allowedTripTypes")
                .map { it.trim() }
                .filter { it.isNotBlank() }
        }

        val uniqueTags = tags.distinct()
        clearDynamicTags(tripTagsContainer, DYNAMIC_TRIP_TAG_MARKER)

        val referencedIds = mutableListOf<Int>()
        uniqueTags.forEach { tag ->
            val tagView = buildTagView(tag, DYNAMIC_TRIP_TAG_MARKER)
            tripTagsContainer.addView(tagView)
            referencedIds += tagView.id
        }
        tripTagsFlow.referencedIds = referencedIds.toIntArray()

        val hasTags = uniqueTags.isNotEmpty()
        tvTripTagsTitle.isVisible = hasTags
        tripTagsContainer.isVisible = hasTags
    }

    private fun renderDocumentsTags(payloadObject: JSONObject?) {
        val documentsUpToDate = optNullableBoolean(payloadObject, "documentsUpToDate")
        val ipvaLicensingOk = optNullableBoolean(payloadObject, "ipvaLicensingOk")

        val tags = listOf(
            when (documentsUpToDate) {
                true -> getString(R.string.renter_vehicle_details_documents_general_yes)
                false -> getString(R.string.renter_vehicle_details_documents_general_no)
                null -> getString(R.string.renter_vehicle_details_documents_general_not_informed)
            },
            when (ipvaLicensingOk) {
                true -> getString(R.string.renter_vehicle_details_documents_ipva_yes)
                false -> getString(R.string.renter_vehicle_details_documents_ipva_no)
                null -> getString(R.string.renter_vehicle_details_documents_ipva_not_informed)
            }
        ).distinct()

        clearDynamicTags(documentsTagsContainer, DYNAMIC_DOCUMENTS_TAG_MARKER)

        val referencedIds = mutableListOf<Int>()
        tags.forEach { tag ->
            val tagView = buildTagView(tag, DYNAMIC_DOCUMENTS_TAG_MARKER)
            documentsTagsContainer.addView(tagView)
            referencedIds += tagView.id
        }
        documentsTagsFlow.referencedIds = referencedIds.toIntArray()

        val hasTags = tags.isNotEmpty()
        tvDocumentsTagsTitle.isVisible = hasTags
        documentsTagsContainer.isVisible = hasTags
    }

    private fun buildDeliveryRadiusTag(rawRadius: String?): String {
        val normalized = rawRadius.orEmpty()
            .trim()
            .replace("km", "", ignoreCase = true)
            .trim()

        return if (normalized.isBlank()) {
            getString(R.string.renter_vehicle_details_delivery_radius_not_informed)
        } else {
            getString(R.string.renter_vehicle_details_delivery_radius_tag, normalized)
        }
    }

    private fun buildDeliveryFeeTag(rawFee: String?): String {
        val value = rawFee.orEmpty().trim()
        if (value.isBlank()) {
            return getString(R.string.renter_vehicle_details_delivery_fee_not_informed)
        }

        val formattedFee = parseCurrencyToDouble(value)
            ?.let(::formatCurrency)
            ?: value
        return getString(R.string.renter_vehicle_details_delivery_fee_tag, formattedFee)
    }

    private fun clearDynamicTags(container: ViewGroup, marker: String) {
        for (index in container.childCount - 1 downTo 0) {
            val child = container.getChildAt(index)
            if (child.tag == marker) {
                container.removeViewAt(index)
            }
        }
    }

    private fun buildTagView(tag: String, marker: String): TextView {
        return TextView(this).apply {
            id = View.generateViewId()
            this.tag = marker
            setBackgroundResource(R.drawable.bg_vehicle_detail_tag)
            setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6))
            setTextColor(MaterialColors.getColor(this, R.attr.textPrimary))
            textSize = 10f
            setTypeface(typeface, Typeface.BOLD)
            text = tag
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

    private fun buildAvailabilitySummary(weeklySchedule: JSONObject?): String? {
        val parsedRules = parseWeekdayRules(weeklySchedule) ?: return null
        val bookableRules = parsedRules
            .filterValues { it.isBookableByDefinition() }
        if (bookableRules.isEmpty()) return null

        val primaryTextRes = when {
            bookableRules.size == 7 -> R.string.renter_vehicle_details_availability_full_week
            bookableRules.keys == setOf(
                DayOfWeek.MONDAY,
                DayOfWeek.TUESDAY,
                DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY,
                DayOfWeek.FRIDAY
            ) -> R.string.renter_vehicle_details_availability_mon_to_fri

            else -> R.string.renter_vehicle_details_availability_selected_days
        }

        val detailTextRes = when {
            bookableRules.values.all { it.allDay } -> R.string.renter_vehicle_details_availability_all_day
            bookableRules.values.all { !it.allDay && it.hasValidTimeWindow() } -> {
                R.string.renter_vehicle_details_availability_time_restricted
            }

            else -> R.string.renter_vehicle_details_availability_mixed_hours
        }

        return getString(
            R.string.renter_vehicle_details_availability_format,
            getString(primaryTextRes),
            getString(detailTextRes)
        )
    }

    private fun parseWeekdayRules(weeklySchedule: JSONObject?): Map<DayOfWeek, DayAvailabilityRule>? {
        if (weeklySchedule == null || weeklySchedule.length() == 0) return null

        val result = linkedMapOf<DayOfWeek, DayAvailabilityRule>()
        var hasAnyScheduleEntry = false
        val mappings = listOf(
            VehicleRegisterViewModel.DAY_MON to DayOfWeek.MONDAY,
            VehicleRegisterViewModel.DAY_TUE to DayOfWeek.TUESDAY,
            VehicleRegisterViewModel.DAY_WED to DayOfWeek.WEDNESDAY,
            VehicleRegisterViewModel.DAY_THU to DayOfWeek.THURSDAY,
            VehicleRegisterViewModel.DAY_FRI to DayOfWeek.FRIDAY,
            VehicleRegisterViewModel.DAY_SAT to DayOfWeek.SATURDAY,
            VehicleRegisterViewModel.DAY_SUN to DayOfWeek.SUNDAY
        )

        mappings.forEach { (jsonKey, dayOfWeek) ->
            if (!weeklySchedule.has(jsonKey)) return@forEach
            hasAnyScheduleEntry = true
            val entry = weeklySchedule.opt(jsonKey)
            result[dayOfWeek] = parseDayAvailabilityRule(entry)
        }

        if (!hasAnyScheduleEntry) return null
        return result
    }

    private fun parseDayAvailabilityRule(entry: Any?): DayAvailabilityRule {
        return when (entry) {
            is JSONObject -> {
                val enabled = entry.optBoolean("enabled", false)
                val allDay = entry.optBoolean("allDay", false)
                val start = entry.optString("startTime").trim().ifBlank { null }
                val end = entry.optString("endTime").trim().ifBlank { null }
                val startTime = if (allDay) null else parseTime(start)
                val endTime = if (allDay) null else parseTime(end)
                DayAvailabilityRule(
                    enabled = enabled,
                    allDay = allDay,
                    startTime = startTime,
                    endTime = endTime
                )
            }

            is Boolean -> DayAvailabilityRule(
                enabled = entry,
                allDay = entry,
                startTime = null,
                endTime = null
            )

            is String -> {
                val enabled = entry.trim().equals("true", ignoreCase = true)
                DayAvailabilityRule(
                    enabled = enabled,
                    allDay = enabled,
                    startTime = null,
                    endTime = null
                )
            }

            else -> DayAvailabilityRule(
                enabled = false,
                allDay = false,
                startTime = null,
                endTime = null
            )
        }
    }

    private fun parseTime(raw: String?): LocalTime? {
        val normalized = normalizeTimeValue(raw) ?: return null

        return runCatching { LocalTime.parse(normalized, DateTimeFormatter.ISO_LOCAL_TIME) }.getOrNull()
            ?: runCatching { LocalTime.parse(normalized, DateTimeFormatter.ofPattern("HH:mm")) }.getOrNull()
            ?: runCatching { LocalTime.parse(normalized, DateTimeFormatter.ofPattern("H:mm")) }.getOrNull()
    }

    private fun normalizeTimeValue(raw: String?): String? {
        val source = raw?.trim().orEmpty()
        if (source.isBlank()) return null

        var value = source.lowercase()
            .replace(".", ":")
            .replace("h", ":")
            .replace(Regex("\\s+"), "")
            .trim(':')

        if (value.matches(Regex("^\\d{1,2}$"))) {
            value += ":00"
        }

        if (!value.matches(Regex("^\\d{1,2}:\\d{1,2}(:\\d{1,2})?$"))) {
            return null
        }

        val segments = value.split(":").toMutableList()
        if (segments[0].length == 1) segments[0] = "0${segments[0]}"
        if (segments[1].length == 1) segments[1] = "0${segments[1]}"
        if (segments.size > 2 && segments[2].length == 1) {
            segments[2] = "0${segments[2]}"
        }
        return segments.joinToString(":")
    }

    private fun optStringList(payloadObject: JSONObject?, key: String): List<String> {
        if (payloadObject == null || payloadObject.isNull(key)) return emptyList()
        return when (val value = payloadObject.opt(key)) {
            is JSONArray -> {
                buildList {
                    for (index in 0 until value.length()) {
                        val item = value.optString(index).trim()
                        if (item.isNotBlank()) add(item)
                    }
                }
            }

            is Collection<*> -> value.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotBlank) }
            is String -> value.split(",").map { it.trim() }.filter { it.isNotBlank() }
            else -> emptyList()
        }
    }

    private fun optStringMap(payloadObject: JSONObject?, key: String): Map<String, String> {
        if (payloadObject == null || payloadObject.isNull(key)) return emptyMap()
        val raw = payloadObject.opt(key)
        return when (raw) {
            is JSONObject -> {
                val result = linkedMapOf<String, String>()
                val keys = raw.keys()
                while (keys.hasNext()) {
                    val itemKey = keys.next().trim()
                    val itemValue = raw.optString(itemKey).trim()
                    if (itemKey.isNotBlank() && itemValue.isNotBlank()) {
                        result[itemKey] = itemValue
                    }
                }
                result
            }

            is Map<*, *> -> {
                raw.mapNotNull { (mapKey, mapValue) ->
                    val itemKey = mapKey?.toString()?.trim().orEmpty()
                    val itemValue = mapValue?.toString()?.trim().orEmpty()
                    if (itemKey.isNotBlank() && itemValue.isNotBlank()) itemKey to itemValue else null
                }.toMap()
            }

            else -> emptyMap()
        }
    }

    private fun optNullableBoolean(payloadObject: JSONObject?, key: String): Boolean? {
        if (payloadObject == null || payloadObject.isNull(key)) return null
        return when (val value = payloadObject.opt(key)) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> when (value.trim().lowercase(Locale.ROOT)) {
                "true", "1", "yes", "sim" -> true
                "false", "0", "no", "nao" -> false
                else -> null
            }

            else -> null
        }
    }

    private fun JSONObject.optNullableDouble(key: String): Double? {
        if (isNull(key)) return null
        return when (val value = opt(key)) {
            is Number -> value.toDouble()
            is String -> value.trim().toDoubleOrNull()
            else -> null
        }
    }

    private fun showAvailabilityPopup() {
        val payloadObject = payload ?: return
        val availabilityRules = buildAvailabilityRules(payloadObject)
        val dialog = BottomSheetDialog(this)
        val sheetView = LayoutInflater.from(this)
            .inflate(R.layout.dialog_renter_vehicle_availability, null, false)
        dialog.setContentView(sheetView)

        val btnCloseCalendar = sheetView.findViewById<TextView>(R.id.btnCloseCalendar)
        val btnPrevMonth = sheetView.findViewById<TextView>(R.id.btnPrevMonth)
        val btnNextMonth = sheetView.findViewById<TextView>(R.id.btnNextMonth)
        val tvCurrentMonth = sheetView.findViewById<TextView>(R.id.tvCurrentMonth)
        val layoutWeekdayHeader = sheetView.findViewById<LinearLayout>(R.id.layoutWeekdayHeader)
        val rvAvailabilityCalendar = sheetView.findViewById<RecyclerView>(R.id.rvAvailabilityCalendar)
        val tvSelectedDatesSummary = sheetView.findViewById<TextView>(R.id.tvSelectedDatesSummary)
        val btnViewQuote = sheetView.findViewById<Button>(R.id.btnViewQuote)

        bindWeekdayHeader(layoutWeekdayHeader)

        var displayMonth = selectedStartDate?.let { YearMonth.from(it) } ?: YearMonth.now()
        var startDate = selectedStartDate
        var endDate = selectedEndDate
        if (startDate != null && endDate == null && isSingleDayOnlySelection(startDate, availabilityRules)) {
            endDate = startDate
        }

        lateinit var calendarAdapter: CalendarDaysAdapter
        calendarAdapter = CalendarDaysAdapter { clickedDate ->
            if (!isDateSelectable(clickedDate, availabilityRules)) return@CalendarDaysAdapter

            val updatedSelection = updateSelection(
                clickedDate = clickedDate,
                currentStart = startDate,
                currentEnd = endDate,
                rules = availabilityRules
            )
            startDate = updatedSelection.first
            endDate = updatedSelection.second

            renderMonthCells(
                adapter = calendarAdapter,
                month = displayMonth,
                rules = availabilityRules,
                start = startDate,
                end = endDate,
                monthLabel = tvCurrentMonth,
                prevMonthButton = btnPrevMonth
            )
            updateSelectionSummary(tvSelectedDatesSummary, startDate, endDate)
            btnViewQuote.isEnabled = startDate != null
        }

        rvAvailabilityCalendar.layoutManager = GridLayoutManager(this, 7)
        rvAvailabilityCalendar.adapter = calendarAdapter

        btnPrevMonth.setOnClickListener {
            val previousMonth = displayMonth.minusMonths(1)
            if (previousMonth.isBefore(YearMonth.now())) return@setOnClickListener
            displayMonth = previousMonth
            renderMonthCells(
                adapter = calendarAdapter,
                month = displayMonth,
                rules = availabilityRules,
                start = startDate,
                end = endDate,
                monthLabel = tvCurrentMonth,
                prevMonthButton = btnPrevMonth
            )
        }
        btnNextMonth.setOnClickListener {
            displayMonth = displayMonth.plusMonths(1)
            renderMonthCells(
                adapter = calendarAdapter,
                month = displayMonth,
                rules = availabilityRules,
                start = startDate,
                end = endDate,
                monthLabel = tvCurrentMonth,
                prevMonthButton = btnPrevMonth
            )
        }
        btnCloseCalendar.setOnClickListener { dialog.dismiss() }

        btnViewQuote.setOnClickListener {
            val confirmedStart = startDate ?: return@setOnClickListener
            persistDateSelectionToMemory(confirmedStart, endDate)
            updateBottomSubtitle()

            Toast.makeText(
                this,
                buildQuoteToastText(confirmedStart, endDate),
                Toast.LENGTH_LONG
            ).show()
            dialog.dismiss()
        }

        renderMonthCells(
            adapter = calendarAdapter,
            month = displayMonth,
            rules = availabilityRules,
            start = startDate,
            end = endDate,
            monthLabel = tvCurrentMonth,
            prevMonthButton = btnPrevMonth
        )
        updateSelectionSummary(tvSelectedDatesSummary, startDate, endDate)
        btnViewQuote.isEnabled = startDate != null

        dialog.show()
    }

    private fun updateBottomSubtitle() {
        val start = selectedStartDate
        val end = selectedEndDate

        tvBottomRentSubtitle.text = when {
            start == null -> getString(R.string.renter_vehicle_details_bottom_subtitle)
            end == null -> getString(
                R.string.renter_vehicle_details_bottom_subtitle_single_date,
                formatDisplayDate(start)
            )

            else -> {
                val days = rentalDays(start, end)
                val total = dailyPriceValue?.let { it * days }
                if (total != null) {
                    getString(
                        R.string.renter_vehicle_details_bottom_subtitle_with_budget,
                        days,
                        formatCurrency(total)
                    )
                } else {
                    getString(
                        R.string.renter_vehicle_details_bottom_subtitle_range,
                        formatDisplayDate(start),
                        formatDisplayDate(end)
                    )
                }
            }
        }

        updateBottomActionCta()
    }

    private fun restoreDateSelectionFromMemory() {
        val selection = RenterDateSelectionMemoryStore.read()
        selectedStartDate = selection?.startDate
        selectedEndDate = selection?.endDate
    }

    private fun persistDateSelectionToMemory(startDate: LocalDate, endDate: LocalDate?) {
        selectedStartDate = startDate
        selectedEndDate = endDate
        RenterDateSelectionMemoryStore.save(startDate = startDate, endDate = endDate)
    }

    private fun hasDateSelection(): Boolean = selectedStartDate != null

    private fun handleBottomActionClick() {
        val start = selectedStartDate
        if (start == null) {
            showAvailabilityPopup()
            return
        }

        Toast.makeText(
            this,
            buildQuoteToastText(start, selectedEndDate),
            Toast.LENGTH_LONG
        ).show()
    }

    private fun updateBottomActionCta() {
        val hasSelection = hasDateSelection()
        btnSeeAvailability.text = getString(
            if (hasSelection) {
                R.string.renter_vehicle_details_rent_now
            } else {
                R.string.renter_vehicle_details_see_availability
            }
        )
        tvBottomRentSubtitle.isClickable = hasSelection
        tvBottomRentSubtitle.isFocusable = hasSelection

        val currentFlags = tvBottomRentSubtitle.paintFlags
        tvBottomRentSubtitle.paintFlags = if (hasSelection) {
            currentFlags or Paint.UNDERLINE_TEXT_FLAG
        } else {
            currentFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
        }
    }

    private fun buildQuoteToastText(start: LocalDate, end: LocalDate?): String {
        return if (end == null || end == start) {
            getString(
                R.string.renter_vehicle_details_quote_single_day,
                formatDisplayDate(start)
            )
        } else {
            val days = rentalDays(start, end)
            val total = dailyPriceValue?.let { it * days }
            if (total != null) {
                getString(
                    R.string.renter_vehicle_details_quote_with_total,
                    formatDisplayDate(start),
                    formatDisplayDate(end),
                    days,
                    formatCurrency(total)
                )
            } else {
                getString(
                    R.string.renter_vehicle_details_quote_range,
                    formatDisplayDate(start),
                    formatDisplayDate(end),
                    days
                )
            }
        }
    }

    private fun updateSelectionSummary(
        summaryView: TextView,
        startDate: LocalDate?,
        endDate: LocalDate?
    ) {
        summaryView.text = when {
            startDate == null -> getString(R.string.renter_vehicle_details_calendar_selected_none)
            endDate == null -> getString(
                R.string.renter_vehicle_details_calendar_selected_start,
                formatDisplayDate(startDate)
            )
            endDate == startDate -> getString(
                R.string.renter_vehicle_details_calendar_selected_same_day,
                formatDisplayDate(startDate)
            )

            else -> getString(
                R.string.renter_vehicle_details_calendar_selected_range,
                formatDisplayDate(startDate),
                formatDisplayDate(endDate)
            )
        }
    }

    private fun renderMonthCells(
        adapter: CalendarDaysAdapter,
        month: YearMonth,
        rules: AvailabilityRules,
        start: LocalDate?,
        end: LocalDate?,
        monthLabel: TextView,
        prevMonthButton: View
    ) {
        monthLabel.text = monthTitle(month)
        prevMonthButton.alpha = if (month.isAfter(YearMonth.now())) 1f else 0.45f

        val cells = buildMonthCells(
            month = month,
            rules = rules,
            selectedStart = start,
            selectedEnd = end
        )
        adapter.submitList(cells)
    }

    private fun monthTitle(month: YearMonth): String {
        val locale = Locale.getDefault()
        val raw = month.atDay(1).format(DateTimeFormatter.ofPattern("MMMM yyyy", locale))
        return raw.replaceFirstChar { firstChar ->
            if (firstChar.isLowerCase()) firstChar.titlecase(locale) else firstChar.toString()
        }
    }

    private fun bindWeekdayHeader(container: LinearLayout) {
        container.removeAllViews()
        val locale = Locale.getDefault()
        val firstDayOfWeek = WeekFields.of(locale).firstDayOfWeek

        repeat(7) { index ->
            val dayOfWeek = firstDayOfWeek.plus(index.toLong())
            val textView = TextView(this).apply {
                text = dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
                    .replace(".", "")
                    .uppercase(locale)
                gravity = android.view.Gravity.CENTER
                textSize = 12f
                setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
            }
            container.addView(
                textView,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
        }
    }

    private fun buildAvailabilityRules(payloadObject: JSONObject): AvailabilityRules {
        val weekdayRules = parseWeekdayRules(payloadObject.optJSONObject("weeklySchedule"))
        val blockedDates = parseBlockedDates(payloadObject)
        return AvailabilityRules(
            weekdayRules = weekdayRules,
            blockedDates = blockedDates
        )
    }

    private fun parseBlockedDates(payloadObject: JSONObject): Set<LocalDate> {
        val blocked = linkedSetOf<LocalDate>()

        val singleDateKeys = listOf(
            "bookedDates",
            "blockedDates",
            "unavailableDates",
            "reservedDates",
            "rentedDates"
        )
        singleDateKeys.forEach { key ->
            if (!payloadObject.has(key) || payloadObject.isNull(key)) return@forEach
            addDateValues(payloadObject.opt(key), blocked)
        }

        val rangeKeys = listOf(
            "bookedDateRanges",
            "blockedDateRanges",
            "unavailableDateRanges",
            "reservedDateRanges",
            "rentedDateRanges",
            "rentalDateRanges",
            "bookings",
            "reservations"
        )
        rangeKeys.forEach { key ->
            if (!payloadObject.has(key) || payloadObject.isNull(key)) return@forEach
            addRangeValues(payloadObject.opt(key), blocked)
        }

        return blocked
    }

    private fun addDateValues(value: Any?, destination: MutableSet<LocalDate>) {
        when (value) {
            is JSONArray -> {
                for (index in 0 until value.length()) {
                    parseDate(value.opt(index))?.let(destination::add)
                }
            }

            is Collection<*> -> value.forEach { item -> parseDate(item)?.let(destination::add) }
            is String -> value.split(",").forEach { token -> parseDate(token)?.let(destination::add) }
            else -> parseDate(value)?.let(destination::add)
        }
    }

    private fun addRangeValues(value: Any?, destination: MutableSet<LocalDate>) {
        when (value) {
            is JSONArray -> {
                for (index in 0 until value.length()) {
                    addRangeItem(value.opt(index), destination)
                }
            }

            is Collection<*> -> value.forEach { item -> addRangeItem(item, destination) }
            else -> addRangeItem(value, destination)
        }
    }

    private fun addRangeItem(item: Any?, destination: MutableSet<LocalDate>) {
        if (item == null) return

        if (item is JSONObject) {
            val start = parseDate(
                item.optAny("startDate")
                    ?: item.optAny("start")
                    ?: item.optAny("from")
                    ?: item.optAny("checkIn")
                    ?: item.optAny("pickupDate")
            )
            val end = parseDate(
                item.optAny("endDate")
                    ?: item.optAny("end")
                    ?: item.optAny("to")
                    ?: item.optAny("checkOut")
                    ?: item.optAny("returnDate")
            )
            when {
                start != null && end != null -> addDateRange(start, end, destination)
                start != null -> destination += start
                else -> parseDate(item.optAny("date"))?.let(destination::add)
            }
            return
        }

        parseDate(item)?.let(destination::add)
    }

    private fun addDateRange(start: LocalDate, end: LocalDate, destination: MutableSet<LocalDate>) {
        var cursor = if (start.isBefore(end)) start else end
        val limit = if (start.isBefore(end)) end else start
        var count = 0

        while (!cursor.isAfter(limit) && count < MAX_RANGE_DAYS_FOR_BLOCKS) {
            destination += cursor
            cursor = cursor.plusDays(1)
            count++
        }
    }

    private fun JSONObject.optAny(key: String): Any? {
        if (isNull(key)) return null
        return opt(key)
    }

    private fun parseDate(value: Any?): LocalDate? {
        return when (value) {
            null -> null
            is LocalDate -> value
            is Number -> {
                val raw = value.toLong()
                val epochMillis = if (raw in 1..9_999_999_999L) raw * 1000L else raw
                runCatching {
                    Instant.ofEpochMilli(epochMillis)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate()
                }.getOrNull()
            }

            is String -> parseDateFromString(value)
            else -> parseDateFromString(value.toString())
        }
    }

    private fun parseDateFromString(rawValue: String): LocalDate? {
        val raw = rawValue.trim()
        if (raw.isBlank()) return null

        val directFormats = listOf(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd")
        )
        directFormats.forEach { formatter ->
            runCatching { return LocalDate.parse(raw, formatter) }
        }

        val fallback = raw.take(10)
        if (fallback.length == 10) {
            directFormats.forEach { formatter ->
                runCatching { return LocalDate.parse(fallback, formatter) }
            }
        }

        return null
    }

    private fun updateSelection(
        clickedDate: LocalDate,
        currentStart: LocalDate?,
        currentEnd: LocalDate?,
        rules: AvailabilityRules
    ): Pair<LocalDate?, LocalDate?> {
        if (isSingleDayOnlySelection(clickedDate, rules)) {
            return clickedDate to clickedDate
        }

        if (currentStart == null || currentEnd != null) {
            return clickedDate to null
        }
        if (clickedDate == currentStart) {
            return clickedDate to null
        }

        val start = if (clickedDate.isBefore(currentStart)) clickedDate else currentStart
        val end = if (clickedDate.isBefore(currentStart)) currentStart else clickedDate

        return if (isRangeSelectable(start, end, rules)) {
            start to end
        } else {
            if (isSingleDayOnlySelection(clickedDate, rules)) {
                clickedDate to clickedDate
            } else {
                clickedDate to null
            }
        }
    }

    private fun isSingleDayOnlySelection(date: LocalDate, rules: AvailabilityRules): Boolean {
        if (!isDateSelectable(date, rules)) return false

        val previousSelectable = isDateSelectable(date.minusDays(1), rules)
        val nextSelectable = isDateSelectable(date.plusDays(1), rules)
        return !previousSelectable && !nextSelectable
    }

    private fun isRangeSelectable(start: LocalDate, end: LocalDate, rules: AvailabilityRules): Boolean {
        var cursor = start
        var counter = 0
        while (!cursor.isAfter(end) && counter < MAX_RANGE_DAYS_FOR_BLOCKS) {
            if (!isDateSelectable(cursor, rules)) return false
            cursor = cursor.plusDays(1)
            counter++
        }
        return true
    }

    private fun isDateSelectable(date: LocalDate, rules: AvailabilityRules): Boolean {
        if (date.isBefore(LocalDate.now())) return false

        val dayRule = rules.weekdayRules?.get(date.dayOfWeek)
        if (rules.weekdayRules != null && (dayRule == null || !dayRule.isBookableOnDate(date))) {
            return false
        }

        if (date in rules.blockedDates) return false
        return true
    }

    private fun buildMonthCells(
        month: YearMonth,
        rules: AvailabilityRules,
        selectedStart: LocalDate?,
        selectedEnd: LocalDate?
    ): List<CalendarDayCell> {
        val locale = Locale.getDefault()
        val firstDayOfWeek = WeekFields.of(locale).firstDayOfWeek
        val firstDayInMonth = month.atDay(1)
        var leadingEmptyCount = firstDayInMonth.dayOfWeek.value - firstDayOfWeek.value
        if (leadingEmptyCount < 0) leadingEmptyCount += 7

        val cells = mutableListOf<CalendarDayCell>()
        repeat(leadingEmptyCount) {
            cells += CalendarDayCell.empty()
        }

        val today = LocalDate.now()
        for (dayOfMonth in 1..month.lengthOfMonth()) {
            val date = month.atDay(dayOfMonth)
            val isStart = selectedStart != null && date == selectedStart
            val isEnd = selectedEnd != null && date == selectedEnd
            val isInRange = selectedStart != null &&
                selectedEnd != null &&
                date.isAfter(selectedStart) &&
                date.isBefore(selectedEnd)
            cells += CalendarDayCell(
                date = date,
                isSelectable = isDateSelectable(date, rules),
                isStart = isStart,
                isEnd = isEnd,
                isInRange = isInRange,
                isToday = date == today
            )
        }

        while (cells.size % 7 != 0) {
            cells += CalendarDayCell.empty()
        }

        return cells
    }

    private fun formatDisplayDate(date: LocalDate): String {
        return date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.getDefault()))
    }

    private fun rentalDays(start: LocalDate, end: LocalDate): Long {
        return max(1L, ChronoUnit.DAYS.between(start, end))
    }

    private fun parseCurrencyToDouble(raw: String?): Double? {
        val source = raw.orEmpty().trim()
        if (source.isBlank()) return null

        val normalized = source
            .replace("R$", "", ignoreCase = true)
            .replace("\\s".toRegex(), "")

        return if (normalized.contains(",")) {
            normalized.replace(".", "").replace(",", ".").toDoubleOrNull()
        } else {
            normalized.toDoubleOrNull()
        }
    }

    private fun formatCurrency(value: Double): String {
        val locale = Locale.forLanguageTag("pt-BR")
        return NumberFormat.getCurrencyInstance(locale).format(value)
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private data class DayAvailabilityRule(
        val enabled: Boolean,
        val allDay: Boolean,
        val startTime: LocalTime?,
        val endTime: LocalTime?
    ) {
        fun hasValidTimeWindow(): Boolean {
            return startTime != null && endTime != null && endTime.isAfter(startTime)
        }

        fun isBookableByDefinition(): Boolean {
            return enabled && (allDay || hasValidTimeWindow())
        }

        fun isBookableOnDate(date: LocalDate): Boolean {
            if (!isBookableByDefinition()) return false
            if (allDay) return true
            if (date != LocalDate.now()) return true

            val end = endTime ?: return false
            return LocalTime.now().isBefore(end)
        }
    }

    private data class AvailabilityRules(
        val weekdayRules: Map<DayOfWeek, DayAvailabilityRule>?,
        val blockedDates: Set<LocalDate>
    )

    private data class CalendarDayCell(
        val date: LocalDate?,
        val isSelectable: Boolean,
        val isStart: Boolean,
        val isEnd: Boolean,
        val isInRange: Boolean,
        val isToday: Boolean
    ) {
        companion object {
            fun empty(): CalendarDayCell {
                return CalendarDayCell(
                    date = null,
                    isSelectable = false,
                    isStart = false,
                    isEnd = false,
                    isInRange = false,
                    isToday = false
                )
            }
        }
    }

    private class CalendarDaysAdapter(
        private val onDayClick: (LocalDate) -> Unit
    ) : RecyclerView.Adapter<CalendarDaysAdapter.DayViewHolder>() {

        private val items = mutableListOf<CalendarDayCell>()

        fun submitList(cells: List<CalendarDayCell>) {
            items.clear()
            items.addAll(cells)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DayViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_renter_calendar_day, parent, false)
            return DayViewHolder(view, onDayClick)
        }

        override fun onBindViewHolder(holder: DayViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        class DayViewHolder(
            itemView: View,
            private val onDayClick: (LocalDate) -> Unit
        ) : RecyclerView.ViewHolder(itemView) {

            private val cardDay: MaterialCardView = itemView.findViewById(R.id.cardDay)
            private val tvDay: TextView = itemView.findViewById(R.id.tvDay)

            fun bind(cell: CalendarDayCell) {
                if (cell.date == null) {
                    cardDay.visibility = View.INVISIBLE
                    tvDay.text = ""
                    itemView.setOnClickListener(null)
                    return
                }

                cardDay.visibility = View.VISIBLE
                tvDay.text = cell.date.dayOfMonth.toString()

                val secondary = MaterialColors.getColor(itemView, com.google.android.material.R.attr.colorSecondary)
                val onSecondary = MaterialColors.getColor(itemView, com.google.android.material.R.attr.colorOnSecondary)
                val onSurface = MaterialColors.getColor(itemView, com.google.android.material.R.attr.colorOnSurface)
                val onSurfaceVariant = MaterialColors.getColor(itemView, com.google.android.material.R.attr.colorOnSurfaceVariant)
                val surface = MaterialColors.getColor(itemView, com.google.android.material.R.attr.colorSurface)

                cardDay.strokeWidth = 0
                cardDay.alpha = 1f

                when {
                    cell.isStart || cell.isEnd -> {
                        cardDay.setCardBackgroundColor(secondary)
                        tvDay.setTextColor(onSecondary)
                        tvDay.alpha = 1f
                    }

                    cell.isInRange -> {
                        cardDay.setCardBackgroundColor(adjustAlpha(secondary, 0.22f))
                        tvDay.setTextColor(onSurface)
                        tvDay.alpha = 1f
                    }

                    else -> {
                        cardDay.setCardBackgroundColor(surface)
                        tvDay.setTextColor(if (cell.isSelectable) onSurface else onSurfaceVariant)
                    }
                }

                if (!cell.isStart && !cell.isEnd && cell.isToday) {
                    cardDay.strokeWidth = 1
                    cardDay.strokeColor = secondary
                }

                if (!cell.isSelectable) {
                    tvDay.alpha = 0.35f
                    cardDay.alpha = 0.7f
                } else {
                    tvDay.alpha = 1f
                    cardDay.alpha = 1f
                }

                itemView.setOnClickListener { onDayClick(cell.date) }
            }

            private fun adjustAlpha(color: Int, factor: Float): Int {
                val alpha = (android.graphics.Color.alpha(color) * factor).toInt()
                val red = android.graphics.Color.red(color)
                val green = android.graphics.Color.green(color)
                val blue = android.graphics.Color.blue(color)
                return android.graphics.Color.argb(alpha, red, green, blue)
            }
        }
    }
}
