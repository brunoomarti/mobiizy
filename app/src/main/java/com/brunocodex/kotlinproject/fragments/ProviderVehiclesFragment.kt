package com.brunocodex.kotlinproject.fragments

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.brunocodex.kotlinproject.R
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rvVehicles = view.findViewById(R.id.rvVehicles)
        tvVehiclesEmpty = view.findViewById(R.id.tvVehiclesEmpty)

        vehiclesAdapter = ProviderVehiclesAdapter { item ->
            continueDraft(item)
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

    private fun loadVehicles() {
        val ownerId = firebaseAuth.currentUser?.uid.orEmpty().ifBlank { "anonymous" }

        viewLifecycleOwner.lifecycleScope.launch {
            val rows = runCatching { vehicleSyncRepository.getVehicles(ownerId) }
                .getOrElse { emptyList() }

            val cards = rows.map { row ->
                row.toCardUi()
            }

            vehiclesAdapter.submitList(cards)
            val isEmpty = cards.isEmpty()
            tvVehiclesEmpty.isVisible = isEmpty
            rvVehicles.isVisible = !isEmpty
        }
    }

    private fun continueDraft(item: ProviderVehicleCardUi) {
        if (!item.isDraft) return

        val intent = Intent(requireContext(), ProviderVehicleRegisterActivity::class.java)
            .putExtra(ProviderVehicleRegisterActivity.EXTRA_INITIAL_DRAFT_JSON, item.payloadJson)
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
            isDraft = statusIsDraft,
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
}
