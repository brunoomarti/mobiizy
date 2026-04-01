package com.brunocodex.kotlinproject.adapters

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.brunocodex.kotlinproject.R
import com.brunocodex.kotlinproject.services.SupabaseStorageService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import kotlin.math.roundToInt

data class ProviderVehicleCardUi(
    val vehicleId: String,
    val payloadJson: String,
    val title: String,
    val plate: String,
    val plateLabel: String,
    val updatedAtTimestamp: Long,
    val updatedAtLabel: String,
    val statusLabel: String,
    val status: String,
    val hasPendingSync: Boolean,
    val pendingSyncLabel: String,
    val vehicleType: String? = null,
    val bodyType: String? = null,
    val localDraftId: String? = null,
    val sideTagLabel: String? = null,
    val thumbnailUrl: String? = null
)

class ProviderVehiclesAdapter(
    private val lifecycleScope: LifecycleCoroutineScope,
    private val onCardClick: (ProviderVehicleCardUi) -> Unit
    ) : ListAdapter<ProviderVehicleCardUi, ProviderVehiclesAdapter.ProviderVehicleViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProviderVehicleViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_provider_vehicle_card, parent, false)
        return ProviderVehicleViewHolder(
            itemView = view,
            lifecycleScope = lifecycleScope,
            onCardClick = onCardClick
        )
    }

    override fun onBindViewHolder(holder: ProviderVehicleViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: ProviderVehicleViewHolder) {
        holder.clearImageLoad()
        super.onViewRecycled(holder)
    }

    override fun onViewDetachedFromWindow(holder: ProviderVehicleViewHolder) {
        holder.clearImageLoad()
        super.onViewDetachedFromWindow(holder)
    }

    class ProviderVehicleViewHolder(
        itemView: View,
        private val lifecycleScope: LifecycleCoroutineScope,
        private val onCardClick: (ProviderVehicleCardUi) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val tvVehicleTitle: TextView = itemView.findViewById(R.id.tvVehicleTitle)
        private val tvVehiclePlate: TextView = itemView.findViewById(R.id.tvVehiclePlate)
        private val tvVehicleUpdatedAt: TextView = itemView.findViewById(R.id.tvVehicleUpdatedAt)
        private val tvVehicleStatus: TextView = itemView.findViewById(R.id.tvVehicleStatus)
        private val tvVehicleSideTag: TextView = itemView.findViewById(R.id.tvVehicleSideTag)
        private val tvSyncPending: TextView = itemView.findViewById(R.id.tvSyncPending)
        private val ivVehicleThumb: ImageView = itemView.findViewById(R.id.ivVehicleThumb)
        private var imageLoadJob: Job? = null
        private var imageLoadToken = 0L

        fun bind(item: ProviderVehicleCardUi) {
            tvVehicleTitle.text = item.title
            tvVehiclePlate.text = item.plateLabel
            tvVehicleUpdatedAt.text = item.updatedAtLabel
            tvVehicleStatus.text = item.statusLabel
            tvVehicleSideTag.text = item.sideTagLabel.orEmpty()
            tvVehicleSideTag.isVisible = !item.sideTagLabel.isNullOrBlank()
            tvSyncPending.text = item.pendingSyncLabel
            tvSyncPending.isVisible = item.hasPendingSync
            bindVehicleThumbnail(item)
            itemView.setOnClickListener { onCardClick(item) }
        }

        fun clearImageLoad() {
            imageLoadJob?.cancel()
            imageLoadJob = null
            imageLoadToken += 1L
            showThumbnailPlaceholder()
        }

        private fun bindVehicleThumbnail(item: ProviderVehicleCardUi) {
            imageLoadJob?.cancel()
            imageLoadJob = null

            val requestToken = ++imageLoadToken
            showThumbnailPlaceholder()

            val photoUrl = resolveThumbnailUrl(item)
            if (photoUrl.isNullOrBlank()) return

            thumbnailBitmapCache.get(photoUrl)?.let { cachedBitmap ->
                if (!cachedBitmap.isRecycled) {
                    showThumbnailBitmap(cachedBitmap)
                    return
                }
            }

            val targetPx = (THUMBNAIL_SIZE_DP * itemView.resources.displayMetrics.density)
                .roundToInt()
                .coerceAtLeast(1)

            imageLoadJob = lifecycleScope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    try {
                        loadBitmapFromUrl(photoUrl, targetPx)
                    } catch (error: Throwable) {
                        if (error is CancellationException) throw error
                        Log.w(
                            TAG,
                            "Falha inesperada no carregamento da thumbnail para '$photoUrl'",
                            error
                        )
                        null
                    }
                }

                if (requestToken != imageLoadToken) return@launch
                if (bitmap == null) {
                    showThumbnailPlaceholder()
                    return@launch
                }

                thumbnailBitmapCache.put(photoUrl, bitmap)
                showThumbnailBitmap(bitmap)
            }
        }

        private fun showThumbnailPlaceholder() {
            ivVehicleThumb.setImageDrawable(null)
            ivVehicleThumb.setBackgroundResource(R.drawable.bg_skeleton_block)
        }

        private fun showThumbnailBitmap(bitmap: Bitmap) {
            ivVehicleThumb.background = null
            ivVehicleThumb.setImageBitmap(bitmap)
        }

        companion object {
            private const val TAG = "ProviderVehiclesAdapter"
            private const val THUMBNAIL_SIZE_DP = 88
            private const val PHOTO_FRONT_SLOT_KEY = "front"
            private val PHOTO_FRONT_SLOT_ALIASES = listOf(
                "front",
                "frente",
                "frontal",
                "foto_frontal",
                "front_view",
                "front-photo",
                "front_photo"
            )

            private val thumbnailBitmapCache = object : LruCache<String, Bitmap>(18 * 1024) {
                override fun sizeOf(key: String, value: Bitmap): Int {
                    return value.byteCount / 1024
                }
            }

            private fun resolveThumbnailUrl(item: ProviderVehicleCardUi): String? {
                val providedUrl = item.thumbnailUrl?.trim().orEmpty()
                if (providedUrl.isNotBlank()) return providedUrl

                val payload = runCatching { JSONObject(item.payloadJson) }.getOrNull() ?: return null
                val uploadedPhotoUrls = payload.optJSONObject("uploadedPhotoUrls")

                val frontPhoto = uploadedPhotoUrls
                    ?.optString(PHOTO_FRONT_SLOT_KEY)
                    ?.trim()
                    .orEmpty()
                if (frontPhoto.isNotBlank()) return frontPhoto

                if (uploadedPhotoUrls != null) {
                    val keys = uploadedPhotoUrls.keys()
                    while (keys.hasNext()) {
                        val rawKey = keys.next()
                        val key = rawKey.trim().lowercase()
                        if (PHOTO_FRONT_SLOT_ALIASES.none { alias -> key.contains(alias) }) {
                            continue
                        }

                        val candidate = uploadedPhotoUrls.optString(rawKey).trim()
                        if (candidate.isNotBlank()) return candidate
                    }
                }

                return null
            }

            private suspend fun loadBitmapFromUrl(photoUrl: String, targetPx: Int): Bitmap? {
                val normalizedUrl = photoUrl.trim()
                if (normalizedUrl.isBlank()) return null

                val bytes = try {
                    SupabaseStorageService.downloadBytesByPublicUrl(normalizedUrl)
                } catch (primaryError: Throwable) {
                    if (primaryError is CancellationException) throw primaryError

                    try {
                        URL(normalizedUrl).openConnection().apply {
                            connectTimeout = 15000
                            readTimeout = 15000
                        }.getInputStream().use { input ->
                            input.readBytes()
                        }
                    } catch (fallbackError: Throwable) {
                        if (fallbackError is CancellationException) throw fallbackError
                        Log.w(
                            TAG,
                            "Falha ao carregar thumbnail para URL '$normalizedUrl'",
                            fallbackError
                        )
                        return null
                    }
                }

                return decodeThumbnail(bytes, targetPx)
            }

            private fun decodeThumbnail(bytes: ByteArray, targetPx: Int): Bitmap? {
                val bounds = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

                val sampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight, targetPx)
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            }

            private fun calculateSampleSize(
                width: Int,
                height: Int,
                targetPx: Int
            ): Int {
                var inSampleSize = 1
                var currentWidth = width
                var currentHeight = height
                while (currentWidth / 2 >= targetPx && currentHeight / 2 >= targetPx) {
                    currentWidth /= 2
                    currentHeight /= 2
                    inSampleSize *= 2
                }
                return inSampleSize.coerceAtLeast(1)
            }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<ProviderVehicleCardUi>() {
        override fun areItemsTheSame(oldItem: ProviderVehicleCardUi, newItem: ProviderVehicleCardUi): Boolean {
            return oldItem.vehicleId == newItem.vehicleId
        }

        override fun areContentsTheSame(oldItem: ProviderVehicleCardUi, newItem: ProviderVehicleCardUi): Boolean {
            return oldItem == newItem
        }
    }
}
