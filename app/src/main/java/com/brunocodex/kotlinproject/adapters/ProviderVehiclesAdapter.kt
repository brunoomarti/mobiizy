package com.brunocodex.kotlinproject.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.brunocodex.kotlinproject.R
import java.util.Locale

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
    val localDraftId: String? = null
)

class ProviderVehiclesAdapter(
    private val onCardClick: (ProviderVehicleCardUi) -> Unit
) : ListAdapter<ProviderVehicleCardUi, ProviderVehiclesAdapter.ProviderVehicleViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProviderVehicleViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_provider_vehicle_card, parent, false)
        return ProviderVehicleViewHolder(view, onCardClick)
    }

    override fun onBindViewHolder(holder: ProviderVehicleViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ProviderVehicleViewHolder(
        itemView: View,
        private val onCardClick: (ProviderVehicleCardUi) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val tvVehicleTitle: TextView = itemView.findViewById(R.id.tvVehicleTitle)
        private val tvVehiclePlate: TextView = itemView.findViewById(R.id.tvVehiclePlate)
        private val tvVehicleUpdatedAt: TextView = itemView.findViewById(R.id.tvVehicleUpdatedAt)
        private val tvVehicleStatus: TextView = itemView.findViewById(R.id.tvVehicleStatus)
        private val tvSyncPending: TextView = itemView.findViewById(R.id.tvSyncPending)
        private val ivVehicleThumb: ImageView = itemView.findViewById(R.id.ivVehicleThumb)

        fun bind(item: ProviderVehicleCardUi) {
            tvVehicleTitle.text = item.title
            tvVehiclePlate.text = item.plateLabel
            tvVehicleUpdatedAt.text = item.updatedAtLabel
            tvVehicleStatus.text = item.statusLabel
            tvSyncPending.text = item.pendingSyncLabel
            tvSyncPending.isVisible = item.hasPendingSync
            ivVehicleThumb.setImageResource(resolveVehicleImageRes(item))
            itemView.setOnClickListener { onCardClick(item) }
        }

        private fun resolveVehicleImageRes(item: ProviderVehicleCardUi): Int {
            val normalizedType = normalizeKey(item.vehicleType)
            val normalizedBodyType = normalizeKey(item.bodyType)

            if (normalizedType == "motorcycle" || normalizedBodyType in MOTORCYCLE_BODY_TYPES) {
                return R.drawable.motorcycle
            }

            return if (normalizedBodyType in SUV_LIKE_BODY_TYPES) {
                R.drawable.car_suv
            } else {
                R.drawable.car_sedan
            }
        }

        private fun normalizeKey(raw: String?): String {
            return raw.orEmpty()
                .trim()
                .lowercase(Locale.ROOT)
                .replace("-", "")
                .replace("_", "")
                .replace(" ", "")
        }

        companion object {
            private val MOTORCYCLE_BODY_TYPES = setOf(
                "street",
                "scooter",
                "trail",
                "naked",
                "sport",
                "touring",
                "custom",
                "bigtrail",
                "offroad"
            )

            private val SUV_LIKE_BODY_TYPES = setOf(
                "suv",
                "picape",
                "van",
                "crossover"
            )
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
