package com.brunocodex.kotlinproject.fragments

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.brunocodex.kotlinproject.R
import com.brunocodex.kotlinproject.activities.RenterVehicleDetailsActivity
import com.brunocodex.kotlinproject.adapters.ProviderVehicleCardUi
import com.brunocodex.kotlinproject.adapters.ProviderVehiclesAdapter
import com.brunocodex.kotlinproject.services.NearbyVehiclesRepository
import com.brunocodex.kotlinproject.services.SQLiteConfiguration
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Task
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToLong

class RenterDiscoverFragment : Fragment(R.layout.fragment_renter_discover) {

    private lateinit var searchInput: TextInputEditText
    private lateinit var rvResults: RecyclerView
    private lateinit var tvSearchEmpty: TextView
    private lateinit var skeletonContainer: ViewGroup
    private lateinit var idleStateContainer: ViewGroup
    private lateinit var idleStateImage: ImageView
    private lateinit var discoverVehiclesAdapter: ProviderVehiclesAdapter
    private val skeletonAnimators = mutableListOf<ObjectAnimator>()
    private var idleStateAnimationJob: Job? = null
    private var idleStateImageIndex = 0

    private var searchJob: Job? = null
    private var latestSearchToken = 0L
    private var nearbyVehiclesRepository: NearbyVehiclesRepository? = null
    private var fusedLocationClient: FusedLocationProviderClient? = null

    private var cachedSearchCenter: SearchCenter? = null
    private var cachedNearbyVehicles = emptyList<NearbyVehiclesRepository.NearbyVehicle>()
    private var cachedNearbyVehiclesAtMs = 0L

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        nearbyVehiclesRepository = NearbyVehiclesRepository(requireContext().applicationContext)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())

        searchInput = view.findViewById(R.id.discoverVehicleSearchInput)
        rvResults = view.findViewById(R.id.rvDiscoverVehicles)
        tvSearchEmpty = view.findViewById(R.id.tvDiscoverSearchEmpty)
        skeletonContainer = view.findViewById(R.id.discoverVehiclesSkeletonContainer)
        idleStateContainer = view.findViewById(R.id.discoverSearchIdleStateContainer)
        idleStateImage = view.findViewById(R.id.discoverSearchIdleStateImage)

        discoverVehiclesAdapter = ProviderVehiclesAdapter(viewLifecycleOwner.lifecycleScope) { card ->
            if (!isAdded) return@ProviderVehiclesAdapter
            startActivity(
                Intent(requireContext(), RenterVehicleDetailsActivity::class.java)
                    .putExtra(RenterVehicleDetailsActivity.EXTRA_VEHICLE_ID, card.vehicleId)
                    .putExtra(RenterVehicleDetailsActivity.EXTRA_PAYLOAD_JSON, card.payloadJson)
            )
        }
        rvResults.layoutManager = LinearLayoutManager(requireContext())
        rvResults.adapter = discoverVehiclesAdapter
        rvResults.isVisible = false
        tvSearchEmpty.isVisible = false
        skeletonContainer.isVisible = false
        idleStateContainer.isVisible = false

        searchInput.doOnTextChanged { text, _, _, _ ->
            handleSearchQueryChanged(text?.toString().orEmpty())
        }
        showIdleState()
    }

    override fun onDestroyView() {
        searchJob?.cancel()
        searchJob = null
        stopSkeletonAnimation()
        stopIdleStateAnimationLoop()
        if (::rvResults.isInitialized) {
            rvResults.adapter = null
        }
        nearbyVehiclesRepository = null
        fusedLocationClient = null
        super.onDestroyView()
    }

    private fun handleSearchQueryChanged(rawInput: String) {
        val query = rawInput.trim()
        latestSearchToken += 1L
        val requestToken = latestSearchToken
        searchJob?.cancel()

        if (query.length < MIN_QUERY_LENGTH) {
            stopSkeletonAnimation()
            skeletonContainer.isVisible = false
            discoverVehiclesAdapter.submitList(emptyList())
            rvResults.isVisible = false
            tvSearchEmpty.isVisible = false
            showIdleState()
            return
        }

        searchJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            runSearch(query = query, requestToken = requestToken)
        }
    }

    private suspend fun runSearch(query: String, requestToken: Long) {
        showLoadingState()

        try {
            val searchCenter = withContext(Dispatchers.IO) {
                resolveSearchCenter()
            }
            val nearbyVehicles = withContext(Dispatchers.IO) {
                loadNearbyVehicles(searchCenter)
            }
            val matchedVehicles = withContext(Dispatchers.Default) {
                filterVehiclesForQuery(nearbyVehicles, query)
            }

            if (!isAdded || view == null) return
            if (requestToken != latestSearchToken) return
            if (query != searchInput.text?.toString()?.trim()) return

            val cards = matchedVehicles.map { it.toProviderCardUi() }
            discoverVehiclesAdapter.submitList(cards)
            showLoadedState(hasResults = cards.isNotEmpty())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            Log.w(TAG, "Discover search failed for query=$query", throwable)
            if (!isAdded || view == null) return
            if (requestToken != latestSearchToken) return
            discoverVehiclesAdapter.submitList(emptyList())
            showErrorState()
        } finally {
            if (requestToken == latestSearchToken && isAdded && view != null) {
                skeletonContainer.isVisible = false
                stopSkeletonAnimation()
            }
        }
    }

    private fun showLoadingState() {
        hideIdleState()
        tvSearchEmpty.isVisible = false
        rvResults.isVisible = false
        skeletonContainer.isVisible = true
        startSkeletonAnimation()
    }

    private fun showLoadedState(hasResults: Boolean) {
        hideIdleState()
        stopSkeletonAnimation()
        skeletonContainer.isVisible = false
        rvResults.isVisible = hasResults
        tvSearchEmpty.isVisible = !hasResults
    }

    private fun showErrorState() {
        hideIdleState()
        stopSkeletonAnimation()
        skeletonContainer.isVisible = false
        rvResults.isVisible = false
        tvSearchEmpty.isVisible = true
    }

    private fun showIdleState() {
        stopSkeletonAnimation()
        skeletonContainer.isVisible = false
        rvResults.isVisible = false
        tvSearchEmpty.isVisible = false
        idleStateContainer.isVisible = true
        startIdleStateAnimationLoop()
    }

    private fun hideIdleState() {
        idleStateContainer.isVisible = false
        stopIdleStateAnimationLoop()
    }

    private fun startIdleStateAnimationLoop() {
        if (!::idleStateContainer.isInitialized) return
        if (!::idleStateImage.isInitialized) return
        if (!idleStateContainer.isVisible) return
        if (idleStateAnimationJob?.isActive == true) return

        idleStateAnimationJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive && idleStateContainer.isVisible) {
                val nextImage = IDLE_STATE_IMAGE_RES_IDS[idleStateImageIndex]
                idleStateImageIndex = (idleStateImageIndex + 1) % IDLE_STATE_IMAGE_RES_IDS.size
                idleStateImage.setImageResource(nextImage)
                runCatching { animateIdleStateImageCycle() }
                    .onFailure {
                        idleStateImage.alpha = 0f
                        idleStateImage.scaleX = 0f
                        idleStateImage.scaleY = 0f
                    }
            }
        }
    }

    private suspend fun animateIdleStateImageCycle() {
        idleStateImage.alpha = 0f
        idleStateImage.scaleX = 0f
        idleStateImage.scaleY = 0f

        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(idleStateImage, View.SCALE_X, 0f, 1f),
                ObjectAnimator.ofFloat(idleStateImage, View.SCALE_Y, 0f, 1f),
                ObjectAnimator.ofFloat(idleStateImage, View.ALPHA, 0f, 1f)
            )
            duration = IDLE_STATE_ENTER_DURATION_MS
            interpolator = OvershootInterpolator(IDLE_STATE_ENTER_OVERSHOOT_TENSION)
        }.startAndAwaitEnd()

        delay(IDLE_STATE_VISIBLE_DURATION_MS)

        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(idleStateImage, View.SCALE_X, 1f, 0f),
                ObjectAnimator.ofFloat(idleStateImage, View.SCALE_Y, 1f, 0f),
                ObjectAnimator.ofFloat(idleStateImage, View.ALPHA, 1f, 0f)
            )
            duration = IDLE_STATE_EXIT_DURATION_MS
            interpolator = AccelerateInterpolator(IDLE_STATE_EXIT_ACCELERATION_FACTOR)
        }.startAndAwaitEnd()
    }

    private fun stopIdleStateAnimationLoop() {
        idleStateAnimationJob?.cancel()
        idleStateAnimationJob = null
        if (!::idleStateImage.isInitialized) return
        idleStateImage.animate().cancel()
        idleStateImage.alpha = 0f
        idleStateImage.scaleX = 0f
        idleStateImage.scaleY = 0f
    }

    private suspend fun Animator.startAndAwaitEnd() {
        suspendCancellableCoroutine<Unit> { continuation ->
            val listener = object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    animation.removeListener(this)
                    if (continuation.isActive) {
                        continuation.resume(Unit)
                    }
                }

                override fun onAnimationCancel(animation: Animator) {
                    animation.removeListener(this)
                    if (continuation.isActive) {
                        continuation.resume(Unit)
                    }
                }
            }
            addListener(listener)
            continuation.invokeOnCancellation {
                runCatching { cancel() }
            }
            start()
        }
    }

    private fun startSkeletonAnimation() {
        if (skeletonAnimators.isNotEmpty()) return

        val blocks = mutableListOf<View>()
        collectSkeletonBlocks(skeletonContainer, blocks)
        blocks.forEachIndexed { index, block ->
            val animator = ObjectAnimator.ofFloat(block, View.ALPHA, 0.45f, 1f).apply {
                duration = 850L
                startDelay = (index % 5) * 90L
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                start()
            }
            skeletonAnimators += animator
        }
    }

    private fun stopSkeletonAnimation() {
        if (skeletonAnimators.isEmpty()) return
        skeletonAnimators.forEach { it.cancel() }
        skeletonAnimators.clear()
        resetSkeletonAlpha(skeletonContainer)
    }

    private fun collectSkeletonBlocks(view: View, out: MutableList<View>) {
        if (view.tag?.toString() == "skeleton_block") {
            out += view
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                collectSkeletonBlocks(view.getChildAt(index), out)
            }
        }
    }

    private fun resetSkeletonAlpha(view: View) {
        view.alpha = 1f
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                resetSkeletonAlpha(view.getChildAt(index))
            }
        }
    }

    private suspend fun resolveSearchCenter(): SearchCenter {
        val currentDeviceLocation = getCurrentDeviceLocation()
        if (currentDeviceLocation != null) {
            saveCachedLocation(currentDeviceLocation)
            return currentDeviceLocation
        }

        val cachedLocation = readCachedLocation()
        val now = System.currentTimeMillis()
        if (cachedLocation != null && (now - cachedLocation.timestampMs) in 0L..LOCATION_CACHE_MAX_AGE_MS) {
            return SearchCenter(cachedLocation.latitude, cachedLocation.longitude)
        }

        return DEFAULT_LOCATION
    }

    private suspend fun getCurrentDeviceLocation(): SearchCenter? {
        if (!hasLocationPermission()) return null

        val client = fusedLocationClient ?: return null

        val lastKnown = runCatching { client.lastLocation.await() }
            .getOrNull()
        if (lastKnown != null) {
            return SearchCenter(lastKnown.latitude, lastKnown.longitude)
        }

        val priority = if (hasFineLocationPermission()) {
            Priority.PRIORITY_HIGH_ACCURACY
        } else {
            Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }
        val tokenSource = CancellationTokenSource()
        val freshLocation = runCatching {
            client.getCurrentLocation(priority, tokenSource.token).await()
        }.getOrNull()

        return freshLocation?.let { SearchCenter(it.latitude, it.longitude) }
    }

    private suspend fun loadNearbyVehicles(
        searchCenter: SearchCenter
    ): List<NearbyVehiclesRepository.NearbyVehicle> {
        val repository = nearbyVehiclesRepository ?: return emptyList()
        val now = System.currentTimeMillis()
        val cacheAgeMs = now - cachedNearbyVehiclesAtMs
        val cachedCenter = cachedSearchCenter
        val canUseCache = cachedCenter != null &&
            cacheAgeMs in 0L..SEARCH_CACHE_TTL_MS &&
            distanceBetweenCentersMeters(cachedCenter, searchCenter) <= SEARCH_CACHE_CENTER_DELTA_METERS
        if (canUseCache) {
            return cachedNearbyVehicles
        }

        val primary = repository.getNearbyPublishedVehicles(
            centerLatitude = searchCenter.latitude,
            centerLongitude = searchCenter.longitude,
            radiusMeters = SEARCH_RADIUS_METERS
        )
        val vehicles = if (primary.isNotEmpty()) {
            primary
        } else {
            repository.getNearbyPublishedVehicles(
                centerLatitude = searchCenter.latitude,
                centerLongitude = searchCenter.longitude,
                radiusMeters = SEARCH_FALLBACK_RADIUS_METERS
            )
        }

        cachedSearchCenter = searchCenter
        cachedNearbyVehicles = vehicles
        cachedNearbyVehiclesAtMs = now
        return vehicles
    }

    private fun filterVehiclesForQuery(
        vehicles: List<NearbyVehiclesRepository.NearbyVehicle>,
        rawQuery: String
    ): List<NearbyVehiclesRepository.NearbyVehicle> {
        val normalizedQuery = normalizeForSearch(rawQuery)
        if (normalizedQuery.length < MIN_QUERY_LENGTH) return emptyList()

        val queryTokens = tokenizeForSearch(normalizedQuery)
            .filterNot { it in SEARCH_STOP_WORDS }
            .distinct()
        if (queryTokens.isEmpty()) return emptyList()

        val queryTokenVariants = queryTokens
            .flatMap { token -> semanticVariantsForToken(token) }
            .toSet()

        val requiresInterurbanTripCapability = queryTokenVariants.any { it in INTERURBAN_TRIP_HINT_TOKENS }
        val requiresCarType = queryTokenVariants.any { it in CAR_INTENT_TOKENS }
        val requiresMotorcycleType = queryTokenVariants.any { it in MOTORCYCLE_INTENT_TOKENS }
        val requiredVehicleType = when {
            requiresCarType && !requiresMotorcycleType -> VEHICLE_TYPE_CAR
            requiresMotorcycleType && !requiresCarType -> VEHICLE_TYPE_MOTORCYCLE
            else -> null
        }

        return vehicles
            .mapNotNull { vehicle ->
                val payload = runCatching { JSONObject(vehicle.payloadJson) }.getOrNull()
                if (requiredVehicleType != null &&
                    resolveVehicleTypeForSearch(vehicle, payload) != requiredVehicleType
                ) {
                    return@mapNotNull null
                }
                if (requiresInterurbanTripCapability && !vehicleSupportsInterurbanTrip(vehicle, payload)) {
                    return@mapNotNull null
                }

                val searchCorpus = buildVehicleSearchCorpus(vehicle, payload)
                val score = scoreVehicleMatch(
                    corpus = searchCorpus,
                    normalizedQuery = normalizedQuery,
                    queryTokens = queryTokens
                ) ?: return@mapNotNull null
                MatchedVehicle(vehicle, score)
            }
            .sortedWith(
                compareByDescending<MatchedVehicle> { it.score }
                    .thenBy { it.vehicle.distanceMeters }
            )
            .map { it.vehicle }
    }

    private fun scoreVehicleMatch(
        corpus: VehicleSearchCorpus,
        normalizedQuery: String,
        queryTokens: List<String>
    ): Int? {
        if (corpus.normalizedTokens.isEmpty()) return null

        var score = 0
        for (token in queryTokens) {
            val variants = semanticVariantsForToken(token)
            if (variants.isEmpty()) return null

            val matched = when {
                variants.any { it in corpus.normalizedTokens } -> {
                    score += 48
                    true
                }

                variants.any { variant ->
                    corpus.normalizedTokens.any { indexed ->
                        indexed.startsWith(variant) || variant.startsWith(indexed)
                    }
                } -> {
                    score += 30
                    true
                }

                variants.any { variant -> corpus.normalizedText.contains(variant) } -> {
                    score += 18
                    true
                }

                else -> false
            }

            if (!matched) return null
        }

        val compactQuery = queryTokens.joinToString(" ")
        if (compactQuery.isNotBlank() && corpus.normalizedText.contains(compactQuery)) {
            score += 24
        } else if (corpus.normalizedText.contains(normalizedQuery)) {
            score += 18
        }

        val firstToken = queryTokens.firstOrNull().orEmpty()
        if (firstToken.isNotEmpty() && corpus.normalizedTokens.any { it.startsWith(firstToken) }) {
            score += 8
        }

        return score
    }

    private fun buildVehicleSearchCorpus(
        vehicle: NearbyVehiclesRepository.NearbyVehicle,
        payload: JSONObject?
    ): VehicleSearchCorpus {
        val normalizedPhrases = linkedSetOf<String>()
        val normalizedTokens = linkedSetOf<String>()

        fun addText(rawValue: String?) {
            val normalized = normalizeForSearch(rawValue.orEmpty())
            if (normalized.isBlank()) return

            normalizedPhrases += normalized
            tokenizeForSearch(normalized)
                .filter { it.length >= MIN_INDEXED_TOKEN_LENGTH }
                .filterNot { it in SEARCH_STOP_WORDS }
                .forEach { token ->
                    normalizedTokens += semanticVariantsForToken(token)
                }
        }

        fun addTexts(values: Iterable<String>) {
            values.forEach(::addText)
        }

        addText(vehicle.brand)
        addText(vehicle.model)
        addText(vehicle.manufactureYear)
        addText(vehicle.modelYear)
        addText(vehicle.color)
        addText(vehicle.bodyType)
        addText(vehicle.dailyPrice)
        addText(vehicle.condition)
        addText(vehicle.plate)
        addText(vehicle.vehicleType)
        addTexts(GENERIC_VEHICLE_KEYWORDS)
        addText(vehicle.vehicleId)
        addTexts(vehicle.highlightTags)
        addTexts(vehicle.allowedTripTypes)
        addTexts(conditionSynonyms(vehicle.condition.orEmpty()))
        if (vehicle.allowTrip == true) {
            addTexts(TRIP_ALLOWED_KEYWORDS)
        }

        payload?.let { data ->
            PAYLOAD_STRING_FIELDS.forEach { key ->
                addText(data.optStringOrNull(key))
            }
            PAYLOAD_LIST_FIELDS.forEach { key ->
                addTexts(data.optStringList(key))
            }
            addTexts(conditionSynonyms(data.optStringOrNull("condition").orEmpty()))

            if (data.optBooleanOrNull("allowTrip") == true) {
                addTexts(TRIP_ALLOWED_KEYWORDS)
            }
            if (data.optBooleanOrNull("deliveryByFee") == true) {
                addTexts(DELIVERY_KEYWORDS)
            }
            if (data.optBooleanOrNull("pickupOnLocation") == true) {
                addTexts(PICKUP_LOCATION_KEYWORDS)
            }
            if (data.optBooleanOrNull("hasInsurance") == true) {
                addTexts(INSURANCE_KEYWORDS)
            }
            if (data.optBooleanOrNull("documentsUpToDate") == true) {
                addTexts(DOCUMENTS_OK_KEYWORDS)
            }
            if (data.optBooleanOrNull("ipvaLicensingOk") == true) {
                addTexts(IPVA_OK_KEYWORDS)
            }

            val keys = data.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key in PAYLOAD_EXCLUDED_KEYS) continue
                val value = data.opt(key)
                when (value) {
                    is String -> addText(value)
                    is JSONArray -> {
                        for (index in 0 until value.length()) {
                            val item = value.opt(index)
                            when (item) {
                                is String -> addText(item)
                                is Number -> if (key in PAYLOAD_SEARCHABLE_NUMERIC_FIELDS) {
                                    addText(item.toString())
                                }
                            }
                        }
                    }

                    is Number -> if (key in PAYLOAD_SEARCHABLE_NUMERIC_FIELDS) {
                        addText(value.toString())
                    }
                }
            }
        }

        return VehicleSearchCorpus(
            normalizedTokens = normalizedTokens,
            normalizedText = normalizedPhrases.joinToString(" ")
        )
    }

    private fun resolveVehicleTypeForSearch(
        vehicle: NearbyVehiclesRepository.NearbyVehicle,
        payload: JSONObject?
    ): String? {
        return normalizeVehicleTypeForCard(vehicle.vehicleType)
            ?: normalizeVehicleTypeForCard(payload?.optString("vehicleType"))
    }

    private fun vehicleSupportsInterurbanTrip(
        vehicle: NearbyVehiclesRepository.NearbyVehicle,
        payload: JSONObject?
    ): Boolean {
        val allowTrip = vehicle.allowTrip ?: payload?.optBooleanOrNull("allowTrip")
        if (allowTrip == true) return true
        if (allowTrip == false) return false

        val tripTypes = buildList {
            addAll(vehicle.allowedTripTypes)
            addAll(payload?.optStringList("allowedTripTypes").orEmpty())
        }
        if (tripTypes.isEmpty()) return false

        val normalizedTripTokens = tripTypes
            .flatMap { tokenizeForSearch(normalizeForSearch(it)) }
            .flatMap { semanticVariantsForToken(it) }
            .toSet()
        return normalizedTripTokens.any { it in INTERURBAN_TRIP_HINT_TOKENS }
    }

    private fun semanticVariantsForToken(rawToken: String): Set<String> {
        val normalizedToken = normalizeForSearch(rawToken)
        if (normalizedToken.isBlank()) return emptySet()

        val variants = linkedSetOf<String>()
        variants += normalizedToken
        variants += buildSimpleTokenVariants(normalizedToken)

        TOKEN_EQUIVALENCE_GROUPS
            .firstOrNull { group -> group.any { it in variants } }
            ?.let { group ->
                variants += group
            }

        variants.toList().forEach { variant ->
            variants += buildSimpleTokenVariants(variant)
        }

        return variants
            .filter { it.length >= MIN_INDEXED_TOKEN_LENGTH }
            .toSet()
    }

    private fun buildSimpleTokenVariants(token: String): Set<String> {
        if (token.length < MIN_INDEXED_TOKEN_LENGTH) return emptySet()

        val variants = linkedSetOf<String>()
        if (token.length >= 3) {
            if (token.endsWith("s") && token.length > 3) {
                variants += token.dropLast(1)
            } else {
                variants += "${token}s"
            }
        }

        if (token.length >= 4) {
            when {
                token.endsWith("o") -> variants += token.dropLast(1) + "a"
                token.endsWith("a") -> variants += token.dropLast(1) + "o"
            }
        }
        return variants
    }

    private fun conditionSynonyms(rawCondition: String): List<String> {
        return when (normalizeForSearch(rawCondition)) {
            "excellent" -> listOf("excellent", "excelente", "otimo", "otima")
            "good" -> listOf("good", "bom", "boa")
            "fair" -> listOf("fair", "razoavel", "regular")
            "poor" -> listOf("poor", "ruim")
            else -> emptyList()
        }
    }

    private fun tokenizeForSearch(normalizedInput: String): List<String> {
        return normalizedInput
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return opt(key)
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun JSONObject.optStringList(key: String): List<String> {
        if (!has(key) || isNull(key)) return emptyList()
        return when (val raw = opt(key)) {
            is JSONArray -> {
                buildList {
                    for (index in 0 until raw.length()) {
                        raw.opt(index)
                            ?.toString()
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
                            ?.let(::add)
                    }
                }
            }

            is String -> raw.split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() }
            else -> emptyList()
        }
    }

    private fun JSONObject.optBooleanOrNull(key: String): Boolean? {
        if (!has(key) || isNull(key)) return null
        return when (val raw = opt(key)) {
            is Boolean -> raw
            is Number -> raw.toInt() != 0
            is String -> when (normalizeForSearch(raw)) {
                "true", "1", "sim", "yes" -> true
                "false", "0", "nao", "no" -> false
                else -> null
            }
            else -> null
        }
    }

    private fun NearbyVehiclesRepository.NearbyVehicle.toProviderCardUi(): ProviderVehicleCardUi {
        val payload = runCatching { JSONObject(payloadJson) }.getOrNull()

        val resolvedBrand = brand.orEmpty().trim().ifBlank {
            payload?.optString("brand").orEmpty().trim()
        }
        val resolvedModel = model.orEmpty().trim().ifBlank {
            payload?.optString("model").orEmpty().trim()
        }
        val resolvedManufactureYear = manufactureYear.orEmpty().trim().ifBlank {
            payload?.optString("manufactureYear").orEmpty().trim()
        }
        val resolvedModelYear = modelYear.orEmpty().trim().ifBlank {
            payload?.optString("modelYear").orEmpty().trim()
        }
        val resolvedColor = color.orEmpty().trim().ifBlank {
            payload?.optString("color").orEmpty().trim()
        }
        val resolvedVehicleType = normalizeVehicleTypeForCard(vehicleType)
            ?: normalizeVehicleTypeForCard(payload?.optString("vehicleType"))
            ?: VEHICLE_TYPE_CAR
        val bodyTypeValue = bodyType
            ?: payload?.optString("bodyType").orEmpty().trim().ifBlank { null }
        val dailyPriceValue = dailyPrice.orEmpty().trim()
            .ifBlank { payload?.optString("dailyPrice").orEmpty().trim() }

        val yearLabel = when {
            resolvedManufactureYear.isNotBlank() && resolvedModelYear.isNotBlank() ->
                "$resolvedManufactureYear/$resolvedModelYear"

            resolvedManufactureYear.isNotBlank() -> resolvedManufactureYear
            resolvedModelYear.isNotBlank() -> resolvedModelYear
            else -> ""
        }

        val title = listOf(resolvedBrand, resolvedModel, yearLabel)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { getString(R.string.renter_home_nearby_unknown_vehicle) }

        val colorLabel = getString(
            R.string.renter_home_nearby_color_line,
            resolvedColor.ifBlank { "-" }
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
            sideTagLabel = if (dailyPriceValue.isNotBlank()) {
                getString(R.string.renter_home_nearby_daily_price, dailyPriceValue)
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

    private fun normalizeForSearch(input: String): String {
        val withoutAccent = Normalizer.normalize(input, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        return withoutAccent
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun distanceBetweenCentersMeters(from: SearchCenter, to: SearchCenter): Float {
        val distance = FloatArray(1)
        Location.distanceBetween(
            from.latitude,
            from.longitude,
            to.latitude,
            to.longitude,
            distance
        )
        return distance[0]
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

    private fun hasFineLocationPermission(): Boolean {
        val context = context ?: return false
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun saveCachedLocation(center: SearchCenter) {
        val prefs = context?.getSharedPreferences(LOCATION_CACHE_PREFS, 0) ?: return
        prefs.edit()
            .putLong(KEY_CACHE_TIMESTAMP_MS, System.currentTimeMillis())
            .putString(KEY_CACHE_LATITUDE, center.latitude.toString())
            .putString(KEY_CACHE_LONGITUDE, center.longitude.toString())
            .apply()
    }

    private fun readCachedLocation(): CachedLocation? {
        val prefs = context?.getSharedPreferences(LOCATION_CACHE_PREFS, 0) ?: return null
        if (!prefs.contains(KEY_CACHE_TIMESTAMP_MS)) return null

        val latitude = prefs.getString(KEY_CACHE_LATITUDE, null)?.toDoubleOrNull() ?: return null
        val longitude = prefs.getString(KEY_CACHE_LONGITUDE, null)?.toDoubleOrNull() ?: return null
        val timestamp = prefs.getLong(KEY_CACHE_TIMESTAMP_MS, 0L)
        if (timestamp <= 0L) return null

        return CachedLocation(
            latitude = latitude,
            longitude = longitude,
            timestampMs = timestamp
        )
    }

    private suspend fun <T> Task<T>.await(): T? {
        return suspendCancellableCoroutine { continuation ->
            addOnSuccessListener { result ->
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }
            addOnFailureListener { throwable ->
                if (continuation.isActive) {
                    continuation.resumeWithException(throwable)
                }
            }
            addOnCanceledListener {
                if (continuation.isActive) {
                    continuation.cancel()
                }
            }
        }
    }

    private data class SearchCenter(
        val latitude: Double,
        val longitude: Double
    )

    private data class CachedLocation(
        val latitude: Double,
        val longitude: Double,
        val timestampMs: Long
    )

    private data class MatchedVehicle(
        val vehicle: NearbyVehiclesRepository.NearbyVehicle,
        val score: Int
    )

    private data class VehicleSearchCorpus(
        val normalizedTokens: Set<String>,
        val normalizedText: String
    )

    companion object {
        private const val MIN_QUERY_LENGTH = 2
        private const val MIN_INDEXED_TOKEN_LENGTH = 2
        private const val SEARCH_DEBOUNCE_MS = 300L
        private const val IDLE_STATE_ENTER_DURATION_MS = 220L
        private const val IDLE_STATE_VISIBLE_DURATION_MS = 1560L
        private const val IDLE_STATE_EXIT_DURATION_MS = 220L
        private const val IDLE_STATE_ENTER_OVERSHOOT_TENSION = 3.0f
        private const val IDLE_STATE_EXIT_ACCELERATION_FACTOR = 1.9f
        private const val SEARCH_RADIUS_METERS = 120_000.0
        private const val SEARCH_FALLBACK_RADIUS_METERS = 600_000.0
        private const val SEARCH_CACHE_TTL_MS = 8L * 60L * 1000L
        private const val SEARCH_CACHE_CENTER_DELTA_METERS = 1500f
        private const val LOCATION_CACHE_PREFS = "renter_map_cache"
        private const val KEY_CACHE_LATITUDE = "cache_latitude"
        private const val KEY_CACHE_LONGITUDE = "cache_longitude"
        private const val KEY_CACHE_TIMESTAMP_MS = "cache_timestamp_ms"
        private const val LOCATION_CACHE_MAX_AGE_MS = 5L * 24L * 60L * 60L * 1000L
        private const val VEHICLE_TYPE_CAR = "car"
        private const val VEHICLE_TYPE_MOTORCYCLE = "motorcycle"
        private val DEFAULT_LOCATION = SearchCenter(-23.55052, -46.63331)
        private val IDLE_STATE_IMAGE_RES_IDS = intArrayOf(
            R.drawable.motorcycle,
            R.drawable.car_suv
        )
        private val SEARCH_STOP_WORDS = setOf(
            "a", "o", "as", "os",
            "de", "do", "da", "dos", "das",
            "e", "ou", "em", "no", "na", "nos", "nas",
            "para", "pra", "por", "com", "sem",
            "um", "uma", "uns", "umas"
        )
        private val TOKEN_EQUIVALENCE_GROUPS = listOf(
            setOf("car", "carro", "automovel"),
            setOf("motorcycle", "moto", "motocicleta", "scooter"),
            setOf("veiculo", "veiculos", "vehicle", "vehicles"),
            setOf("combustivel", "fuel"),
            setOf("flex", "bicombustivel"),
            setOf("etanol", "alcool", "alcoolica"),
            setOf("gasolina", "gasoline"),
            setOf("hibrido", "hibrida", "hybrid"),
            setOf("eletrico", "eletrica", "electric"),
            setOf("suv", "utilitario", "crossover"),
            setOf("hatch", "hatchback"),
            setOf("sedan", "seda"),
            setOf("pickup", "picape", "caminhonete"),
            setOf("automatico", "automatica", "automatic", "cvt"),
            setOf("manual"),
            setOf("interurbana", "interurbano", "intermunicipal", "interestadual", "rodovia", "estrada"),
            setOf("viagem", "viajar", "viagens"),
            setOf("urbano", "urbana"),
            setOf("economico", "economica", "economia"),
            setOf("familia", "familiar"),
            setOf("vermelho", "vermelha"),
            setOf("branco", "branca"),
            setOf("preto", "preta"),
            setOf("amarelo", "amarela"),
            setOf("prata", "prateado", "prateada"),
            setOf("azul"),
            setOf("verde"),
            setOf("cinza", "grafite"),
            setOf("marrom", "castanho", "castanha"),
            setOf("bege"),
            setOf("laranja")
        )
        private val CAR_INTENT_TOKENS = setOf("car", "carro", "automovel")
        private val MOTORCYCLE_INTENT_TOKENS = setOf("motorcycle", "moto", "motocicleta", "scooter")
        private val GENERIC_VEHICLE_KEYWORDS = listOf("veiculo", "veiculos", "vehicle", "vehicles")
        private val INTERURBAN_TRIP_HINT_TOKENS = setOf(
            "interurbana",
            "interurbano",
            "intermunicipal",
            "interestadual",
            "estrada",
            "rodovia"
        )
        private val TRIP_ALLOWED_KEYWORDS = listOf(
            "viagem",
            "viajar",
            "interurbana",
            "interurbano",
            "intermunicipal",
            "interestadual",
            "rodovia",
            "estrada"
        )
        private val DELIVERY_KEYWORDS = listOf("entrega", "delivery", "taxa", "domicilio")
        private val PICKUP_LOCATION_KEYWORDS = listOf("retirada", "local", "buscar")
        private val INSURANCE_KEYWORDS = listOf("seguro", "protecao")
        private val DOCUMENTS_OK_KEYWORDS = listOf("documentos", "regular", "em dia")
        private val IPVA_OK_KEYWORDS = listOf("ipva", "licenciamento", "regularizado")
        private val PAYLOAD_STRING_FIELDS = setOf(
            "brand",
            "model",
            "manufactureYear",
            "modelYear",
            "color",
            "bodyType",
            "fuelType",
            "transmissionType",
            "condition",
            "dailyPrice",
            "cityState",
            "neighborhood",
            "seats",
            "mileage",
            "vehicleType",
            "status",
            "deliveryFee",
            "deliveryRadiusKm"
        )
        private val PAYLOAD_LIST_FIELDS = setOf(
            "highlightTags",
            "comfortItems",
            "safetyItems",
            "allowedTripTypes"
        )
        private val PAYLOAD_EXCLUDED_KEYS = setOf(
            "uploadedPhotoUrls",
            "weeklySchedule",
            "pickupGeohash",
            "pickupLatitude",
            "pickupLongitude",
            "updatedAtClient",
            "updatedAtServer"
        )
        private val PAYLOAD_SEARCHABLE_NUMERIC_FIELDS = setOf(
            "manufactureYear",
            "modelYear",
            "deliveryRadiusKm",
            "seats",
            "mileage"
        )
        private const val TAG = "RenterDiscoverFragment"
    }
}
