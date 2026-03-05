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
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class ProviderVehiclesFragment : Fragment(R.layout.fragment_provider_vehicles) {

    private val vehicleSyncRepository by lazy { VehicleSyncRepository(requireContext().applicationContext) }
    private val firebaseAuth by lazy { FirebaseAuth.getInstance() }
    private lateinit var vehiclesAdapter: ProviderVehiclesAdapter
    private lateinit var rvVehicles: RecyclerView
    private lateinit var tvVehiclesEmpty: TextView
    private lateinit var skeletonContainer: ViewGroup
    private val skeletonAnimators = mutableListOf<ObjectAnimator>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rvVehicles = view.findViewById(R.id.rvVehicles)
        tvVehiclesEmpty = view.findViewById(R.id.tvVehiclesEmpty)
        skeletonContainer = view.findViewById(R.id.skeletonContainer)

        vehiclesAdapter = ProviderVehiclesAdapter { item ->
            openVehicle(item)
        }
        rvVehicles.layoutManager = LinearLayoutManager(requireContext())
        rvVehicles.adapter = vehiclesAdapter

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

            val cards = rows.map { row ->
                row.toCardUi()
            }

            vehiclesAdapter.submitList(cards)
            showLoadedState(isEmpty = cards.isEmpty())
        }
    }

    private fun showLoadingState() {
        tvVehiclesEmpty.isVisible = false
        rvVehicles.isVisible = false
        skeletonContainer.isVisible = true
        startSkeletonAnimation()
    }

    private fun showLoadedState(isEmpty: Boolean) {
        stopSkeletonAnimation()
        skeletonContainer.isVisible = false
        tvVehiclesEmpty.isVisible = isEmpty
        rvVehicles.isVisible = !isEmpty
    }

    private fun openVehicle(item: ProviderVehicleCardUi) {
        if (item.status == SQLiteConfiguration.STATUS_DRAFT) {
            val intent = Intent(requireContext(), ProviderVehicleRegisterActivity::class.java)
                .putExtra(ProviderVehicleRegisterActivity.EXTRA_INITIAL_DRAFT_JSON, item.payloadJson)
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
            plateLabel = getString(R.string.provider_vehicles_plate, plate),
            updatedAtLabel = updatedAtLabel,
            statusLabel = statusLabel,
            status = status,
            hasPendingSync = syncState == SQLiteConfiguration.SYNC_STATE_PENDING,
            pendingSyncLabel = getString(R.string.provider_vehicles_sync_pending)
        )
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
