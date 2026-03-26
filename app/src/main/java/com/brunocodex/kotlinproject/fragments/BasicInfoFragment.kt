package com.brunocodex.kotlinproject.fragments

import android.Manifest
import android.content.pm.PackageManager
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
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.brunocodex.kotlinproject.R
import com.brunocodex.kotlinproject.services.ProfilePhotoLocalStore
import com.brunocodex.kotlinproject.services.SupabaseStorageService
import com.brunocodex.kotlinproject.utils.StepValidatable
import com.brunocodex.kotlinproject.utils.StepValidationUtils
import com.brunocodex.kotlinproject.viewmodels.RegisterViewModel
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import com.canhub.cropper.CropImageView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.URL
import java.util.Locale

class BasicInfoFragment : Fragment(R.layout.fragment_basic_info), StepValidatable {

    private val registerViewModel: RegisterViewModel by activityViewModels()

    private enum class PersonType { CPF, CNPJ }

    private lateinit var personTypeGroup: RadioGroup
    private lateinit var rbCpf: RadioButton
    private lateinit var rbCnpj: RadioButton

    private lateinit var nameLayout: TextInputLayout
    private lateinit var nameInput: TextInputEditText

    private lateinit var docLayout: TextInputLayout
    private lateinit var docInput: TextInputEditText

    private lateinit var dateLayout: TextInputLayout
    private lateinit var dateInput: TextInputEditText

    private lateinit var phoneLayout: TextInputLayout
    private lateinit var phoneInput: TextInputEditText

    private lateinit var ivProfilePhoto: ImageView
    private lateinit var tvProfilePhotoFallback: View
    private lateinit var btnChangeProfilePhoto: MaterialButton
    private lateinit var tvProfilePhotoError: TextView

    private var personType: PersonType = PersonType.CPF

    private var docWatcher: TextWatcher? = null
    private var dateWatcher: TextWatcher? = null
    private var phoneWatcher: TextWatcher? = null
    private var profilePhotoLoadToken: Int = 0

    private var tvHeaderStep: TextView? = null

    private val maxProfilePhotoSizePx = 1080
    private val profilePhotoWebpQuality = 84

    private val photoPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            openCropForProfilePhoto(uri)
        }
    }

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                cameraPhotoPicker.launch(null)
            } else {
                Toast.makeText(
                    requireContext(),
                    R.string.register_profile_photo_camera_permission_denied,
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    private val cameraPhotoPicker =
        registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
            if (bitmap == null) return@registerForActivityResult
            val tempUri = saveTempBitmapAndGetUri(bitmap)
            if (tempUri == null) {
                Toast.makeText(
                    requireContext(),
                    R.string.personal_data_photo_crop_failed,
                    Toast.LENGTH_LONG
                ).show()
                return@registerForActivityResult
            }
            openCropForProfilePhoto(tempUri)
        }

    private val cropPhotoLauncher =
        registerForActivityResult(CropImageContract()) { result ->
            if (result.isSuccessful) {
                val croppedUri = result.uriContent
                if (croppedUri != null) {
                    saveProfilePhotoFromUri(croppedUri)
                } else {
                    Toast.makeText(
                        requireContext(),
                        R.string.personal_data_photo_crop_failed,
                        Toast.LENGTH_LONG
                    ).show()
                }
                return@registerForActivityResult
            }

            if (result.error != null) {
                Toast.makeText(
                    requireContext(),
                    R.string.personal_data_photo_crop_failed,
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvHeaderStep = view.findViewById(R.id.tvStepTitle)
        view.findViewById<TextView>(R.id.stepHeadline).text = getString(R.string.basic_info_step_headline)
        view.findViewById<TextView>(R.id.stepSubtitle).text =
            getString(R.string.basic_info_step_subtitle)

        bindViews(view)
        setupProfilePhotoSection()
        setupRequiredLiveClear()
        setupCommonMasks()
        setupPersonTypeToggle()

        val savedDocDigits = registerViewModel.cpf.orEmpty().filter { it.isDigit() }
        val initialType = if (savedDocDigits.length > 11) PersonType.CNPJ else PersonType.CPF

        if (initialType == PersonType.CNPJ) {
            rbCnpj.isChecked = true
        } else {
            rbCpf.isChecked = true
        }

        applyPersonType(initialType)
        restoreData()
        applyProviderLocks()
        setupDataSaving()
    }

    private fun bindViews(view: View) {
        personTypeGroup = view.findViewById(R.id.personTypeGroup)
        rbCpf = view.findViewById(R.id.rbCpf)
        rbCnpj = view.findViewById(R.id.rbCnpj)

        nameLayout = view.findViewById(R.id.nameLayout)
        nameInput = view.findViewById(R.id.nameInput)

        docLayout = view.findViewById(R.id.docLayout)
        docInput = view.findViewById(R.id.docInput)

        dateLayout = view.findViewById(R.id.dateLayout)
        dateInput = view.findViewById(R.id.dateInput)

        phoneLayout = view.findViewById(R.id.phoneLayout)
        phoneInput = view.findViewById(R.id.phoneInput)

        ivProfilePhoto = view.findViewById(R.id.ivRegisterProfilePhoto)
        tvProfilePhotoFallback = view.findViewById(R.id.tvRegisterProfilePhotoFallback)
        btnChangeProfilePhoto = view.findViewById(R.id.btnRegisterChangePhoto)
        tvProfilePhotoError = view.findViewById(R.id.tvRegisterProfilePhotoError)
    }

    private fun setupProfilePhotoSection() {
        btnChangeProfilePhoto.setOnClickListener { showProfilePhotoSourceDialog() }
        tvProfilePhotoError.isVisible = false
        renderBestAvailableProfilePhoto()
    }

    private fun showProfilePhotoSourceDialog() {
        val options = arrayOf(
            getString(R.string.register_profile_photo_source_camera),
            getString(R.string.register_profile_photo_source_gallery)
        )

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.register_profile_photo_source_dialog_title)
            .setItems(options) { _, selectedIndex ->
                when (selectedIndex) {
                    0 -> ensureCameraPermissionAndLaunch()
                    1 -> photoPicker.launch("image/*")
                }
            }
            .setNegativeButton(R.string.register_profile_photo_source_cancel, null)
            .show()
    }

    private fun ensureCameraPermissionAndLaunch() {
        val context = context ?: return
        val alreadyGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (alreadyGranted) {
            cameraPhotoPicker.launch(null)
            return
        }

        val shouldShowRationale = shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        if (shouldShowRationale) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.register_profile_photo_camera_permission_title)
                .setMessage(R.string.register_profile_photo_camera_permission_message)
                .setPositiveButton(R.string.register_profile_photo_camera_permission_confirm) { _, _ ->
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
                .setNegativeButton(R.string.register_profile_photo_source_cancel, null)
                .show()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun saveProfilePhotoFromUri(uri: Uri) {
        setProfilePhotoLoading(true)
        viewLifecycleOwner.lifecycleScope.launch {
            val preparedImage = runCatching {
                withContext(Dispatchers.IO) {
                    prepareImageForUpload(uri)
                }
            }

            setProfilePhotoLoading(false)

            if (preparedImage.isFailure) {
                Toast.makeText(
                    requireContext(),
                    R.string.personal_data_photo_upload_failed,
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            val (bytes, extension) = preparedImage.getOrThrow()
            registerViewModel.pendingProfilePhotoBytes = bytes
            registerViewModel.pendingProfilePhotoExtension = extension
            registerViewModel.profilePhotoUrl = null
            tvProfilePhotoError.isVisible = false
            renderProfilePhotoFromBytes(bytes)
        }
    }

    private fun renderBestAvailableProfilePhoto() {
        val pendingBytes = registerViewModel.pendingProfilePhotoBytes
        if (pendingBytes != null && pendingBytes.isNotEmpty()) {
            renderProfilePhotoFromBytes(pendingBytes)
            return
        }

        val user = FirebaseAuth.getInstance().currentUser
        val snapshot = user?.uid?.let { ProfilePhotoLocalStore.getSnapshot(requireContext(), it) }

        snapshot?.localFileOrNull()?.let { localFile ->
            renderProfilePhotoFromFile(localFile)
            return
        }

        val remoteUrl = registerViewModel.profilePhotoUrl?.trim()?.takeIf { it.isNotBlank() }
            ?: snapshot?.remotePhotoUrl?.trim()?.takeIf { it.isNotBlank() }
            ?: user?.photoUrl?.toString()?.trim()?.takeIf { it.isNotBlank() }

        if (remoteUrl.isNullOrBlank()) {
            showFallbackProfilePhoto()
            return
        }

        renderProfilePhotoFromRemote(remoteUrl)
    }

    private fun renderProfilePhotoFromBytes(photoBytes: ByteArray) {
        val bitmap = BitmapFactory.decodeByteArray(photoBytes, 0, photoBytes.size)
        if (bitmap == null) {
            showFallbackProfilePhoto()
            return
        }
        showProfilePhotoBitmap(bitmap)
    }

    private fun renderProfilePhotoFromFile(localFile: File) {
        val bitmap = BitmapFactory.decodeFile(localFile.absolutePath)
        if (bitmap == null) {
            showFallbackProfilePhoto()
            return
        }
        showProfilePhotoBitmap(bitmap)
    }

    private fun renderProfilePhotoFromRemote(photoUrl: String) {
        val requestToken = ++profilePhotoLoadToken
        showFallbackProfilePhoto()

        viewLifecycleOwner.lifecycleScope.launch {
            val bitmap = runCatching {
                withContext(Dispatchers.IO) { loadBitmapFromUrl(photoUrl) }
            }.getOrNull()

            if (requestToken != profilePhotoLoadToken) return@launch
            if (bitmap == null) {
                showFallbackProfilePhoto()
                return@launch
            }

            showProfilePhotoBitmap(bitmap)
        }
    }

    private fun showProfilePhotoBitmap(bitmap: Bitmap) {
        ivProfilePhoto.setImageBitmap(bitmap)
        ivProfilePhoto.isVisible = true
        tvProfilePhotoFallback.isVisible = false
    }

    private fun showFallbackProfilePhoto() {
        ivProfilePhoto.setImageDrawable(null)
        ivProfilePhoto.isVisible = false
        tvProfilePhotoFallback.isVisible = true
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

    private fun setProfilePhotoLoading(loading: Boolean) {
        btnChangeProfilePhoto.isEnabled = !loading
        btnChangeProfilePhoto.text = if (loading) {
            getString(R.string.personal_data_photo_uploading)
        } else {
            getString(R.string.personal_data_photo_change)
        }
    }

    private fun saveTempBitmapAndGetUri(bitmap: Bitmap): Uri? {
        return runCatching {
            val file = File.createTempFile("register_profile_camera_", ".jpg", requireContext().cacheDir)
            file.outputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)
            }
            Uri.fromFile(file)
        }.getOrNull()
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
        if (!requireContext().theme.resolveAttribute(attr, typedValue, true)) {
            return fallbackColor
        }

        if (typedValue.resourceId != 0) {
            return runCatching { requireContext().getColor(typedValue.resourceId) }
                .getOrDefault(fallbackColor)
        }

        return typedValue.data
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
        return requireContext().contentResolver.openInputStream(uri)?.use { input ->
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
            requireContext().contentResolver.openInputStream(uri)
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

        val halfHeight = height / 2
        val halfWidth = width / 2
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

    private fun restoreData() {
        nameInput.setText(registerViewModel.name)
        phoneInput.setText(registerViewModel.phone)
        docInput.setText(registerViewModel.cpf)
        dateInput.setText(registerViewModel.date)
    }

    private fun setupDataSaving() {
        nameInput.doAfterTextChanged { registerViewModel.name = it.toString() }
        phoneInput.doAfterTextChanged { registerViewModel.phone = it.toString() }
        docInput.doAfterTextChanged { registerViewModel.cpf = it.toString() }
        dateInput.doAfterTextChanged { registerViewModel.date = it.toString() }
    }

    private fun applyProviderLocks() {
        if (registerViewModel.lockNameFromProvider) {
            nameInput.setText(registerViewModel.name.orEmpty())
            nameInput.isEnabled = false
            nameInput.isFocusable = false
            nameInput.isFocusableInTouchMode = false
            nameInput.isClickable = false
            nameLayout.helperText = getString(R.string.helper_prefilled_google_account)
        } else {
            nameInput.isEnabled = true
            nameInput.isFocusable = true
            nameInput.isFocusableInTouchMode = true
            nameInput.isClickable = true
            nameLayout.helperText = null
        }
    }

    private fun setupPersonTypeToggle() {
        personTypeGroup.setOnCheckedChangeListener { _, checkedId ->
            val type = when (checkedId) {
                R.id.rbCnpj -> PersonType.CNPJ
                else -> PersonType.CPF
            }
            applyPersonType(type)
        }
    }

    private fun applyPersonType(type: PersonType) {
        personType = type

        if (type == PersonType.CPF) {
            nameLayout.hint = getString(R.string.hint_full_name)
            dateLayout.hint = getString(R.string.hint_birth_date)
            docLayout.hint = getString(R.string.hint_cpf)
            docInput.setText("")
            docInput.filters = arrayOf(android.text.InputFilter.LengthFilter(14))
        } else {
            nameLayout.hint = getString(R.string.hint_legal_name)
            dateLayout.hint = getString(R.string.hint_opening_date)
            docLayout.hint = getString(R.string.hint_cnpj)
            docInput.setText("")
            docInput.filters = arrayOf(android.text.InputFilter.LengthFilter(18))
        }

        clearError(docLayout)

        docWatcher?.let { docInput.removeTextChangedListener(it) }
        docWatcher = if (type == PersonType.CPF) {
            FixedMaskWatcher(docInput, "###.###.###-##")
        } else {
            FixedMaskWatcher(docInput, "##.###.###/####-##")
        }
        docInput.addTextChangedListener(docWatcher)
        docInput.doAfterTextChanged { validateDocLive() }
    }

    private fun setupCommonMasks() {
        dateWatcher = SimpleMaskWatcher(dateInput, "##/##/####").also { dateInput.addTextChangedListener(it) }
        phoneWatcher = PhoneMaskWatcher(phoneInput).also { phoneInput.addTextChangedListener(it) }
    }

    private fun setupRequiredLiveClear() {
        attachRequiredLiveClear(nameLayout, nameInput)
        attachRequiredLiveClear(dateLayout, dateInput)
        attachRequiredLiveClear(phoneLayout, phoneInput)

        docInput.doAfterTextChanged {
            val digits = docInput.text?.toString()?.filter { it.isDigit() }.orEmpty()
            if (digits.isNotEmpty()) clearError(docLayout)
        }
    }

    private fun attachRequiredLiveClear(layout: TextInputLayout, input: TextInputEditText) {
        input.doAfterTextChanged {
            val value = input.text?.toString()?.trim().orEmpty()
            if (value.isNotEmpty()) clearError(layout)
        }
    }

    private fun clearError(layout: TextInputLayout) {
        layout.error = null
        layout.isErrorEnabled = false
    }

    private fun validateDocLive() {
        val digits = docInput.text?.toString()?.filter { it.isDigit() }.orEmpty()
        if (digits.isEmpty()) {
            clearError(docLayout)
            return
        }

        val expected = if (personType == PersonType.CPF) 11 else 14
        if (digits.length < expected) {
            clearError(docLayout)
            return
        }

        val ok = if (personType == PersonType.CPF) isValidCPF(digits) else isValidCNPJ(digits)
        if (!ok) {
            docLayout.error = if (personType == PersonType.CPF) {
                getString(R.string.error_invalid_cpf)
            } else {
                getString(R.string.error_invalid_cnpj)
            }
            docLayout.isErrorEnabled = true
        } else {
            clearError(docLayout)
        }
    }

    override fun validateStep(showErrors: Boolean): Boolean {
        val root = view ?: return false
        val baseOk = StepValidationUtils.validateRequiredFields(root, showErrors)

        val digits = docInput.text?.toString()?.filter { it.isDigit() }.orEmpty()
        val expected = if (personType == PersonType.CPF) 11 else 14
        val docOk = digits.length == expected &&
            (if (personType == PersonType.CPF) isValidCPF(digits) else isValidCNPJ(digits))

        if (showErrors) {
            if (!docOk) {
                docLayout.error = if (personType == PersonType.CPF) {
                    getString(R.string.error_provide_valid_cpf)
                } else {
                    getString(R.string.error_provide_valid_cnpj)
                }
                docLayout.isErrorEnabled = true
            } else {
                clearError(docLayout)
            }
        }

        tvProfilePhotoError.isVisible = false
        return baseOk && docOk
    }

    private fun isValidCPF(cpf: String): Boolean {
        if (cpf.length != 11) return false
        if (cpf.all { it == cpf[0] }) return false

        val digits = cpf.map { it.digitToInt() }
        val dv1 = run {
            var sum = 0
            for (i in 0..8) sum += digits[i] * (10 - i)
            val mod = sum % 11
            if (mod < 2) 0 else 11 - mod
        }
        val dv2 = run {
            var sum = 0
            for (i in 0..9) sum += (if (i == 9) dv1 else digits[i]) * (11 - i)
            val mod = sum % 11
            if (mod < 2) 0 else 11 - mod
        }
        return digits[9] == dv1 && digits[10] == dv2
    }

    private fun isValidCNPJ(cnpj: String): Boolean {
        if (cnpj.length != 14) return false
        if (cnpj.all { it == cnpj[0] }) return false

        val digits = cnpj.map { it.digitToInt() }
        val weights1 = intArrayOf(5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2)
        val weights2 = intArrayOf(6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2)

        val dv1 = run {
            var sum = 0
            for (i in 0..11) sum += digits[i] * weights1[i]
            val mod = sum % 11
            if (mod < 2) 0 else 11 - mod
        }
        val dv2 = run {
            var sum = 0
            for (i in 0..12) sum += (if (i == 12) dv1 else digits[i]) * weights2[i]
            val mod = sum % 11
            if (mod < 2) 0 else 11 - mod
        }
        return digits[12] == dv1 && digits[13] == dv2
    }

    private class FixedMaskWatcher(
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

            val formatted = format(digits, mask)
            isUpdating = true
            editText.setText(formatted)
            editText.setSelection(formatted.length.coerceAtMost(editText.text?.length ?: 0))
            isUpdating = false
        }

        private fun format(digits: String, mask: String): String {
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
            val formatted = format(digits, mask)
            isUpdating = true
            editText.setText(formatted)
            editText.setSelection(formatted.length.coerceAtMost(editText.text?.length ?: 0))
            isUpdating = false
        }

        private fun format(digits: String, mask: String): String {
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

    override fun onDestroyView() {
        super.onDestroyView()
        tvHeaderStep = null
    }
}
