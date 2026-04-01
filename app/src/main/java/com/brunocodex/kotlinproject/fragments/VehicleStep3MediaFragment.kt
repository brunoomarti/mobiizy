package com.brunocodex.kotlinproject.fragments

import android.app.Dialog
import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.Button
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.brunocodex.kotlinproject.R
import com.brunocodex.kotlinproject.services.SupabaseStorageService
import com.brunocodex.kotlinproject.utils.StepValidatable
import com.brunocodex.kotlinproject.viewmodels.VehicleRegisterViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketException
import java.net.UnknownHostException
import java.net.URL
import java.util.Locale
import javax.net.ssl.SSLException

class VehicleStep3MediaFragment : Fragment(R.layout.fragment_vehicle_step3_media), StepValidatable {
    companion object {
        private const val TAG = "VehicleStep3Media"
    }

    private val vehicleViewModel: VehicleRegisterViewModel by activityViewModels()

    private data class MediaSlot(
        val key: String,
        val label: String,
        val required: Boolean
    )

    private data class SlotUi(
        val status: TextView,
        val button: Button,
        val viewButton: View
    )

    private val slotUiMap: LinkedHashMap<String, SlotUi> = linkedMapOf()
    private val uploadingSlotKeys: MutableSet<String> = linkedSetOf()
    private var selectedPhotoSlot: String? = null
    private var selectedCameraSlot: String? = null

    private lateinit var requiredUploadsContainer: GridLayout
    private lateinit var recommendedUploadsContainer: GridLayout
    private lateinit var tvRequiredPhotosError: TextView
    private lateinit var tvPhotoCountError: TextView
    private lateinit var tvUploadedPhotosCount: TextView
    private lateinit var stepSubtitle: TextView
    private lateinit var tvRequiredPhotosHint: TextView
    private lateinit var tvRecommendedPhotosHint: TextView
    private var lastRenderedVehicleType: String? = null

    private val photoPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val slotKey = selectedPhotoSlot
        selectedPhotoSlot = null
        if (uri == null || slotKey.isNullOrBlank()) return@registerForActivityResult
        uploadPhoto(slotKey) {
            prepareGalleryImageForUpload(uri)
        }
    }

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                launchCameraPickerSafely()
            } else {
                selectedCameraSlot = null
                showToast(R.string.vehicle_photo_camera_permission_denied)
            }
        }

    private val cameraPhotoPicker =
        registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
            val slotKey = selectedCameraSlot
            selectedCameraSlot = null
            if (bitmap == null || slotKey.isNullOrBlank()) return@registerForActivityResult
            uploadPhoto(slotKey) {
                prepareCameraImageForUpload(bitmap)
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<TextView>(R.id.stepHeadline).text =
            getString(R.string.vehicle_step3_headline)
        stepSubtitle = view.findViewById(R.id.stepSubtitle)
        tvRequiredPhotosHint = view.findViewById(R.id.tvRequiredPhotosHint)
        tvRecommendedPhotosHint = view.findViewById(R.id.tvRecommendedPhotosHint)

        requiredUploadsContainer = view.findViewById(R.id.requiredUploadsContainer)
        recommendedUploadsContainer = view.findViewById(R.id.recommendedUploadsContainer)
        tvRequiredPhotosError = view.findViewById(R.id.tvRequiredPhotosError)
        tvPhotoCountError = view.findViewById(R.id.tvPhotoCountError)
        tvUploadedPhotosCount = view.findViewById(R.id.tvUploadedPhotosCount)

        refreshVehicleTypeDependentUi(force = true)
    }

    override fun onResume() {
        super.onResume()
        refreshVehicleTypeDependentUi()
    }

    private fun refreshVehicleTypeDependentUi(force: Boolean = false) {
        val currentType = vehicleViewModel.vehicleType
        if (!force && currentType == lastRenderedVehicleType) return
        lastRenderedVehicleType = currentType

        val isMotorcycle = currentType == VehicleRegisterViewModel.TYPE_MOTORCYCLE
        stepSubtitle.text = getString(
            if (isMotorcycle) {
                R.string.vehicle_step3_subtitle_motorcycle
            } else {
                R.string.vehicle_step3_subtitle
            }
        )
        tvRequiredPhotosHint.text = getString(
            if (isMotorcycle) {
                R.string.vehicle_step3_required_photos_hint_motorcycle
            } else {
                R.string.vehicle_step3_required_photos_hint
            }
        )
        tvRecommendedPhotosHint.text = getString(
            if (isMotorcycle) {
                R.string.vehicle_step3_recommended_photos_hint_motorcycle
            } else {
                R.string.vehicle_step3_recommended_photos_hint
            }
        )

        inflateUploadSlots()
        renderPhotoSummary()
    }

    private fun inflateUploadSlots() {
        requiredUploadsContainer.removeAllViews()
        recommendedUploadsContainer.removeAllViews()
        slotUiMap.clear()

        var requiredIndex = 0
        var recommendedIndex = 0
        mediaSlotsForCurrentType().forEach { slot ->
            val row = layoutInflater.inflate(R.layout.item_vehicle_media_upload, null, false)
            val tvSlotName = row.findViewById<TextView>(R.id.tvSlotName)
            val tvSlotStatus = row.findViewById<TextView>(R.id.tvSlotStatus)
            val btnUpload = row.findViewById<Button>(R.id.btnUploadSlot)
            val btnViewPhoto = row.findViewById<View>(R.id.btnViewSlotPhoto)

            tvSlotName.text = slot.label
            btnUpload.setOnClickListener { launchPhotoSourceChooser(slot.key) }
            btnViewPhoto.setOnClickListener { openUploadedPhoto(slot.key) }

            slotUiMap[slot.key] = SlotUi(
                status = tvSlotStatus,
                button = btnUpload,
                viewButton = btnViewPhoto
            )
            updatePhotoSlotUi(slot.key)

            if (slot.required) {
                applyGridItemLayout(
                    view = row,
                    index = requiredIndex++,
                    columns = requiredUploadsContainer.columnCount
                )
                requiredUploadsContainer.addView(row)
            } else {
                applyGridItemLayout(
                    view = row,
                    index = recommendedIndex++,
                    columns = recommendedUploadsContainer.columnCount
                )
                recommendedUploadsContainer.addView(row)
            }
        }
    }

    private fun mediaSlotsForCurrentType(): List<MediaSlot> {
        val isMotorcycle = vehicleViewModel.vehicleType == VehicleRegisterViewModel.TYPE_MOTORCYCLE
        return if (isMotorcycle) {
            listOf(
                MediaSlot(
                    key = VehicleRegisterViewModel.PHOTO_FRONT,
                    label = getString(R.string.vehicle_photo_front),
                    required = true
                ),
                MediaSlot(
                    key = VehicleRegisterViewModel.PHOTO_REAR,
                    label = getString(R.string.vehicle_photo_rear),
                    required = true
                ),
                MediaSlot(
                    key = VehicleRegisterViewModel.PHOTO_LEFT_SIDE,
                    label = getString(R.string.vehicle_photo_left_side),
                    required = true
                ),
                MediaSlot(
                    key = VehicleRegisterViewModel.PHOTO_RIGHT_SIDE,
                    label = getString(R.string.vehicle_photo_right_side),
                    required = true
                ),
                MediaSlot(
                    key = VehicleRegisterViewModel.PHOTO_DASHBOARD,
                    label = getString(R.string.vehicle_moto_photo_panel),
                    required = true
                ),
                MediaSlot(
                    key = VehicleRegisterViewModel.PHOTO_ENGINE,
                    label = getString(R.string.vehicle_moto_photo_engine),
                    required = true
                ),
                MediaSlot(
                    key = VehicleRegisterViewModel.PHOTO_TIRES_WHEELS,
                    label = getString(R.string.vehicle_photo_tires_wheels),
                    required = false
                ),
                MediaSlot(
                    key = VehicleRegisterViewModel.PHOTO_EXHAUST,
                    label = getString(R.string.vehicle_moto_photo_exhaust),
                    required = false
                ),
                MediaSlot(
                    key = VehicleRegisterViewModel.PHOTO_ACCESSORIES,
                    label = getString(R.string.vehicle_moto_photo_accessories),
                    required = false
                )
            )
        } else {
            listOf(
                MediaSlot(
                    key = VehicleRegisterViewModel.PHOTO_FRONT,
                    label = getString(R.string.vehicle_photo_front),
                    required = true
                ),
                MediaSlot(
                    key = VehicleRegisterViewModel.PHOTO_REAR,
                    label = getString(R.string.vehicle_photo_rear),
                    required = true
                ),
                MediaSlot(
                    key = VehicleRegisterViewModel.PHOTO_LEFT_SIDE,
                    label = getString(R.string.vehicle_photo_left_side),
                    required = true
                ),
                MediaSlot(
                    key = VehicleRegisterViewModel.PHOTO_RIGHT_SIDE,
                    label = getString(R.string.vehicle_photo_right_side),
                    required = true
                ),
                MediaSlot(
                    key = VehicleRegisterViewModel.PHOTO_DASHBOARD,
                    label = getString(R.string.vehicle_photo_dashboard),
                    required = true
                ),
                MediaSlot(
                    key = VehicleRegisterViewModel.PHOTO_TRUNK,
                    label = getString(R.string.vehicle_photo_trunk),
                    required = true
                ),
                MediaSlot(
                    key = VehicleRegisterViewModel.PHOTO_TIRES_WHEELS,
                    label = getString(R.string.vehicle_photo_tires_wheels),
                    required = false
                ),
                MediaSlot(
                    key = VehicleRegisterViewModel.PHOTO_FINISHING,
                    label = getString(R.string.vehicle_photo_finishing_details),
                    required = false
                ),
                MediaSlot(
                    key = VehicleRegisterViewModel.PHOTO_ACCESSORIES,
                    label = getString(R.string.vehicle_photo_accessories),
                    required = false
                )
            )
        }
    }

    private fun isSupabaseReady(): Boolean {
        if (SupabaseStorageService.isReady()) return true
        val missing = SupabaseStorageService.missingConfigurationKeys().joinToString(", ")
        val detail = if (missing.isBlank()) "" else " [$missing]"
        Toast.makeText(
            requireContext(),
            getString(R.string.vehicle_error_supabase_not_ready) + detail,
            Toast.LENGTH_LONG
        ).show()
        return false
    }

    private fun launchPhotoSourceChooser(slotKey: String) {
        if (!isSupabaseReady()) return

        val options = arrayOf(
            getString(R.string.vehicle_photo_source_camera),
            getString(R.string.vehicle_photo_source_gallery)
        )

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.vehicle_photo_source_dialog_title)
            .setItems(options) { _, selectedIndex ->
                when (selectedIndex) {
                    0 -> ensureCameraPermissionAndLaunch(slotKey)

                    1 -> {
                        selectedPhotoSlot = slotKey
                        photoPicker.launch("image/*")
                    }
                }
            }
            .setNegativeButton(R.string.vehicle_photo_source_cancel, null)
            .show()
    }

    private fun ensureCameraPermissionAndLaunch(slotKey: String) {
        selectedCameraSlot = slotKey
        val context = context ?: run {
            selectedCameraSlot = null
            return
        }

        val alreadyGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (alreadyGranted) {
            launchCameraPickerSafely()
            return
        }

        val shouldShowRationale = shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        if (shouldShowRationale) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.vehicle_photo_camera_permission_title)
                .setMessage(R.string.vehicle_photo_camera_permission_message)
                .setPositiveButton(R.string.vehicle_photo_camera_permission_confirm) { _, _ ->
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
                .setNegativeButton(R.string.vehicle_photo_source_cancel) { _, _ ->
                    selectedCameraSlot = null
                }
                .show()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchCameraPickerSafely() {
        runCatching {
            cameraPhotoPicker.launch(null)
        }.onFailure { throwable ->
            selectedCameraSlot = null
            val knownExpected = throwable is ActivityNotFoundException || throwable is IllegalStateException
            Log.e(TAG, "Unable to launch camera source (knownExpected=$knownExpected)", throwable)
            showToast(R.string.vehicle_photo_camera_unavailable)
        }
    }

    private fun showToast(@StringRes messageRes: Int) {
        context?.let { ctx ->
            Toast.makeText(ctx, messageRes, Toast.LENGTH_LONG).show()
        }
    }

    private fun uploadPhoto(
        slotKey: String,
        mediaProvider: () -> Pair<ByteArray, String>
    ) {
        if (uploadingSlotKeys.contains(slotKey)) return
        val slotUi = slotUiMap[slotKey] ?: return
        val ownerId = FirebaseAuth.getInstance().currentUser?.uid.orEmpty().ifBlank { "anonymous" }
        val plate = vehicleViewModel.plate?.trim().orEmpty()
        val previousPhotoUrl = vehicleViewModel.uploadedPhotoUrls[slotKey]
        if (plate.isBlank()) {
            Toast.makeText(
                requireContext(),
                getString(R.string.vehicle_error_fill_plate_before_upload),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        uploadingSlotKeys += slotKey
        updatePhotoSlotUi(slotKey)

        viewLifecycleOwner.lifecycleScope.launch {
            val uploadResult = runCatching {
                withContext(Dispatchers.IO) {
                    val (bytes, extension) = mediaProvider()
                    val publicUrl = uploadPhotoWithRetry(
                        ownerId = ownerId,
                        plate = plate,
                        slotKey = slotKey,
                        bytes = bytes,
                        extension = extension
                    )
                    if (!previousPhotoUrl.isNullOrBlank() && previousPhotoUrl != publicUrl) {
                        runCatching { SupabaseStorageService.deleteByPublicUrl(previousPhotoUrl) }
                    }
                    publicUrl
                }
            }

            uploadingSlotKeys.remove(slotKey)

            uploadResult.onSuccess { publicUrl ->
                vehicleViewModel.uploadedPhotoUrls[slotKey] = publicUrl
                updatePhotoSlotUi(slotKey)
                renderPhotoSummary()
                tvRequiredPhotosError.isVisible = false
                if (vehicleViewModel.photoCount() >= 6) {
                    tvPhotoCountError.isVisible = false
                }
            }.onFailure {
                if (it is CancellationException) {
                    updatePhotoSlotUi(slotKey)
                    return@onFailure
                }
                updatePhotoSlotUi(slotKey)
                if (previousPhotoUrl.isNullOrBlank()) {
                    slotUi.status.text = getString(R.string.vehicle_photo_status_failed)
                }
                Log.e(TAG, "Upload failed for slot=$slotKey plate=$plate", it)
                val toastMessage = when {
                    isTransientNetworkError(it) -> getString(R.string.vehicle_error_upload_failed_network)
                    isSupabasePolicyOrAuthError(it) -> getString(R.string.vehicle_error_upload_failed_permission)
                    else -> getString(R.string.vehicle_error_upload_failed)
                }
                Toast.makeText(
                    requireContext(),
                    toastMessage,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun updatePhotoSlotUi(slotKey: String) {
        val slotUi = slotUiMap[slotKey] ?: return
        val isUploading = uploadingSlotKeys.contains(slotKey)
        val uploaded = vehicleViewModel.uploadedPhotoUrls[slotKey].isNullOrBlank().not()
        slotUi.status.text = when {
            isUploading -> getString(R.string.vehicle_photo_status_uploading)
            uploaded -> getString(R.string.vehicle_photo_status_uploaded)
            else -> getString(R.string.vehicle_photo_status_pending)
        }
        slotUi.button.text = when {
            isUploading -> getString(R.string.vehicle_photo_status_uploading)
            uploaded -> getString(R.string.vehicle_replace_photo_button)
            else -> getString(R.string.vehicle_upload_photo_button)
        }
        slotUi.button.isEnabled = !isUploading
        slotUi.viewButton.isEnabled = uploaded && !isUploading
        slotUi.viewButton.isVisible = uploaded
    }

    private fun openUploadedPhoto(slotKey: String) {
        val photoUrl = vehicleViewModel.uploadedPhotoUrls[slotKey].orEmpty()
        if (photoUrl.isBlank()) return

        showPhotoPreviewDialog(photoUrl)
    }

    private fun showPhotoPreviewDialog(photoUrl: String) {
        val dialog = Dialog(requireContext(), android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(R.layout.dialog_photo_preview)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        val imageView = dialog.findViewById<ImageView>(R.id.ivPhotoPreview)
        val progress = dialog.findViewById<CircularProgressIndicator>(R.id.progressPhotoPreview)
        val closeButton = dialog.findViewById<ImageButton>(R.id.btnClosePhotoPreview)
        closeButton.setOnClickListener { dialog.dismiss() }

        dialog.show()

        viewLifecycleOwner.lifecycleScope.launch {
            val bitmapResult = runCatching {
                withContext(Dispatchers.IO) { loadBitmapFromUrl(photoUrl) }
            }

            if (!dialog.isShowing) return@launch
            progress.isVisible = false

            bitmapResult.onSuccess { bitmap ->
                imageView.setImageBitmap(bitmap)
            }.onFailure {
                Log.e(TAG, "Failed to open uploaded photo preview: ${collectThrowableMessages(it)}", it)
                Toast.makeText(
                    requireContext(),
                    getString(R.string.vehicle_error_open_photo_failed),
                    Toast.LENGTH_LONG
                ).show()
                dialog.dismiss()
            }
        }
    }

    private fun renderPhotoSummary() {
        tvUploadedPhotosCount.text = getString(
            R.string.vehicle_uploaded_photos_count,
            vehicleViewModel.photoCount()
        )
    }

    private fun applyGridItemLayout(view: View, index: Int, columns: Int) {
        val gap = (12 * resources.displayMetrics.density).toInt()
        val safeColumns = if (columns <= 0) 2 else columns
        val column = index % safeColumns
        val row = index / safeColumns
        val halfGap = gap / 2
        val params = GridLayout.LayoutParams().apply {
            width = 0
            height = GridLayout.LayoutParams.WRAP_CONTENT
            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            topMargin = if (row == 0) 0 else gap
            bottomMargin = 0
            marginStart = if (column == 0) 0 else halfGap
            marginEnd = if (column == safeColumns - 1) 0 else halfGap
        }
        view.layoutParams = params
    }

    private fun readBytes(uri: Uri): ByteArray {
        return requireContext().contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Unable to open selected file")
    }

    private fun bitmapToJpegBytes(bitmap: Bitmap): ByteArray {
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
            output.toByteArray()
        }
    }

    private fun prepareGalleryImageForUpload(uri: Uri): Pair<ByteArray, String> {
        val originalBytes = readBytes(uri)
        val fallbackExtension = guessFileExtension(uri, "jpg")
        val bitmap = decodeBitmapFromUri(uri) ?: return originalBytes to fallbackExtension
        return chooseBestImageEncoding(
            bitmap = bitmap,
            originalBytes = originalBytes,
            fallbackExtension = fallbackExtension
        )
    }

    private fun prepareCameraImageForUpload(bitmap: Bitmap): Pair<ByteArray, String> {
        val webpLosslessBytes = bitmapToBytes(bitmap, Bitmap.CompressFormat.WEBP_LOSSLESS, 100)
        if (webpLosslessBytes.isNotEmpty()) return webpLosslessBytes to "webp"
        return bitmapToJpegBytes(bitmap) to "jpg"
    }

    private fun chooseBestImageEncoding(
        bitmap: Bitmap,
        originalBytes: ByteArray,
        fallbackExtension: String
    ): Pair<ByteArray, String> {
        val webpLosslessBytes = bitmapToBytes(bitmap, Bitmap.CompressFormat.WEBP_LOSSLESS, 100)
        if (webpLosslessBytes.isNotEmpty() && webpLosslessBytes.size <= originalBytes.size) {
            return webpLosslessBytes to "webp"
        }

        // Prioriza alta qualidade visual quando a versao sem perdas nao reduz tamanho.
        val webpHighQualityBytes = bitmapToBytes(bitmap, Bitmap.CompressFormat.WEBP_LOSSY, 92)
        if (webpHighQualityBytes.isNotEmpty() && webpHighQualityBytes.size < originalBytes.size) {
            return webpHighQualityBytes to "webp"
        }

        return originalBytes to fallbackExtension
    }

    private fun decodeBitmapFromUri(uri: Uri): Bitmap? {
        return requireContext().contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input)
        }
    }

    private fun bitmapToBytes(
        bitmap: Bitmap,
        format: Bitmap.CompressFormat,
        quality: Int
    ): ByteArray {
        return ByteArrayOutputStream().use { output ->
            val ok = bitmap.compress(format, quality, output)
            if (!ok) return ByteArray(0)
            output.toByteArray()
        }
    }

    private suspend fun loadBitmapFromUrl(photoUrl: String): Bitmap {
        val bytes = runCatching {
            SupabaseStorageService.downloadBytesByPublicUrl(photoUrl)
        }.getOrElse {
            URL(photoUrl).openConnection().apply {
                connectTimeout = 15000
                readTimeout = 15000
            }.getInputStream().use { input -> input.readBytes() }
        }

        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: error("Unable to decode bitmap from uploaded bytes")
    }

    private fun guessFileExtension(uri: Uri, fallback: String): String {
        val mime = requireContext().contentResolver.getType(uri)
        val byMime = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime.orEmpty())
        if (!byMime.isNullOrBlank()) return byMime.lowercase(Locale.ROOT)

        val lastSegment = uri.lastPathSegment.orEmpty()
        val dotIndex = lastSegment.lastIndexOf('.')
        if (dotIndex in 1 until lastSegment.lastIndex) {
            return lastSegment.substring(dotIndex + 1).lowercase(Locale.ROOT)
        }
        return fallback
    }

    private suspend fun uploadPhotoWithRetry(
        ownerId: String,
        plate: String,
        slotKey: String,
        bytes: ByteArray,
        extension: String
    ): String {
        repeat(3) { attempt ->
            try {
                return SupabaseStorageService.uploadVehiclePhotoByPlate(
                    ownerId = ownerId,
                    vehiclePlate = plate,
                    mediaCategory = slotKey,
                    bytes = bytes,
                    fileExtension = extension
                )
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                val shouldRetry = attempt < 2 && isTransientNetworkError(throwable)
                if (!shouldRetry) throw throwable
                delay((attempt + 1) * 500L)
            }
        }
        error("Unexpected upload retry flow")
    }

    private fun isTransientNetworkError(throwable: Throwable): Boolean {
        var current: Throwable? = throwable
        while (current != null) {
            when (current) {
                is UnknownHostException,
                is SocketException,
                is SSLException,
                is IOException -> return true
            }
            current = current.cause
        }

        val message = throwable.message.orEmpty().lowercase(Locale.ROOT)
        return message.contains("resolve name") ||
            message.contains("broken pipe") ||
            message.contains("connection abort") ||
            message.contains("timeout")
    }

    private fun isSupabasePolicyOrAuthError(throwable: Throwable): Boolean {
        val message = collectThrowableMessages(throwable).lowercase(Locale.ROOT)
        return message.contains("row-level security") ||
            message.contains("permission denied") ||
            message.contains("unauthorized") ||
            message.contains("status code 401") ||
            message.contains("status code 403") ||
            message.contains("jwt")
    }

    private fun collectThrowableMessages(throwable: Throwable): String {
        val messages = StringBuilder()
        var current: Throwable? = throwable
        while (current != null) {
            if (messages.isNotEmpty()) messages.append(" | ")
            messages.append(current.message.orEmpty())
            current = current.cause
        }
        return messages.toString()
    }

    override fun validateStep(showErrors: Boolean): Boolean {
        val requiredPhotosOk = vehicleViewModel.requiredPhotosChecked()
        val minPhotosOk = vehicleViewModel.photoCount() >= 6
        if (showErrors) {
            tvRequiredPhotosError.isVisible = !requiredPhotosOk
            tvPhotoCountError.isVisible = !minPhotosOk
        }
        return requiredPhotosOk && minPhotosOk
    }
}

