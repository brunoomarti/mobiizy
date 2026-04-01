package com.brunocodex.kotlinproject.fragments

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.brunocodex.kotlinproject.R
import com.brunocodex.kotlinproject.activities.ProviderVehicleDetailsActivity
import com.brunocodex.kotlinproject.activities.ProviderVehicleRegisterActivity
import com.brunocodex.kotlinproject.adapters.ProviderVehicleCardUi
import com.brunocodex.kotlinproject.adapters.ProviderVehiclesAdapter
import com.brunocodex.kotlinproject.services.SQLiteConfiguration
import com.brunocodex.kotlinproject.services.VehicleSyncRepository
import com.brunocodex.kotlinproject.utils.LocalVehicleDraftStore
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class ProviderVehiclesFragment : Fragment(R.layout.fragment_provider_vehicles) {

    private val vehicleSyncRepository by lazy { VehicleSyncRepository(requireContext().applicationContext) }
    private val firebaseAuth by lazy { FirebaseAuth.getInstance() }
    private lateinit var inProgressAdapter: ProviderVehiclesAdapter
    private lateinit var registeredAdapter: ProviderVehiclesAdapter
    private lateinit var scrollVehiclesSections: View
    private lateinit var rvVehiclesInProgress: RecyclerView
    private lateinit var rvVehiclesRegistered: RecyclerView
    private lateinit var tvVehiclesEmpty: TextView
    private lateinit var tvInProgressListTitle: TextView
    private lateinit var tvInProgressEmpty: TextView
    private lateinit var tvRegisteredEmpty: TextView
    private lateinit var skeletonContainer: ViewGroup
    private val skeletonAnimators = mutableListOf<ObjectAnimator>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        scrollVehiclesSections = view.findViewById(R.id.scrollVehiclesSections)
        rvVehiclesInProgress = view.findViewById(R.id.rvVehiclesInProgress)
        rvVehiclesRegistered = view.findViewById(R.id.rvVehiclesRegistered)
        tvVehiclesEmpty = view.findViewById(R.id.tvVehiclesEmpty)
        tvInProgressListTitle = view.findViewById(R.id.tvInProgressListTitle)
        tvInProgressEmpty = view.findViewById(R.id.tvInProgressEmpty)
        tvRegisteredEmpty = view.findViewById(R.id.tvRegisteredEmpty)
        skeletonContainer = view.findViewById(R.id.skeletonContainer)

        inProgressAdapter = ProviderVehiclesAdapter(viewLifecycleOwner.lifecycleScope) { item ->
            openVehicle(item)
        }
        registeredAdapter = ProviderVehiclesAdapter(viewLifecycleOwner.lifecycleScope) { item ->
            openVehicle(item)
        }
        rvVehiclesInProgress.layoutManager = LinearLayoutManager(requireContext())
        rvVehiclesInProgress.adapter = inProgressAdapter
        rvVehiclesInProgress.isNestedScrollingEnabled = false

        rvVehiclesRegistered.layoutManager = LinearLayoutManager(requireContext())
        rvVehiclesRegistered.adapter = registeredAdapter
        rvVehiclesRegistered.isNestedScrollingEnabled = false

        view.findViewById<View>(R.id.btnAddVehicle).setOnClickListener {
            startActivity(Intent(requireContext(), ProviderVehicleRegisterActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        loadVehicles()
    }

    override fun onDestroyView() {
        stopSkeletonAnimation()
        super.onDestroyView()
    }

    private fun loadVehicles() {
        val ownerId = firebaseAuth.currentUser?.uid.orEmpty().ifBlank { "anonymous" }
        showLoadingState()

        viewLifecycleOwner.lifecycleScope.launch {
            val rows = runCatching { vehicleSyncRepository.getVehiclesOnlineFirst(ownerId) }
                .getOrElse { emptyList() }

            val persistedCards = rows.map { row -> row.toCardUi() }
            val inProgressCards = persistedCards
                .filter { it.status == SQLiteConfiguration.STATUS_DRAFT }
                .toMutableList()
            val registeredCards = persistedCards
                .filter { it.status != SQLiteConfiguration.STATUS_DRAFT }
                .sortedByDescending { it.updatedAtTimestamp }

            val localDraftCards = readLocalInProgressDraftCards()
            val mergedInProgress = mergeInProgressCards(
                persistedDrafts = inProgressCards,
                localDrafts = localDraftCards
            )
                .sortedByDescending { it.updatedAtTimestamp }

            inProgressAdapter.submitList(mergedInProgress)
            registeredAdapter.submitList(registeredCards)
            showLoadedState(
                inProgressCount = mergedInProgress.size,
                registeredCount = registeredCards.size
            )
        }
    }

    private fun showLoadingState() {
        tvVehiclesEmpty.isVisible = false
        scrollVehiclesSections.isVisible = false
        skeletonContainer.isVisible = true
        startSkeletonAnimation()
    }

    private fun showLoadedState(inProgressCount: Int, registeredCount: Int) {
        val isEmpty = inProgressCount == 0 && registeredCount == 0
        val hasInProgress = inProgressCount > 0
        stopSkeletonAnimation()
        skeletonContainer.isVisible = false
        tvVehiclesEmpty.isVisible = isEmpty
        scrollVehiclesSections.isVisible = !isEmpty
        tvInProgressListTitle.isVisible = hasInProgress
        tvInProgressEmpty.isVisible = false
        rvVehiclesInProgress.isVisible = hasInProgress
        rvVehiclesRegistered.isVisible = registeredCount > 0
        tvRegisteredEmpty.isVisible = !isEmpty && registeredCount == 0
    }

    private fun mergeInProgressCards(
        persistedDrafts: List<ProviderVehicleCardUi>,
        localDrafts: List<ProviderVehicleCardUi>
    ): List<ProviderVehicleCardUi> {
        val merged = persistedDrafts.toMutableList()

        localDrafts.forEach { local ->
            val localPlate = local.plate.trim()
            if (localPlate.isBlank()) {
                merged += local
                return@forEach
            }

            val samePlateIndex = merged.indexOfFirst { persisted ->
                persisted.plate.trim().equals(localPlate, ignoreCase = true)
            }

            if (samePlateIndex == -1) {
                merged += local
                return@forEach
            }

            if (local.updatedAtTimestamp >= merged[samePlateIndex].updatedAtTimestamp) {
                merged[samePlateIndex] = local
            }
        }

        return merged
    }

    private fun openVehicle(item: ProviderVehicleCardUi) {
        if (item.status == SQLiteConfiguration.STATUS_DRAFT) {
            val intent = Intent(requireContext(), ProviderVehicleRegisterActivity::class.java)
                .putExtra(ProviderVehicleRegisterActivity.EXTRA_INITIAL_DRAFT_JSON, item.payloadJson)
                .putExtra(
                    ProviderVehicleRegisterActivity.EXTRA_INITIAL_LOCAL_DRAFT_ID,
                    item.localDraftId
                )
            startActivity(intent)
            return
        }

        val intent = Intent(requireContext(), ProviderVehicleDetailsActivity::class.java)
            .putExtra(ProviderVehicleDetailsActivity.EXTRA_VEHICLE_ID, item.vehicleId)
            .putExtra(ProviderVehicleDetailsActivity.EXTRA_VEHICLE_STATUS, item.status)
            .putExtra(ProviderVehicleDetailsActivity.EXTRA_PAYLOAD_JSON, item.payloadJson)
        startActivity(intent)
    }

    private fun SQLiteConfiguration.VehicleRow.toCardUi(): ProviderVehicleCardUi {
        val payload = runCatching { JSONObject(payloadJson) }.getOrNull()

        val brand = payload?.optString("brand").orEmpty().trim()
        val model = payload?.optString("model").orEmpty().trim()
        val modelYear = payload?.optString("modelYear").orEmpty().trim()
        val vehicleType = payload?.optString("vehicleType").orEmpty().trim().ifBlank { null }
        val bodyType = payload?.optString("bodyType").orEmpty().trim().ifBlank { null }

        val displayTitle = listOf(brand, model).filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { getString(R.string.provider_vehicles_unknown_vehicle) }
            .let { base ->
                if (modelYear.isBlank()) base else "$base $modelYear"
            }

        val statusIsDraft = status == SQLiteConfiguration.STATUS_DRAFT
        val statusLabel = if (statusIsDraft) {
            getString(R.string.provider_vehicles_status_draft)
        } else {
            getString(R.string.provider_vehicles_status_published)
        }

        val updatedAtLabel = getString(
            R.string.provider_vehicles_updated_at,
            formatDateTime(updatedAt)
        )

        return ProviderVehicleCardUi(
            vehicleId = vehicleId,
            payloadJson = payloadJson,
            title = displayTitle,
            plate = plate,
            plateLabel = getString(R.string.provider_vehicles_plate, plate),
            updatedAtTimestamp = updatedAt,
            updatedAtLabel = updatedAtLabel,
            statusLabel = statusLabel,
            status = status,
            hasPendingSync = syncState == SQLiteConfiguration.SYNC_STATE_PENDING,
            pendingSyncLabel = getString(R.string.provider_vehicles_sync_pending),
            vehicleType = vehicleType,
            bodyType = bodyType
        )
    }

    private fun readLocalInProgressDraftCards(): List<ProviderVehicleCardUi> {
        val context = context ?: return emptyList()
        return LocalVehicleDraftStore.readAll(context)
            .mapNotNull { entry ->
                val payload = runCatching { JSONObject(entry.payloadJson) }.getOrNull() ?: return@mapNotNull null
                if (!hasMeaningfulLocalDraft(payload)) return@mapNotNull null

                val brand = payload.optString("brand").orEmpty().trim()
                val model = payload.optString("model").orEmpty().trim()
                val modelYear = payload.optString("modelYear").orEmpty().trim()
                val vehicleType = payload.optString("vehicleType").orEmpty().trim().ifBlank { null }
                val bodyType = payload.optString("bodyType").orEmpty().trim().ifBlank { null }
                val plate = payload.optString("plate").orEmpty().trim().uppercase(Locale.getDefault())

                val displayTitle = listOf(brand, model).filter { it.isNotBlank() }
                    .joinToString(" ")
                    .ifBlank { getString(R.string.provider_vehicles_in_progress_fallback_title) }
                    .let { base -> if (modelYear.isBlank()) base else "$base $modelYear" }

                ProviderVehicleCardUi(
                    vehicleId = entry.id,
                    payloadJson = entry.payloadJson,
                    title = displayTitle,
                    plate = plate,
                    plateLabel = getString(
                        R.string.provider_vehicles_plate,
                        plate.ifBlank { "-" }
                    ),
                    updatedAtTimestamp = entry.updatedAt,
                    updatedAtLabel = getString(
                        R.string.provider_vehicles_updated_at,
                        formatDateTime(entry.updatedAt)
                    ),
                    statusLabel = getString(R.string.provider_vehicles_status_draft),
                    status = SQLiteConfiguration.STATUS_DRAFT,
                    hasPendingSync = false,
                    pendingSyncLabel = getString(R.string.provider_vehicles_sync_pending),
                    vehicleType = vehicleType,
                    bodyType = bodyType,
                    localDraftId = entry.id
                )
            }
    }

    private fun hasMeaningfulLocalDraft(payload: JSONObject): Boolean {
        val hasRequiredLikeFields = listOf(
            "brand",
            "model",
            "plate",
            "cityState",
            "pickupStreet",
            "dailyPrice"
        ).any { key ->
            payload.optString(key).orEmpty().trim().isNotBlank()
        }

        val hasHighlights = payload.optJSONArray("highlightTags")?.length()?.let { it > 0 } ?: false
        val uploadedPhotos = payload.optJSONObject("uploadedPhotoUrls")
        val hasUploadedPhotos = uploadedPhotos
            ?.keys()
            ?.asSequence()
            ?.any { key -> uploadedPhotos.optString(key).orEmpty().isNotBlank() }
            ?: false
        val progressedStep = payload.optInt("currentStep", 0) > 0

        return hasRequiredLikeFields || hasHighlights || hasUploadedPhotos || progressedStep
    }

    private fun formatDateTime(timestamp: Long): String {
        val formatter = DateFormat.getDateTimeInstance(
            DateFormat.SHORT,
            DateFormat.SHORT,
            Locale.getDefault()
        )
        return formatter.format(Date(timestamp))
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
            for (i in 0 until view.childCount) {
                collectSkeletonBlocks(view.getChildAt(i), out)
            }
        }
    }

    private fun resetSkeletonAlpha(view: View) {
        view.alpha = 1f
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                resetSkeletonAlpha(view.getChildAt(i))
            }
        }
    }
}
