package com.brunocodex.kotlinproject.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleCoroutineScope
import com.brunocodex.kotlinproject.R
import com.brunocodex.kotlinproject.services.SupabaseStorageService
import com.brunocodex.kotlinproject.viewmodels.VehicleRegisterViewModel
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

class VehiclePhotoCarouselController(
    private val context: Context,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val photoView: ImageView,
    private val emptyView: TextView,
    private val prevButton: TextView,
    private val nextButton: TextView,
    private val dotsContainer: LinearLayout,
    private val progress: CircularProgressIndicator,
    @StringRes private val emptyMessageRes: Int,
    @StringRes private val loadErrorMessageRes: Int
) {

    companion object {
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

    private var photoUrls: List<String> = emptyList()
    private var currentPhotoIndex = 0
    private var photoLoadRequestToken = 0

    init {
        prevButton.setOnClickListener { goToPreviousPhoto() }
        nextButton.setOnClickListener { goToNextPhoto() }
    }

    fun bindFromPayload(payload: JSONObject?) {
        photoUrls = extractPhotoUrls(payload)
        currentPhotoIndex = 0
        photoLoadRequestToken++

        if (photoUrls.isEmpty()) {
            photoView.setImageDrawable(null)
            progress.isVisible = false
            emptyView.text = context.getString(emptyMessageRes)
            emptyView.isVisible = true
            prevButton.isVisible = false
            nextButton.isVisible = false
            dotsContainer.removeAllViews()
            return
        }

        emptyView.isVisible = false
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
        prevButton.isVisible = hasMultiplePhotos
        nextButton.isVisible = hasMultiplePhotos
        if (!hasMultiplePhotos) return

        val canGoBack = currentPhotoIndex > 0
        val canGoForward = currentPhotoIndex < photoUrls.lastIndex
        prevButton.isEnabled = canGoBack
        nextButton.isEnabled = canGoForward
        prevButton.alpha = if (canGoBack) 1f else 0.45f
        nextButton.alpha = if (canGoForward) 1f else 0.45f
    }

    private fun renderPhotoDots() {
        dotsContainer.removeAllViews()
        if (photoUrls.isEmpty()) return

        val normalSize = dpToPx(8)
        val selectedSize = dpToPx(11)
        val spacing = dpToPx(6)
        photoUrls.indices.forEach { index ->
            val dot = View(context).apply {
                setBackgroundResource(
                    if (index == currentPhotoIndex) R.drawable.dot_selected else R.drawable.dot_unselected
                )
            }
            val size = if (index == currentPhotoIndex) selectedSize else normalSize
            val params = LinearLayout.LayoutParams(size, size)
            if (index > 0) params.marginStart = spacing
            dot.layoutParams = params
            dotsContainer.addView(dot)
        }
    }

    private fun renderCurrentPhoto() {
        if (photoUrls.isEmpty()) return
        val url = photoUrls[currentPhotoIndex]
        val requestToken = ++photoLoadRequestToken

        progress.isVisible = true
        emptyView.isVisible = false

        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { loadBitmapFromUrl(url) }
            }

            if (requestToken != photoLoadRequestToken) return@launch
            progress.isVisible = false

            result.onSuccess { bitmap ->
                photoView.setImageBitmap(bitmap)
            }.onFailure {
                photoView.setImageDrawable(null)
                emptyView.text = context.getString(loadErrorMessageRes)
                emptyView.isVisible = true
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

    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
}
