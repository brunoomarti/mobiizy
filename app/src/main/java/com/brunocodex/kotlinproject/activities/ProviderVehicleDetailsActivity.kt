package com.brunocodex.kotlinproject.activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.brunocodex.kotlinproject.R
import com.brunocodex.kotlinproject.services.SQLiteConfiguration
import com.brunocodex.kotlinproject.viewmodels.VehicleRegisterViewModel
import org.json.JSONObject

class ProviderVehicleDetailsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VEHICLE_ID = "extra_vehicle_id"
        const val EXTRA_VEHICLE_STATUS = "extra_vehicle_status"
        const val EXTRA_PAYLOAD_JSON = "extra_payload_json"
    }

    private lateinit var tvVehicleName: TextView
    private lateinit var tvVehicleStatus: TextView
    private lateinit var tvVehicleInfo: TextView
    private lateinit var btnEditListing: Button

    private var payloadRaw: String = ""
    private var vehicleStatus: String = SQLiteConfiguration.STATUS_PUBLISHED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_provider_vehicle_details)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        tvVehicleName = findViewById(R.id.tvVehicleName)
        tvVehicleStatus = findViewById(R.id.tvVehicleStatus)
        tvVehicleInfo = findViewById(R.id.tvVehicleInfo)
        btnEditListing = findViewById(R.id.btnEditListing)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        payloadRaw = intent.getStringExtra(EXTRA_PAYLOAD_JSON).orEmpty()
        vehicleStatus = intent.getStringExtra(EXTRA_VEHICLE_STATUS).orEmpty()
            .ifBlank { SQLiteConfiguration.STATUS_PUBLISHED }

        bindDetails()

        btnEditListing.setOnClickListener {
            val intent = Intent(this, ProviderVehicleRegisterActivity::class.java)
                .putExtra(ProviderVehicleRegisterActivity.EXTRA_INITIAL_DRAFT_JSON, payloadRaw)
            startActivity(intent)
        }
        btnEditListing.isEnabled = payloadRaw.isNotBlank()
    }

    private fun bindDetails() {
        val payload = runCatching { JSONObject(payloadRaw) }.getOrNull()

        val brand = payload?.optString("brand").orEmpty().trim()
        val model = payload?.optString("model").orEmpty().trim()
        val modelYear = payload?.optString("modelYear").orEmpty().trim()
        val title = listOf(brand, model)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { getString(R.string.provider_vehicles_unknown_vehicle) }
            .let { if (modelYear.isBlank()) it else "$it $modelYear" }

        val plate = payload?.optString("plate").orEmpty().trim()
        val cityState = payload?.optString("cityState").orEmpty().trim()
        val neighborhood = payload?.optString("neighborhood").orEmpty().trim()
        val dailyPrice = payload?.optString("dailyPrice").orEmpty().trim()
        val mileage = payload?.optString("mileage").orEmpty().trim()
        val condition = conditionLabel(payload?.optString("condition"))

        tvVehicleName.text = title
        tvVehicleStatus.text = statusLabel(vehicleStatus)
        tvVehicleInfo.text = listOf(
            infoLine(getString(R.string.vehicle_field_plate), plate),
            infoLine(getString(R.string.vehicle_field_city_state), cityState),
            infoLine(getString(R.string.vehicle_field_neighborhood_region), neighborhood),
            infoLine(getString(R.string.vehicle_field_daily_price), dailyPrice),
            infoLine(getString(R.string.vehicle_field_mileage), mileage),
            infoLine(getString(R.string.vehicle_field_condition), condition)
        ).joinToString("\n")
    }

    private fun statusLabel(status: String): String {
        return if (status == SQLiteConfiguration.STATUS_DRAFT) {
            getString(R.string.provider_vehicles_status_draft)
        } else {
            getString(R.string.provider_vehicles_status_published)
        }
    }

    private fun infoLine(label: String, value: String?): String {
        val safeValue = value.orEmpty().ifBlank { getString(R.string.vehicle_summary_not_informed) }
        return getString(R.string.provider_vehicle_details_line, label, safeValue)
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
}
