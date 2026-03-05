package com.brunocodex.kotlinproject.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.brunocodex.kotlinproject.R

data class ProviderVehicleCardUi(
    val vehicleId: String,
    val payloadJson: String,
    val title: String,
    val plateLabel: String,
    val updatedAtLabel: String,
    val statusLabel: String,
    val status: String,
    val hasPendingSync: Boolean,
    val pendingSyncLabel: String
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
        fun bind(item: ProviderVehicleCardUi) {
            tvVehicleTitle.text = item.title
            tvVehiclePlate.text = item.plateLabel
            tvVehicleUpdatedAt.text = item.updatedAtLabel
            tvVehicleStatus.text = item.statusLabel
            tvSyncPending.text = item.pendingSyncLabel
            tvSyncPending.isVisible = item.hasPendingSync
            itemView.setOnClickListener { onCardClick(item) }
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
