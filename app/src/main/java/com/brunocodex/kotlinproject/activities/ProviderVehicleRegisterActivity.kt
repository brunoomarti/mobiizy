package com.brunocodex.kotlinproject.activities

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.brunocodex.kotlinproject.R
import com.brunocodex.kotlinproject.adapters.VehicleRegisterPagerAdapter
import com.brunocodex.kotlinproject.services.SQLiteConfiguration
import com.brunocodex.kotlinproject.services.VehicleSyncRepository
import com.brunocodex.kotlinproject.utils.StepHeaderBindable
import com.brunocodex.kotlinproject.utils.StepValidatable
import com.brunocodex.kotlinproject.utils.VehicleStepNavigator
import com.brunocodex.kotlinproject.viewmodels.VehicleRegisterViewModel
import com.google.firebase.auth.FirebaseAuth
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.launch
import org.json.JSONObject

class ProviderVehicleRegisterActivity : AppCompatActivity(), VehicleStepNavigator {

    private val vehicleViewModel: VehicleRegisterViewModel by viewModels()
    private val draftPrefs by lazy { getSharedPreferences("vehicle_register_draft_prefs", MODE_PRIVATE) }
    private val vehicleSyncRepository by lazy { VehicleSyncRepository(this) }
    private val firebaseAuth by lazy { FirebaseAuth.getInstance() }
    private var shouldSaveDraft = true

    private enum class StepState { PENDING, CURRENT, DONE, ALERT }

    private lateinit var viewPager: ViewPager2
    private lateinit var steps: List<View>
    private lateinit var lines: List<View>
    private lateinit var btnBack: Button
    private lateinit var btnNext: Button
    private lateinit var progressBtnNext: CircularProgressIndicator
    private lateinit var tvStepTitle: TextView
    private lateinit var stepStates: MutableList<StepState>
    private var isSubmitting = false

    companion object {
        private const val LOCAL_DRAFT_KEY = "provider_vehicle_register_draft"
        const val EXTRA_INITIAL_DRAFT_JSON = "extra_initial_draft_json"
        private const val TAG = "ProviderVehicleRegister"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_provider_vehicle_register)

        tvStepTitle = findViewById(R.id.tvStepTitle)

        val btnCancel: TextView = findViewById(R.id.btnCancel)
        btnCancel.setOnClickListener { showCancelDialog() }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        restoreInitialDraftFromIntent()
        setupUi()
        syncPendingVehiclesSilently()
    }

    override fun onStop() {
        super.onStop()
        if (shouldSaveDraft) saveDraftProgress()
    }

    override fun goToStep(stepIndex: Int) {
        if (!::viewPager.isInitialized) return
        val safeStep = stepIndex.coerceIn(0, (viewPager.adapter?.itemCount ?: 1) - 1)
        vehicleViewModel.currentStep = safeStep
        viewPager.setCurrentItem(safeStep, true)
    }

    private fun setupUi() {
        viewPager = findViewById(R.id.view_pager)

        val adapter = VehicleRegisterPagerAdapter(this)
        viewPager.adapter = adapter
        viewPager.isUserInputEnabled = false
        viewPager.offscreenPageLimit = adapter.itemCount

        steps = listOf(
            findViewById(R.id.step1),
            findViewById(R.id.step2),
            findViewById(R.id.step3),
            findViewById(R.id.step4),
            findViewById(R.id.step5),
            findViewById(R.id.step6),
            findViewById(R.id.step7)
        )

        lines = listOf(
            findViewById(R.id.line1),
            findViewById(R.id.line2),
            findViewById(R.id.line3),
            findViewById(R.id.line4),
            findViewById(R.id.line5),
            findViewById(R.id.line6)
        )

        btnBack = findViewById(R.id.btnBack)
        btnNext = findViewById(R.id.btnNext)
        progressBtnNext = findViewById(R.id.progressBtnNext)
        stepStates = MutableList(adapter.itemCount) { StepState.PENDING }

        val safeStep = vehicleViewModel.currentStep.coerceIn(0, adapter.itemCount - 1)
        viewPager.setCurrentItem(safeStep, false)

        btnBack.setOnClickListener {
            if (isSubmitting) return@setOnClickListener
            val previous = (viewPager.currentItem - 1).coerceAtLeast(0)
            vehicleViewModel.currentStep = previous
            viewPager.setCurrentItem(previous, true)
        }

        btnNext.setOnClickListener {
            if (isSubmitting) return@setOnClickListener
            val current = viewPager.currentItem
            val lastIndex = (viewPager.adapter?.itemCount ?: 1) - 1

            if (!validateStep(current, showErrors = true)) {
                markAlert(current)
                renderStepper(current)
                return@setOnClickListener
            } else {
                clearAlert(current)
            }

            if (current == lastIndex) {
                val firstInvalid = findFirstInvalidStep()
                if (firstInvalid != -1) {
                    markAlert(firstInvalid)
                    vehicleViewModel.currentStep = firstInvalid
                    viewPager.setCurrentItem(firstInvalid, true)
                    renderStepper(firstInvalid)
                    validateStep(firstInvalid, showErrors = true)
                    return@setOnClickListener
                }

                submitVehicle()
                return@setOnClickListener
            }

            val next = (current + 1).coerceAtMost(lastIndex)
            vehicleViewModel.currentStep = next
            saveDraftProgress()
            viewPager.setCurrentItem(next, true)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isSubmitting) return
                if (viewPager.currentItem == 0) {
                    showCancelDialog()
                } else {
                    val previous = (viewPager.currentItem - 1).coerceAtLeast(0)
                    vehicleViewModel.currentStep = previous
                    viewPager.setCurrentItem(previous, true)
                }
            }
        })

        renderStepper(viewPager.currentItem)
        updateButtons(viewPager.currentItem)
        notifyCurrentStepToFragment(viewPager.currentItem)

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                vehicleViewModel.currentStep = position
                renderStepper(position)
                updateButtons(position)
                notifyCurrentStepToFragment(position)
            }
        })
    }

    private fun showCancelDialog() {
        val hasProgress = vehicleViewModel.hasAnyProgress()
        AlertDialog.Builder(this)
            .setTitle(R.string.vehicle_register_cancel_dialog_title)
            .setMessage(
                if (hasProgress) {
                    getString(R.string.vehicle_register_cancel_dialog_message_with_draft)
                } else {
                    getString(R.string.vehicle_register_cancel_dialog_message_without_draft)
                }
            )
            .setNegativeButton(R.string.vehicle_register_cancel_dialog_action_continue, null)
            .setPositiveButton(R.string.vehicle_register_cancel_dialog_action_save_and_exit) { _, _ ->
                saveDraftProgress()
                finish()
            }
            .setNeutralButton(R.string.vehicle_register_cancel_dialog_action_discard) { _, _ ->
                discardDraftAndExit()
            }
            .show()
    }

    private fun submitVehicle() {
        if (isSubmitting) return

        val draftMode = vehicleViewModel.availabilityMode == VehicleRegisterViewModel.AVAILABILITY_DRAFT
        val plate = vehicleViewModel.plate?.trim().orEmpty()
        if (plate.isBlank()) {
            Toast.makeText(
                this,
                getString(R.string.vehicle_error_fill_plate_before_upload),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val ownerId = firebaseAuth.currentUser?.uid.orEmpty().ifBlank { "anonymous" }
        val status = if (draftMode) {
            SQLiteConfiguration.STATUS_DRAFT
        } else {
            SQLiteConfiguration.STATUS_PUBLISHED
        }
        val payloadJson = vehicleViewModel.toDraftJson().toString()

        setSubmitLoading(true)

        lifecycleScope.launch {
            val result = runCatching {
                vehicleSyncRepository.saveOrUpdateVehicle(
                    ownerId = ownerId,
                    plate = plate,
                    status = status,
                    payloadJson = payloadJson
                )
            }

            result.onSuccess { sync ->
                if (draftMode) {
                    saveDraftProgress()
                    val message = if (sync.remoteSynced) {
                        getString(R.string.vehicle_register_draft_saved)
                    } else {
                        getString(R.string.vehicle_register_draft_saved_pending_sync)
                    }
                    Toast.makeText(this@ProviderVehicleRegisterActivity, message, Toast.LENGTH_LONG).show()
                    finish()
                    return@onSuccess
                }

                shouldSaveDraft = false
                clearLocalDraft()
                val message = if (sync.remoteSynced) {
                    getString(R.string.vehicle_register_publish_success)
                } else {
                    getString(R.string.vehicle_register_publish_saved_pending_sync)
                }
                Toast.makeText(this@ProviderVehicleRegisterActivity, message, Toast.LENGTH_LONG).show()
                finish()
            }.onFailure { throwable ->
                Log.e(TAG, "Failed to persist vehicle", throwable)
                Toast.makeText(
                    this@ProviderVehicleRegisterActivity,
                    getString(R.string.vehicle_register_save_failed),
                    Toast.LENGTH_LONG
                ).show()
                setSubmitLoading(false)
            }
        }
    }

    private fun validateStep(index: Int, showErrors: Boolean): Boolean {
        val fragment = supportFragmentManager.findFragmentByTag("f$index")
        val validatable = fragment as? StepValidatable
        return validatable?.validateStep(showErrors) ?: false
    }

    private fun findFirstInvalidStep(): Int {
        val count = viewPager.adapter?.itemCount ?: 0
        for (i in 0 until count) {
            val isValid = validateStep(i, showErrors = false)
            if (!isValid) return i
        }
        return -1
    }

    private fun markAlert(index: Int) {
        stepStates[index] = StepState.ALERT
    }

    private fun clearAlert(index: Int) {
        if (stepStates[index] == StepState.ALERT) {
            stepStates[index] = StepState.PENDING
        }
    }

    private fun renderStepper(currentIndex: Int) {
        for (i in steps.indices) {
            val state = when {
                stepStates[i] == StepState.ALERT -> StepState.ALERT
                i < currentIndex -> StepState.DONE
                i == currentIndex -> StepState.CURRENT
                else -> StepState.PENDING
            }

            when (state) {
                StepState.DONE -> steps[i].setBackgroundResource(R.drawable.step_done)
                StepState.CURRENT -> steps[i].setBackgroundResource(R.drawable.step_current)
                StepState.PENDING -> steps[i].setBackgroundResource(R.drawable.step_pending)
                StepState.ALERT -> steps[i].setBackgroundResource(R.drawable.step_alert)
            }
        }

        for (i in lines.indices) {
            val done = currentIndex >= (i + 1)
            val alertLine = (stepStates[i] == StepState.ALERT) || (stepStates[i + 1] == StepState.ALERT)

            lines[i].setBackgroundResource(
                when {
                    alertLine -> R.drawable.line_alert
                    done -> R.drawable.line_done
                    else -> R.drawable.line_pending
                }
            )
        }
    }

    private fun updateButtons(currentIndex: Int) {
        if (isSubmitting) return

        val lastIndex = (viewPager.adapter?.itemCount ?: 1) - 1

        btnBack.isEnabled = currentIndex > 0
        btnNext.text = when {
            currentIndex != lastIndex -> getString(R.string.register_next)
            vehicleViewModel.availabilityMode == VehicleRegisterViewModel.AVAILABILITY_DRAFT ->
                getString(R.string.vehicle_register_save_draft)
            else -> getString(R.string.vehicle_register_publish)
        }

        val total = viewPager.adapter?.itemCount ?: 1
        tvStepTitle.text = getString(R.string.vehicle_register_step_of_total, currentIndex + 1, total)
    }

    private fun setSubmitLoading(loading: Boolean) {
        isSubmitting = loading
        if (loading) {
            btnNext.isEnabled = false
            btnBack.isEnabled = false
            btnNext.text = ""
            progressBtnNext.visibility = View.VISIBLE
            return
        }

        progressBtnNext.visibility = View.GONE
        updateButtons(viewPager.currentItem)
    }

    private fun notifyCurrentStepToFragment(position: Int) {
        val total = viewPager.adapter?.itemCount ?: 1
        val fragment = supportFragmentManager.findFragmentByTag("f$position")
        (fragment as? StepHeaderBindable)?.onStepChanged(position, total)
    }

    private fun saveDraftProgress() {
        vehicleViewModel.currentStep =
            if (::viewPager.isInitialized) viewPager.currentItem else vehicleViewModel.currentStep

        val json = vehicleViewModel.toDraftJson()
        draftPrefs.edit().putString(LOCAL_DRAFT_KEY, json.toString()).apply()
    }

    private fun loadLocalDraft() {
        val raw = draftPrefs.getString(LOCAL_DRAFT_KEY, null) ?: return
        runCatching { JSONObject(raw) }
            .onSuccess { vehicleViewModel.restoreFromJson(it) }
    }

    private fun restoreInitialDraftFromIntent() {
        val raw = intent?.getStringExtra(EXTRA_INITIAL_DRAFT_JSON).orEmpty()
        if (raw.isBlank()) return

        runCatching { JSONObject(raw) }
            .onSuccess { vehicleViewModel.restoreFromJson(it) }
            .onFailure { Log.w(TAG, "Failed to parse initial draft from intent", it) }
    }

    private fun discardDraftAndExit() {
        shouldSaveDraft = false
        clearLocalDraft()

        val isDraftMode = vehicleViewModel.availabilityMode == VehicleRegisterViewModel.AVAILABILITY_DRAFT
        val plate = vehicleViewModel.plate?.trim().orEmpty()
        if (!isDraftMode || plate.isBlank()) {
            finish()
            return
        }

        val ownerId = firebaseAuth.currentUser?.uid.orEmpty().ifBlank { "anonymous" }
        lifecycleScope.launch {
            runCatching {
                vehicleSyncRepository.deleteVehicle(ownerId = ownerId, plate = plate)
            }.onFailure {
                Log.w(TAG, "Failed to delete draft vehicle during discard", it)
            }
            finish()
        }
    }

    private fun clearLocalDraft() {
        draftPrefs.edit().remove(LOCAL_DRAFT_KEY).apply()
    }

    private fun syncPendingVehiclesSilently() {
        val ownerId = firebaseAuth.currentUser?.uid.orEmpty().ifBlank { "anonymous" }
        lifecycleScope.launch {
            runCatching { vehicleSyncRepository.syncPendingVehicles(ownerId) }
                .onFailure { Log.w(TAG, "Pending vehicle sync failed", it) }
        }
    }
}
