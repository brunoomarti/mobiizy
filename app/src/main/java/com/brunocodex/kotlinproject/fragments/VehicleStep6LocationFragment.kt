package com.brunocodex.kotlinproject.fragments

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.brunocodex.kotlinproject.R
import com.brunocodex.kotlinproject.components.BankPriceInputEditText
import com.brunocodex.kotlinproject.utils.StepValidatable
import com.brunocodex.kotlinproject.utils.StepValidationUtils
import com.brunocodex.kotlinproject.viewmodels.VehicleRegisterViewModel
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class VehicleStep6LocationFragment :
    Fragment(R.layout.fragment_vehicle_step6_location),
    StepValidatable {

    private val vehicleViewModel: VehicleRegisterViewModel by activityViewModels()

    private data class DayRowViews(
        val enabledCheck: MaterialCheckBox,
        val startLayout: TextInputLayout,
        val endLayout: TextInputLayout
    )

    private val dayRows: LinkedHashMap<String, DayRowViews> = linkedMapOf()

    private lateinit var deliveryFeeContainer: View
    private lateinit var cbPickupOnLocation: MaterialCheckBox
    private lateinit var cbDeliveryByFee: MaterialCheckBox
    private lateinit var deliveryRadiusLayout: TextInputLayout
    private lateinit var deliveryFeeLayout: TextInputLayout
    private lateinit var deliveryFeeInput: BankPriceInputEditText
    private lateinit var tvDeliveryOptionError: TextView
    private lateinit var tvScheduleError: TextView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<TextView>(R.id.stepHeadline).text =
            getString(R.string.vehicle_step6_headline)
        view.findViewById<TextView>(R.id.stepSubtitle).text =
            getString(R.string.vehicle_step6_subtitle)

        deliveryFeeContainer = view.findViewById(R.id.deliveryFeeContainer)
        cbPickupOnLocation = view.findViewById(R.id.cbPickupOnLocation)
        cbDeliveryByFee = view.findViewById(R.id.cbDeliveryByFee)
        deliveryRadiusLayout = view.findViewById(R.id.deliveryRadiusLayout)
        deliveryFeeLayout = view.findViewById(R.id.deliveryFeeLayout)
        deliveryFeeInput = view.findViewById(R.id.deliveryFeeInput)
        tvDeliveryOptionError = view.findViewById(R.id.tvDeliveryOptionError)
        tvScheduleError = view.findViewById(R.id.tvScheduleError)

        bindText(view, R.id.cityStateInput, vehicleViewModel.cityState) { vehicleViewModel.cityState = it }
        bindText(view, R.id.neighborhoodInput, vehicleViewModel.neighborhood) { vehicleViewModel.neighborhood = it }
        bindText(view, R.id.pickupPointInput, vehicleViewModel.pickupPoint) { vehicleViewModel.pickupPoint = it }
        bindText(
            view,
            R.id.deliveryRadiusInput,
            vehicleViewModel.deliveryRadiusKm
        ) {
            vehicleViewModel.deliveryRadiusKm = it
            if (!it.isNullOrBlank()) deliveryRadiusLayout.error = null
        }
        bindBankPrice(view, R.id.deliveryFeeInput, vehicleViewModel.deliveryFee) {
            if (vehicleViewModel.deliveryByFee) {
                vehicleViewModel.deliveryFee = it
                if (!it.isNullOrBlank()) deliveryFeeLayout.error = null
            }
        }

        setupDeliveryOptions()
        setupScheduleRows(view.findViewById(R.id.scheduleContainer))
    }

    private fun bindText(
        root: View,
        inputId: Int,
        initialValue: String?,
        onChange: (String?) -> Unit
    ) {
        val input = root.findViewById<TextInputEditText>(inputId)
        input.setText(initialValue.orEmpty())
        input.doAfterTextChanged { onChange(it?.toString()?.trim()) }
    }

    private fun bindBankPrice(
        root: View,
        inputId: Int,
        initialValue: String?,
        onChange: (String?) -> Unit
    ) {
        val input = root.findViewById<BankPriceInputEditText>(inputId)
        input.bindBankPrice(initialValue) { formattedValue ->
            onChange(formattedValue)
        }
    }

    private fun setupDeliveryOptions() {
        cbPickupOnLocation.isChecked = vehicleViewModel.pickupOnLocation
        cbDeliveryByFee.isChecked = vehicleViewModel.deliveryByFee
        updateDeliveryFeeVisibility(vehicleViewModel.deliveryByFee)
        if (vehicleViewModel.deliveryByFee) {
            vehicleViewModel.deliveryFee = deliveryFeeInput.text?.toString()?.trim().orEmpty()
                .ifBlank { null }
        } else {
            vehicleViewModel.deliveryFee = null
        }

        cbPickupOnLocation.setOnCheckedChangeListener { _, isChecked ->
            vehicleViewModel.pickupOnLocation = isChecked
            if (isChecked || vehicleViewModel.deliveryByFee) {
                tvDeliveryOptionError.visibility = View.GONE
            }
        }

        cbDeliveryByFee.setOnCheckedChangeListener { _, isChecked ->
            vehicleViewModel.deliveryByFee = isChecked
            updateDeliveryFeeVisibility(isChecked)
            if (!isChecked) {
                deliveryRadiusLayout.error = null
                deliveryFeeLayout.error = null
                vehicleViewModel.deliveryRadiusKm = null
                vehicleViewModel.deliveryFee = null
            } else {
                vehicleViewModel.deliveryFee = deliveryFeeInput.text?.toString()?.trim().orEmpty()
                    .ifBlank { null }
                if (!vehicleViewModel.deliveryFee.isNullOrBlank()) deliveryFeeLayout.error = null
            }
            if (vehicleViewModel.pickupOnLocation || isChecked) {
                tvDeliveryOptionError.visibility = View.GONE
            }
        }
    }

    private fun updateDeliveryFeeVisibility(visible: Boolean) {
        deliveryFeeContainer.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun setupScheduleRows(container: LinearLayout) {
        container.removeAllViews()
        dayRows.clear()

        val days = listOf(
            VehicleRegisterViewModel.DAY_MON to getString(R.string.vehicle_day_mon),
            VehicleRegisterViewModel.DAY_TUE to getString(R.string.vehicle_day_tue),
            VehicleRegisterViewModel.DAY_WED to getString(R.string.vehicle_day_wed),
            VehicleRegisterViewModel.DAY_THU to getString(R.string.vehicle_day_thu),
            VehicleRegisterViewModel.DAY_FRI to getString(R.string.vehicle_day_fri),
            VehicleRegisterViewModel.DAY_SAT to getString(R.string.vehicle_day_sat),
            VehicleRegisterViewModel.DAY_SUN to getString(R.string.vehicle_day_sun)
        )

        days.forEach { (dayKey, dayLabel) ->
            val row = layoutInflater.inflate(R.layout.item_day_schedule, container, false)
            val schedule = vehicleViewModel.weeklySchedule[dayKey] ?: VehicleRegisterViewModel.DaySchedule()
            vehicleViewModel.weeklySchedule[dayKey] = schedule

            val tvDayName = row.findViewById<TextView>(R.id.tvDayName)
            val cbEnabled = row.findViewById<MaterialCheckBox>(R.id.cbDayEnabled)
            val timeContainer = row.findViewById<View>(R.id.timeInputsContainer)
            val startLayout = row.findViewById<TextInputLayout>(R.id.startTimeLayout)
            val endLayout = row.findViewById<TextInputLayout>(R.id.endTimeLayout)
            val startInput = row.findViewById<TextInputEditText>(R.id.startTimeInput)
            val endInput = row.findViewById<TextInputEditText>(R.id.endTimeInput)

            tvDayName.text = dayLabel
            cbEnabled.isChecked = schedule.enabled
            startInput.setText(schedule.startTime)
            endInput.setText(schedule.endTime)
            timeContainer.visibility = if (schedule.enabled) View.VISIBLE else View.GONE

            cbEnabled.setOnCheckedChangeListener { _, isChecked ->
                schedule.enabled = isChecked
                timeContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
                if (!isChecked) {
                    startLayout.error = null
                    endLayout.error = null
                }
                if (isAtLeastOneDayEnabled()) {
                    tvScheduleError.visibility = View.GONE
                }
            }

            startInput.doAfterTextChanged {
                schedule.startTime = it?.toString()?.trim().orEmpty()
                if (schedule.startTime.isNotBlank()) {
                    startLayout.error = null
                }
            }

            endInput.doAfterTextChanged {
                schedule.endTime = it?.toString()?.trim().orEmpty()
                if (schedule.endTime.isNotBlank()) {
                    endLayout.error = null
                }
            }

            dayRows[dayKey] = DayRowViews(
                enabledCheck = cbEnabled,
                startLayout = startLayout,
                endLayout = endLayout
            )
            container.addView(row)
        }
    }

    private fun isAtLeastOneDayEnabled(): Boolean {
        return vehicleViewModel.weeklySchedule.values.any { it.enabled }
    }

    override fun validateStep(showErrors: Boolean): Boolean {
        val root = view ?: return false
        val textFieldsOk = StepValidationUtils.validateRequiredFields(root, showErrors)

        val deliveryOptionOk = vehicleViewModel.pickupOnLocation || vehicleViewModel.deliveryByFee
        var deliveryFieldsOk = true
        if (vehicleViewModel.deliveryByFee) {
            val radiusOk = !vehicleViewModel.deliveryRadiusKm.isNullOrBlank()
            val feeOk = !vehicleViewModel.deliveryFee.isNullOrBlank()
            deliveryFieldsOk = radiusOk && feeOk
            if (showErrors) {
                deliveryRadiusLayout.error = if (radiusOk) null else getString(R.string.error_required)
                deliveryFeeLayout.error = if (feeOk) null else getString(R.string.error_required)
            }
        } else if (showErrors) {
            deliveryRadiusLayout.error = null
            deliveryFeeLayout.error = null
        }

        val enabledSchedules = vehicleViewModel.weeklySchedule.filterValues { it.enabled }
        val scheduleOk = enabledSchedules.isNotEmpty() && enabledSchedules.values.all {
            it.startTime.isNotBlank() && it.endTime.isNotBlank()
        }

        if (showErrors) {
            tvDeliveryOptionError.visibility = if (deliveryOptionOk) View.GONE else View.VISIBLE
            tvScheduleError.visibility = if (scheduleOk) View.GONE else View.VISIBLE

            vehicleViewModel.weeklySchedule.forEach { (dayKey, schedule) ->
                val row = dayRows[dayKey] ?: return@forEach
                if (!schedule.enabled) {
                    row.startLayout.error = null
                    row.endLayout.error = null
                } else {
                    row.startLayout.error = if (schedule.startTime.isBlank()) {
                        getString(R.string.error_required)
                    } else {
                        null
                    }
                    row.endLayout.error = if (schedule.endTime.isBlank()) {
                        getString(R.string.error_required)
                    } else {
                        null
                    }
                }
            }
        }

        return textFieldsOk && deliveryOptionOk && deliveryFieldsOk && scheduleOk
    }
}
