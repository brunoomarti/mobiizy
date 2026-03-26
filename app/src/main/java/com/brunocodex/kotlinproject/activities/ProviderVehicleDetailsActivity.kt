package com.brunocodex.kotlinproject.activities

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.brunocodex.kotlinproject.R
import com.brunocodex.kotlinproject.services.SQLiteConfiguration
import com.brunocodex.kotlinproject.services.SupabaseStorageService
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.brunocodex.kotlinproject.viewmodels.VehicleRegisterViewModel
import org.json.JSONObject
import java.net.URL

class ProviderVehicleDetailsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VEHICLE_ID = "extra_vehicle_id"
        const val EXTRA_VEHICLE_STATUS = "extra_vehicle_status"
        const val EXTRA_PAYLOAD_JSON = "extra_payload_json"

        private val PHOTO_SLOT_ORDER = listOf(
            VehicleRegisterViewModel.PHOTO_FRONT,
            VehicleRegisterViewModel.PHOTO_REAR,
            VehicleRegisterViewModel.PHOTO_LEFT_SIDE,
            VehicleRegisterViewModel.PHOTO_RIGHT_SIDE,
            VehicleRegisterViewModel.PHOTO_DASHBOARD,
            VehicleRegisterViewModel.PHOTO_TRUNK,
            VehicleRegisterViewModel.PHOTO_ENGINE,
            VehicleRegisterViewModel.PHOTO_TIRES_WHEELS,
            VehicleRegisterViewModel.PHOTO_FINISHING,
            VehicleRegisterViewModel.PHOTO_ACCESSORIES,
            VehicleRegisterViewModel.PHOTO_EXHAUST
        )
    }

    private lateinit var tvVehicleName: TextView
    private lateinit var tvVehicleStatus: TextView
    private lateinit var tvVehicleInfo: TextView
    private lateinit var ivVehiclePhotoCarousel: ImageView
    private lateinit var tvVehiclePhotosEmpty: TextView
    private lateinit var btnPrevPhoto: TextView
    private lateinit var btnNextPhoto: TextView
    private lateinit var photoDotsContainer: LinearLayout
    private lateinit var progressVehiclePhoto: CircularProgressIndicator
    private lateinit var btnEditListing: Button

    private var payloadRaw: String = ""
    private var vehicleStatus: String = SQLiteConfiguration.STATUS_PUBLISHED
    private var photoUrls: List<String> = emptyList()
    private var currentPhotoIndex = 0
    private var photoLoadRequestToken = 0

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
        ivVehiclePhotoCarousel = findViewById(R.id.ivVehiclePhotoCarousel)
        tvVehiclePhotosEmpty = findViewById(R.id.tvVehiclePhotosEmpty)
        btnPrevPhoto = findViewById(R.id.btnPrevPhoto)
        btnNextPhoto = findViewById(R.id.btnNextPhoto)
        photoDotsContainer = findViewById(R.id.photoDotsContainer)
        progressVehiclePhoto = findViewById(R.id.progressVehiclePhoto)
        btnEditListing = findViewById(R.id.btnEditListing)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        btnPrevPhoto.setOnClickListener { goToPreviousPhoto() }
        btnNextPhoto.setOnClickListener { goToNextPhoto() }

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

        bindPhotoCarousel(payload)
    }

    private fun bindPhotoCarousel(payload: JSONObject?) {
        photoUrls = extractPhotoUrls(payload)
        currentPhotoIndex = 0
        photoLoadRequestToken++

        if (photoUrls.isEmpty()) {
            ivVehiclePhotoCarousel.setImageDrawable(null)
            progressVehiclePhoto.isVisible = false
            tvVehiclePhotosEmpty.text = getString(R.string.provider_vehicle_details_photos_empty)
            tvVehiclePhotosEmpty.isVisible = true
            btnPrevPhoto.isVisible = false
            btnNextPhoto.isVisible = false
            photoDotsContainer.removeAllViews()
            return
        }

        tvVehiclePhotosEmpty.isVisible = false
        renderPhotoDots()
        updatePhotoButtonsState()
        renderCurrentPhoto()
    }

    private fun extractPhotoUrls(payload: JSONObject?): List<String> {
        val uploadedPhotos = payload?.optJSONObject("uploadedPhotoUrls") ?: return emptyList()
        val bySlot = linkedMapOf<String, String>()
        val keys = uploadedPhotos.keys()
        while (keys.hasNext()) {
            val key = keys.next().trim()
            val value = uploadedPhotos.optString(key).trim()
            if (key.isNotBlank() && value.isNotBlank()) {
                bySlot[key] = value
            }
        }

        val ordered = mutableListOf<String>()
        PHOTO_SLOT_ORDER.forEach { slot ->
            bySlot[slot]?.let(ordered::add)
        }
        bySlot.keys
            .filter { slot -> slot !in PHOTO_SLOT_ORDER }
            .sorted()
            .forEach { slot -> bySlot[slot]?.let(ordered::add) }

        return ordered.distinct()
    }

    private fun goToPreviousPhoto() {
        if (photoUrls.isEmpty() || currentPhotoIndex <= 0) return
        currentPhotoIndex -= 1
        renderPhotoDots()
        updatePhotoButtonsState()
        renderCurrentPhoto()
    }

    private fun goToNextPhoto() {
        if (photoUrls.isEmpty() || currentPhotoIndex >= photoUrls.lastIndex) return
        currentPhotoIndex += 1
        renderPhotoDots()
        updatePhotoButtonsState()
        renderCurrentPhoto()
    }

    private fun updatePhotoButtonsState() {
        val hasMultiplePhotos = photoUrls.size > 1
        btnPrevPhoto.isVisible = hasMultiplePhotos
        btnNextPhoto.isVisible = hasMultiplePhotos
        if (!hasMultiplePhotos) return

        val canGoBack = currentPhotoIndex > 0
        val canGoForward = currentPhotoIndex < photoUrls.lastIndex
        btnPrevPhoto.isEnabled = canGoBack
        btnNextPhoto.isEnabled = canGoForward
        btnPrevPhoto.alpha = if (canGoBack) 1f else 0.45f
        btnNextPhoto.alpha = if (canGoForward) 1f else 0.45f
    }

    private fun renderPhotoDots() {
        photoDotsContainer.removeAllViews()
        if (photoUrls.isEmpty()) return

        val normalSize = dpToPx(8)
        val selectedSize = dpToPx(11)
        val spacing = dpToPx(6)
        photoUrls.indices.forEach { index ->
            val dot = View(this).apply {
                setBackgroundResource(
                    if (index == currentPhotoIndex) R.drawable.dot_selected else R.drawable.dot_unselected
                )
            }
            val size = if (index == currentPhotoIndex) selectedSize else normalSize
            val params = LinearLayout.LayoutParams(size, size)
            if (index > 0) params.marginStart = spacing
            dot.layoutParams = params
            photoDotsContainer.addView(dot)
        }
    }

    private fun renderCurrentPhoto() {
        if (photoUrls.isEmpty()) return
        val url = photoUrls[currentPhotoIndex]
        val requestToken = ++photoLoadRequestToken

        progressVehiclePhoto.isVisible = true
        tvVehiclePhotosEmpty.isVisible = false

        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { loadBitmapFromUrl(url) }
            }

            if (requestToken != photoLoadRequestToken) return@launch
            progressVehiclePhoto.isVisible = false

            result.onSuccess { bitmap ->
                ivVehiclePhotoCarousel.setImageBitmap(bitmap)
            }.onFailure {
                ivVehiclePhotoCarousel.setImageDrawable(null)
                tvVehiclePhotosEmpty.text = getString(R.string.vehicle_error_open_photo_failed)
                tvVehiclePhotosEmpty.isVisible = true
            }
        }
    }

    private suspend fun loadBitmapFromUrl(photoUrl: String): Bitmap {
        val bytes = runCatching {
            SupabaseStorageService.downloadBytesByPublicUrl(photoUrl)
        }.getOrElse {
            URL(photoUrl).openConnection().apply {
                connectTimeout = 15000
                readTimeout = 15000
            }.getInputStream().use { it.readBytes() }
        }

        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: error("Unable to decode bitmap from bytes")
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

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}
