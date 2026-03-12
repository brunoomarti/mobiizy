package com.brunocodex.kotlinproject.activities

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.brunocodex.kotlinproject.R
import com.brunocodex.kotlinproject.services.ProfilePhotoLocalStore
import com.brunocodex.kotlinproject.services.ProfilePhotoSyncScheduler
import com.brunocodex.kotlinproject.services.ProfilePhotoSyncService
import com.brunocodex.kotlinproject.services.SupabaseStorageService
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import com.canhub.cropper.CropImageView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.URL
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseFirestore.getInstance() }

    private lateinit var tvProfileName: TextView
    private lateinit var tvProfileEmail: TextView
    private lateinit var ivProfilePhoto: ImageView
    private lateinit var tvPhotoFallback: View
    private lateinit var profilePhotoSyncBadge: View
    private lateinit var btnChangePhoto: MaterialButton

    private lateinit var phoneLayout: TextInputLayout
    private lateinit var cepLayout: TextInputLayout
    private lateinit var streetLayout: TextInputLayout
    private lateinit var numberLayout: TextInputLayout
    private lateinit var neighborhoodLayout: TextInputLayout
    private lateinit var cityLayout: TextInputLayout
    private lateinit var stateLayout: TextInputLayout

    private lateinit var phoneInput: TextInputEditText
    private lateinit var cepInput: TextInputEditText
    private lateinit var streetInput: TextInputEditText
    private lateinit var numberInput: TextInputEditText
    private lateinit var neighborhoodInput: TextInputEditText
    private lateinit var cityInput: TextInputEditText
    private lateinit var stateInput: TextInputEditText
    private lateinit var btnSavePersonalData: MaterialButton

    private lateinit var tvPasswordHint: TextView
    private lateinit var currentPasswordLayout: TextInputLayout
    private lateinit var newPasswordLayout: TextInputLayout
    private lateinit var confirmPasswordLayout: TextInputLayout
    private lateinit var currentPasswordInput: TextInputEditText
    private lateinit var newPasswordInput: TextInputEditText
    private lateinit var confirmPasswordInput: TextInputEditText
    private lateinit var btnUpdatePassword: MaterialButton

    private var hasPasswordProvider: Boolean = false
    private var photoLoadToken: Int = 0
    private var currentPhotoUrl: String? = null
    private var stateWatcherUpdating = false

    private val maxProfilePhotoSizePx = 1080
    private val profilePhotoWebpQuality = 84

    private val photoPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) openCropForProfilePhoto(uri)
    }

    private val cropPhotoLauncher =
        registerForActivityResult(CropImageContract()) { result ->
            if (result.isSuccessful) {
                val croppedUri = result.uriContent
                if (croppedUri != null) {
                    uploadProfilePhoto(croppedUri)
                } else {
                    Toast.makeText(this, R.string.personal_data_photo_crop_failed, Toast.LENGTH_LONG).show()
                }
                return@registerForActivityResult
            }
            if (result.error != null) {
                Toast.makeText(this, R.string.personal_data_photo_crop_failed, Toast.LENGTH_LONG).show()
            }
            // cancelado: usuario fechou o crop, nao faz upload
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_settings)

        applyWindowInsets()
        bindViews()
        setupInteractions()
        setupInputMasks()
        ProfilePhotoSyncScheduler.enqueueIfPending(this)
        loadProfileData()
    }

    override fun onResume() {
        super.onResume()
        renderBestAvailableProfilePhoto(currentPhotoUrl)
        ProfilePhotoSyncScheduler.enqueueIfPending(this)
    }

    private fun applyWindowInsets() {
        val root = findViewById<View>(R.id.main)
        val initialPaddingLeft = root.paddingLeft
        val initialPaddingTop = root.paddingTop
        val initialPaddingRight = root.paddingRight
        val initialPaddingBottom = root.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                initialPaddingLeft + systemBars.left,
                initialPaddingTop + systemBars.top,
                initialPaddingRight + systemBars.right,
                initialPaddingBottom + systemBars.bottom
            )
            insets
        }
    }

    private fun bindViews() {
        tvProfileName = findViewById(R.id.tvSettingsProfileName)
        tvProfileEmail = findViewById(R.id.tvSettingsProfileEmail)
        ivProfilePhoto = findViewById(R.id.ivSettingsProfilePhoto)
        tvPhotoFallback = findViewById(R.id.tvSettingsProfilePhotoFallback)
        profilePhotoSyncBadge = findViewById(R.id.settingsProfilePhotoSyncBadge)
        btnChangePhoto = findViewById(R.id.btnSettingsChangePhoto)

        phoneLayout = findViewById(R.id.settingsPhoneLayout)
        cepLayout = findViewById(R.id.settingsCepLayout)
        streetLayout = findViewById(R.id.settingsStreetLayout)
        numberLayout = findViewById(R.id.settingsNumberLayout)
        neighborhoodLayout = findViewById(R.id.settingsNeighborhoodLayout)
        cityLayout = findViewById(R.id.settingsCityLayout)
        stateLayout = findViewById(R.id.settingsStateLayout)

        phoneInput = findViewById(R.id.settingsPhoneInput)
        cepInput = findViewById(R.id.settingsCepInput)
        streetInput = findViewById(R.id.settingsStreetInput)
        numberInput = findViewById(R.id.settingsNumberInput)
        neighborhoodInput = findViewById(R.id.settingsNeighborhoodInput)
        cityInput = findViewById(R.id.settingsCityInput)
        stateInput = findViewById(R.id.settingsStateInput)
        btnSavePersonalData = findViewById(R.id.btnSettingsSavePersonalData)

        tvPasswordHint = findViewById(R.id.tvSettingsPasswordHint)
        currentPasswordLayout = findViewById(R.id.settingsCurrentPasswordLayout)
        newPasswordLayout = findViewById(R.id.settingsNewPasswordLayout)
        confirmPasswordLayout = findViewById(R.id.settingsConfirmPasswordLayout)
        currentPasswordInput = findViewById(R.id.settingsCurrentPasswordInput)
        newPasswordInput = findViewById(R.id.settingsNewPasswordInput)
        confirmPasswordInput = findViewById(R.id.settingsConfirmPasswordInput)
        btnUpdatePassword = findViewById(R.id.btnSettingsUpdatePassword)
    }

    private fun setupInteractions() {
        findViewById<View>(R.id.btnSettingsBack).setOnClickListener { finish() }
        btnChangePhoto.setOnClickListener { photoPicker.launch("image/*") }
        btnSavePersonalData.setOnClickListener { savePersonalData() }
        btnUpdatePassword.setOnClickListener {
            if (hasPasswordProvider) {
                updatePasswordWithCurrent()
            } else {
                sendPasswordResetEmail()
            }
        }
    }

    private fun setupInputMasks() {
        phoneInput.addTextChangedListener(PhoneMaskWatcher(phoneInput))
        cepInput.addTextChangedListener(SimpleMaskWatcher(cepInput, "#####-###"))
        stateInput.doAfterTextChanged { editable ->
            if (stateWatcherUpdating) return@doAfterTextChanged
            val raw = editable?.toString().orEmpty()
            val formatted = raw.uppercase(Locale.ROOT).take(2)
            if (formatted == raw) return@doAfterTextChanged
            stateWatcherUpdating = true
            stateInput.setText(formatted)
            stateInput.setSelection(formatted.length)
            stateWatcherUpdating = false
        }
    }

    private fun loadProfileData() {
        val user = auth.currentUser
        if (user == null) {
            Toast.makeText(this, R.string.personal_data_error_user_not_authenticated, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        hasPasswordProvider = user.providerData.any { it.providerId == EmailAuthProvider.PROVIDER_ID }
        configurePasswordSection(hasPasswordProvider)

        val fallbackName = resolveDisplayName(user.displayName, user.email)
        tvProfileName.text = fallbackName
        tvProfileEmail.text = user.email?.trim().orEmpty().ifBlank {
            getString(R.string.profile_email_unavailable)
        }
        val initialRemoteUrl = user.photoUrl?.toString()?.trim()?.takeIf { it.isNotBlank() }
        currentPhotoUrl = initialRemoteUrl
        renderBestAvailableProfilePhoto(initialRemoteUrl)

        db.collection("users").document(user.uid).get()
            .addOnSuccessListener { doc ->
                val profileName = doc.getString("name")?.trim().orEmpty()
                if (profileName.isNotBlank()) {
                    tvProfileName.text = profileName
                }
                phoneInput.setText(doc.getString("phone").orEmpty())
                cepInput.setText(doc.getString("cep").orEmpty())
                streetInput.setText(doc.getString("street").orEmpty())
                numberInput.setText(doc.getString("number").orEmpty())
                neighborhoodInput.setText(doc.getString("neighborhood").orEmpty())
                cityInput.setText(doc.getString("city").orEmpty())
                stateInput.setText(doc.getString("state").orEmpty())

                val photoUrl = listOf(
                    "photoUrl",
                    "photoURL",
                    "profilePhotoUrl",
                    "avatarUrl"
                ).asSequence()
                    .mapNotNull { key -> doc.getString(key)?.trim() }
                    .firstOrNull { value -> value.isNotBlank() }
                    ?: initialRemoteUrl

                currentPhotoUrl = photoUrl
                ProfilePhotoLocalStore.storeRemoteUrl(this, photoUrl)
                renderBestAvailableProfilePhoto(photoUrl)
            }
            .addOnFailureListener {
                Toast.makeText(
                    this,
                    getString(R.string.personal_data_load_failed),
                    Toast.LENGTH_LONG
                ).show()
            }
    }

    private fun configurePasswordSection(hasPasswordProvider: Boolean) {
        if (hasPasswordProvider) {
            currentPasswordLayout.visibility = View.VISIBLE
            newPasswordLayout.visibility = View.VISIBLE
            confirmPasswordLayout.visibility = View.VISIBLE
            tvPasswordHint.text = getString(R.string.personal_data_password_hint_email)
            btnUpdatePassword.text = getString(R.string.personal_data_password_update_button)
            return
        }

        currentPasswordLayout.visibility = View.GONE
        newPasswordLayout.visibility = View.GONE
        confirmPasswordLayout.visibility = View.GONE
        tvPasswordHint.text = getString(R.string.personal_data_password_hint_google)
        btnUpdatePassword.text = getString(R.string.personal_data_password_reset_button)
    }

    private fun savePersonalData() {
        val user = auth.currentUser
        if (user == null) {
            Toast.makeText(this, R.string.personal_data_error_user_not_authenticated, Toast.LENGTH_LONG).show()
            return
        }

        clearPersonalDataErrors()

        val phone = phoneInput.text?.toString()?.trim().orEmpty()
        val cep = cepInput.text?.toString()?.trim().orEmpty()
        val street = streetInput.text?.toString()?.trim().orEmpty()
        val number = numberInput.text?.toString()?.trim().orEmpty()
        val neighborhood = neighborhoodInput.text?.toString()?.trim().orEmpty()
        val city = cityInput.text?.toString()?.trim().orEmpty()
        val state = stateInput.text?.toString()?.trim()?.uppercase(Locale.ROOT).orEmpty()

        var valid = true

        val phoneDigits = phone.filter { it.isDigit() }
        if (phone.isNotBlank() && phoneDigits.length !in 10..11) {
            phoneLayout.error = getString(R.string.personal_data_error_phone_invalid)
            valid = false
        }

        val cepDigits = cep.filter { it.isDigit() }
        if (cep.isNotBlank() && cepDigits.length != 8) {
            cepLayout.error = getString(R.string.personal_data_error_cep_invalid)
            valid = false
        }

        if (state.isNotBlank() && state.length != 2) {
            stateLayout.error = getString(R.string.personal_data_error_state_invalid)
            valid = false
        }

        val hasAnyAddressField = listOf(cep, street, number, neighborhood, city, state).any { it.isNotBlank() }
        if (hasAnyAddressField) {
            if (cep.isBlank()) {
                cepLayout.error = getString(R.string.error_required)
                valid = false
            }
            if (street.isBlank()) {
                streetLayout.error = getString(R.string.error_required)
                valid = false
            }
            if (number.isBlank()) {
                numberLayout.error = getString(R.string.error_required)
                valid = false
            }
            if (neighborhood.isBlank()) {
                neighborhoodLayout.error = getString(R.string.error_required)
                valid = false
            }
            if (city.isBlank()) {
                cityLayout.error = getString(R.string.error_required)
                valid = false
            }
            if (state.isBlank()) {
                stateLayout.error = getString(R.string.error_required)
                valid = false
            }
        }

        if (!valid) {
            Toast.makeText(this, R.string.personal_data_error_address_incomplete, Toast.LENGTH_LONG).show()
            return
        }

        setSaveLoading(true)

        val payload = mapOf(
            "phone" to phone,
            "cep" to cep,
            "street" to street,
            "number" to number,
            "neighborhood" to neighborhood,
            "city" to city,
            "state" to state,
            "updatedAt" to FieldValue.serverTimestamp()
        )

        db.collection("users").document(user.uid)
            .set(payload, SetOptions.merge())
            .addOnSuccessListener {
                setSaveLoading(false)
                Toast.makeText(this, R.string.personal_data_saved_success, Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener {
                setSaveLoading(false)
                Toast.makeText(this, R.string.personal_data_save_failed, Toast.LENGTH_LONG).show()
            }
    }

    private fun clearPersonalDataErrors() {
        phoneLayout.error = null
        cepLayout.error = null
        streetLayout.error = null
        numberLayout.error = null
        neighborhoodLayout.error = null
        cityLayout.error = null
        stateLayout.error = null
    }

    private fun uploadProfilePhoto(uri: Uri) {
        val user = auth.currentUser
        if (user == null) {
            Toast.makeText(this, R.string.personal_data_error_user_not_authenticated, Toast.LENGTH_LONG).show()
            return
        }

        setPhotoLoading(true)

        lifecycleScope.launch {
            val preparedImage = runCatching {
                withContext(Dispatchers.IO) {
                    prepareImageForUpload(uri)
                }
            }

            if (preparedImage.isFailure) {
                setPhotoLoading(false)
                Toast.makeText(
                    this@SettingsActivity,
                    R.string.personal_data_photo_upload_failed,
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            val (bytes, extension) = preparedImage.getOrThrow()

            val localSaveResult = runCatching {
                withContext(Dispatchers.IO) {
                    ProfilePhotoLocalStore.saveLocalPhoto(
                        context = this@SettingsActivity,
                        photoBytes = bytes,
                        extension = extension
                    )
                }
            }

            if (localSaveResult.isFailure) {
                setPhotoLoading(false)
                Toast.makeText(
                    this@SettingsActivity,
                    R.string.personal_data_photo_upload_failed,
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            renderBestAvailableProfilePhoto(currentPhotoUrl)
            ProfilePhotoSyncScheduler.enqueueIfPending(this@SettingsActivity)

            val syncResult = runCatching {
                ProfilePhotoSyncService.syncPendingPhoto(this@SettingsActivity)
            }.getOrElse { error ->
                ProfilePhotoSyncService.SyncResult.RetryableFailure(error)
            }

            setPhotoLoading(false)

            when (syncResult) {
                is ProfilePhotoSyncService.SyncResult.Synced -> {
                    currentPhotoUrl = syncResult.remotePhotoUrl
                    renderBestAvailableProfilePhoto(syncResult.remotePhotoUrl)
                    Toast.makeText(
                        this@SettingsActivity,
                        R.string.personal_data_photo_updated,
                        Toast.LENGTH_SHORT
                    ).show()
                }

                is ProfilePhotoSyncService.SyncResult.NoPendingPhoto -> {
                    renderBestAvailableProfilePhoto(currentPhotoUrl)
                }

                is ProfilePhotoSyncService.SyncResult.RetryableFailure -> {
                    ProfilePhotoSyncScheduler.enqueueIfPending(this@SettingsActivity)
                    renderBestAvailableProfilePhoto(currentPhotoUrl)
                    Toast.makeText(
                        this@SettingsActivity,
                        R.string.personal_data_photo_saved_local_pending_sync,
                        Toast.LENGTH_LONG
                    ).show()
                }

                is ProfilePhotoSyncService.SyncResult.PermanentFailure -> {
                    ProfilePhotoSyncScheduler.enqueueIfPending(this@SettingsActivity)
                    renderBestAvailableProfilePhoto(currentPhotoUrl)
                    val message = if (!SupabaseStorageService.isReady()) {
                        val missingKeys = SupabaseStorageService.missingConfigurationKeys().joinToString(", ")
                        val detail = if (missingKeys.isBlank()) "" else " [$missingKeys]"
                        getString(R.string.personal_data_photo_upload_not_configured) + detail
                    } else {
                        getString(R.string.personal_data_photo_sync_unavailable)
                    }
                    Toast.makeText(this@SettingsActivity, message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun renderBestAvailableProfilePhoto(remoteFallbackUrl: String?) {
        val snapshot = ProfilePhotoLocalStore.getSnapshot(this)
        val localFile = snapshot.localFileOrNull()
        if (localFile != null) {
            renderLocalProfilePhoto(localFile, snapshot.pendingSync)
            return
        }

        val resolvedRemoteUrl = remoteFallbackUrl?.trim()?.takeIf { it.isNotBlank() }
            ?: snapshot.remotePhotoUrl?.trim()?.takeIf { it.isNotBlank() }
        renderProfilePhoto(resolvedRemoteUrl)
    }

    private fun renderLocalProfilePhoto(localFile: File, pendingSync: Boolean) {
        photoLoadToken++
        val bitmap = BitmapFactory.decodeFile(localFile.absolutePath)
        if (bitmap == null) {
            renderProfilePhoto(currentPhotoUrl)
            return
        }

        ivProfilePhoto.setImageBitmap(bitmap)
        ivProfilePhoto.visibility = View.VISIBLE
        tvPhotoFallback.visibility = View.GONE
        profilePhotoSyncBadge.visibility = if (pendingSync) View.VISIBLE else View.GONE
    }

    private fun renderProfilePhoto(photoUrl: String?) {
        val url = photoUrl?.trim().orEmpty()
        profilePhotoSyncBadge.visibility = View.GONE
        if (url.isBlank()) {
            photoLoadToken++
            ivProfilePhoto.setImageDrawable(null)
            ivProfilePhoto.visibility = View.GONE
            tvPhotoFallback.visibility = View.VISIBLE
            return
        }

        val requestToken = ++photoLoadToken
        ivProfilePhoto.setImageDrawable(null)
        ivProfilePhoto.visibility = View.GONE
        tvPhotoFallback.visibility = View.VISIBLE

        lifecycleScope.launch {
            val bitmap = runCatching {
                withContext(Dispatchers.IO) { loadBitmapFromUrl(url) }
            }.getOrNull()

            if (requestToken != photoLoadToken) return@launch

            if (bitmap == null) {
                ivProfilePhoto.setImageDrawable(null)
                ivProfilePhoto.visibility = View.GONE
                tvPhotoFallback.visibility = View.VISIBLE
                return@launch
            }

            ivProfilePhoto.setImageBitmap(bitmap)
            ivProfilePhoto.visibility = View.VISIBLE
            tvPhotoFallback.visibility = View.GONE
        }
    }

    private suspend fun loadBitmapFromUrl(photoUrl: String): Bitmap? {
        val bytes = runCatching {
            SupabaseStorageService.downloadBytesByPublicUrl(photoUrl)
        }.getOrElse {
            URL(photoUrl).openConnection().apply {
                connectTimeout = 15000
                readTimeout = 15000
            }.getInputStream().use { input -> input.readBytes() }
        }

        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun prepareImageForUpload(uri: Uri): Pair<ByteArray, String> {
        val sampled = decodeSampledBitmapFromUri(uri, maxProfilePhotoSizePx)
            ?: decodeBitmapFromUri(uri)
            ?: error("Nao foi possivel processar a imagem selecionada")

        val normalized = resizeBitmapIfNeeded(sampled, maxProfilePhotoSizePx)
        val webpBytes = ByteArrayOutputStream().use { output ->
            normalized.compress(Bitmap.CompressFormat.WEBP_LOSSY, profilePhotoWebpQuality, output)
            output.toByteArray()
        }

        return webpBytes to "webp"
    }

    private fun decodeBitmapFromUri(uri: Uri): Bitmap? {
        if (uri.scheme?.lowercase(Locale.ROOT) == "file") {
            return BitmapFactory.decodeFile(uri.path.orEmpty())
        }
        return contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input)
        }
    }

    private fun decodeSampledBitmapFromUri(uri: Uri, maxSizePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }

        openInputStreamForUri(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxSizePx, maxSizePx)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        return openInputStreamForUri(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        }
    }

    private fun openInputStreamForUri(uri: Uri): InputStream? {
        val scheme = uri.scheme?.lowercase(Locale.ROOT).orEmpty()
        return if (scheme == "file") {
            val path = uri.path ?: return null
            FileInputStream(File(path))
        } else {
            contentResolver.openInputStream(uri)
        }
    }

    private fun calculateInSampleSize(
        width: Int,
        height: Int,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        var inSampleSize = 1
        if (height <= reqHeight && width <= reqWidth) return inSampleSize

        var halfHeight = height / 2
        var halfWidth = width / 2

        while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
            inSampleSize *= 2
        }

        return inSampleSize.coerceAtLeast(1)
    }

    private fun resizeBitmapIfNeeded(bitmap: Bitmap, maxSizePx: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val longest = maxOf(width, height)
        if (longest <= maxSizePx) return bitmap

        val scale = maxSizePx.toFloat() / longest.toFloat()
        val targetWidth = (width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    private fun openCropForProfilePhoto(sourceUri: Uri) {
        val resolvedToolbarColor = resolveThemeColor(
            com.google.android.material.R.attr.colorSurface,
            Color.WHITE
        )
        val resolvedContentColor = resolveThemeColor(R.attr.textPrimary, Color.BLACK)

        val cropOptions = CropImageOptions().apply {
            fixAspectRatio = true
            aspectRatioX = 1
            aspectRatioY = 1
            guidelines = CropImageView.Guidelines.ON_TOUCH
            outputCompressFormat = Bitmap.CompressFormat.WEBP_LOSSY
            outputCompressQuality = profilePhotoWebpQuality
            activityTitle = getString(R.string.personal_data_photo_crop_title)
            cropMenuCropButtonTitle = getString(R.string.personal_data_photo_crop_apply)
            activityMenuIconColor = resolvedContentColor
            activityMenuTextColor = resolvedContentColor
            toolbarColor = resolvedToolbarColor
            toolbarTitleColor = resolvedContentColor
            toolbarBackButtonColor = resolvedContentColor
            toolbarTintColor = resolvedContentColor
            allowRotation = false
            allowFlipping = false
        }

        cropPhotoLauncher.launch(
            CropImageContractOptions(
                uri = sourceUri,
                cropImageOptions = cropOptions
            )
        )
    }

    private fun resolveThemeColor(attr: Int, fallbackColor: Int): Int {
        val typedValue = TypedValue()
        if (!theme.resolveAttribute(attr, typedValue, true)) {
            return fallbackColor
        }

        if (typedValue.resourceId != 0) {
            return runCatching { getColor(typedValue.resourceId) }
                .getOrDefault(fallbackColor)
        }

        return typedValue.data
    }

    private fun updatePasswordWithCurrent() {
        clearPasswordErrors()

        val user = auth.currentUser
        if (user == null) {
            Toast.makeText(this, R.string.personal_data_error_user_not_authenticated, Toast.LENGTH_LONG).show()
            return
        }
        val email = user.email?.trim().orEmpty()
        if (email.isBlank()) {
            Toast.makeText(this, R.string.personal_data_password_error_user_email, Toast.LENGTH_LONG).show()
            return
        }

        val currentPassword = currentPasswordInput.text?.toString()?.trim().orEmpty()
        val newPassword = newPasswordInput.text?.toString()?.trim().orEmpty()
        val confirmPassword = confirmPasswordInput.text?.toString()?.trim().orEmpty()

        var valid = true
        if (currentPassword.isBlank()) {
            currentPasswordLayout.error = getString(R.string.personal_data_password_error_current_required)
            valid = false
        }
        if (newPassword.isBlank()) {
            newPasswordLayout.error = getString(R.string.personal_data_password_error_new_required)
            valid = false
        }
        if (confirmPassword.isBlank()) {
            confirmPasswordLayout.error = getString(R.string.personal_data_password_error_confirm_required)
            valid = false
        }
        if (newPassword.isNotBlank() && newPassword.length < 6) {
            newPasswordLayout.error = getString(R.string.personal_data_password_error_min)
            valid = false
        }
        if (newPassword.isNotBlank() && confirmPassword.isNotBlank() && newPassword != confirmPassword) {
            confirmPasswordLayout.error = getString(R.string.personal_data_password_error_mismatch)
            valid = false
        }
        if (!valid) return

        setPasswordLoading(true)

        val credential = EmailAuthProvider.getCredential(email, currentPassword)
        user.reauthenticate(credential)
            .addOnSuccessListener {
                user.updatePassword(newPassword)
                    .addOnSuccessListener {
                        setPasswordLoading(false)
                        currentPasswordInput.setText("")
                        newPasswordInput.setText("")
                        confirmPasswordInput.setText("")
                        Toast.makeText(this, R.string.personal_data_password_updated, Toast.LENGTH_SHORT).show()
                    }
                    .addOnFailureListener { error ->
                        setPasswordLoading(false)
                        handlePasswordChangeError(error)
                    }
            }
            .addOnFailureListener { error ->
                setPasswordLoading(false)
                handlePasswordChangeError(error)
            }
    }

    private fun sendPasswordResetEmail() {
        val email = auth.currentUser?.email?.trim().orEmpty()
        if (email.isBlank()) {
            Toast.makeText(this, R.string.personal_data_password_error_user_email, Toast.LENGTH_LONG).show()
            return
        }

        setPasswordLoading(true)
        auth.sendPasswordResetEmail(email)
            .addOnSuccessListener {
                setPasswordLoading(false)
                Toast.makeText(this, R.string.personal_data_password_reset_sent, Toast.LENGTH_LONG).show()
            }
            .addOnFailureListener {
                setPasswordLoading(false)
                Toast.makeText(this, R.string.personal_data_password_error_generic, Toast.LENGTH_LONG).show()
            }
    }

    private fun handlePasswordChangeError(error: Exception) {
        val code = (error as? FirebaseAuthException)?.errorCode.orEmpty()
        if (code == "ERROR_WRONG_PASSWORD" || code == "ERROR_INVALID_CREDENTIAL") {
            currentPasswordLayout.error = getString(R.string.personal_data_password_error_wrong_current)
            return
        }
        if (code == "ERROR_WEAK_PASSWORD") {
            newPasswordLayout.error = getString(R.string.personal_data_password_error_min)
            return
        }
        Toast.makeText(this, R.string.personal_data_password_error_generic, Toast.LENGTH_LONG).show()
    }

    private fun clearPasswordErrors() {
        currentPasswordLayout.error = null
        newPasswordLayout.error = null
        confirmPasswordLayout.error = null
    }

    private fun setPhotoLoading(loading: Boolean) {
        btnChangePhoto.isEnabled = !loading
        btnChangePhoto.text = if (loading) {
            getString(R.string.personal_data_photo_uploading)
        } else {
            getString(R.string.personal_data_photo_change)
        }
    }

    private fun setSaveLoading(loading: Boolean) {
        btnSavePersonalData.isEnabled = !loading
        btnSavePersonalData.text = if (loading) {
            getString(R.string.personal_data_save_loading)
        } else {
            getString(R.string.personal_data_save_button)
        }
    }

    private fun setPasswordLoading(loading: Boolean) {
        btnUpdatePassword.isEnabled = !loading
        btnUpdatePassword.text = when {
            !loading && hasPasswordProvider -> getString(R.string.personal_data_password_update_button)
            !loading && !hasPasswordProvider -> getString(R.string.personal_data_password_reset_button)
            hasPasswordProvider -> getString(R.string.personal_data_password_updating)
            else -> getString(R.string.personal_data_password_reset_sending)
        }
    }

    private fun resolveDisplayName(rawName: String?, email: String?): String {
        val name = rawName?.trim().orEmpty()
        if (name.isNotBlank()) return name
        val emailName = email
            ?.substringBefore("@")
            ?.replace(Regex("[._-]+"), " ")
            ?.trim()
            .orEmpty()
        return emailName.ifBlank { getString(R.string.dashboard_user_fallback_name) }
    }

    private class PhoneMaskWatcher(
        private val editText: TextInputEditText
    ) : TextWatcher {
        private var isUpdating = false
        private var lastDigits = ""

        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

        override fun afterTextChanged(s: Editable?) {
            if (isUpdating) return
            val raw = s?.toString().orEmpty()
            val digits = raw.filter { it.isDigit() }
            if (digits == lastDigits) return
            lastDigits = digits

            val mask = if (digits.length > 10) "(##) #####-####" else "(##) ####-####"
            val formatted = applyMask(digits, mask)

            isUpdating = true
            editText.setText(formatted)
            editText.setSelection(formatted.length.coerceAtMost(editText.text?.length ?: 0))
            isUpdating = false
        }
    }

    private class SimpleMaskWatcher(
        private val editText: TextInputEditText,
        private val mask: String
    ) : TextWatcher {
        private var isUpdating = false
        private var lastDigits = ""

        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

        override fun afterTextChanged(s: Editable?) {
            if (isUpdating) return
            val raw = s?.toString().orEmpty()
            val digits = raw.filter { it.isDigit() }
            if (digits == lastDigits) return
            lastDigits = digits

            val formatted = applyMask(digits, mask)
            isUpdating = true
            editText.setText(formatted)
            editText.setSelection(formatted.length.coerceAtMost(editText.text?.length ?: 0))
            isUpdating = false
        }
    }

    companion object {
        private fun applyMask(digits: String, mask: String): String {
            val out = StringBuilder()
            var index = 0
            for (char in mask) {
                if (char == '#') {
                    if (index >= digits.length) break
                    out.append(digits[index])
                    index++
                } else {
                    if (index >= digits.length) break
                    out.append(char)
                }
            }
            return out.toString()
        }
    }
}
