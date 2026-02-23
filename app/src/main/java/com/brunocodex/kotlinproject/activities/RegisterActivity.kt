package com.brunocodex.kotlinproject.activities

import android.content.Intent
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
import androidx.viewpager2.widget.ViewPager2
import com.brunocodex.kotlinproject.R
import com.brunocodex.kotlinproject.adapters.ViewPagerAdapter
import com.brunocodex.kotlinproject.fragments.ProfileSelectionFragment
import com.brunocodex.kotlinproject.utils.StepHeaderBindable
import com.brunocodex.kotlinproject.utils.StepValidatable
import com.brunocodex.kotlinproject.viewmodels.RegisterViewModel
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import org.json.JSONObject

class RegisterActivity : AppCompatActivity() {

    private val registerViewModel: RegisterViewModel by viewModels()

    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    private var shouldSaveDraft = true
    private val draftPrefs by lazy { getSharedPreferences("register_draft_prefs", MODE_PRIVATE) }

    private enum class StepState { PENDING, CURRENT, DONE, ALERT }

    private lateinit var viewPager: ViewPager2

    private lateinit var steps: List<View>
    private lateinit var lines: List<View>

    private lateinit var btnBack: Button
    private lateinit var btnNext: Button
    private lateinit var tvStepTitle: TextView

    private lateinit var stepStates: MutableList<StepState>

    companion object {
        private const val LOCAL_DRAFT_KEY = "local_register_draft"
        private const val TAG = "RegisterActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_register)

        tvStepTitle = findViewById(R.id.tvStepTitle)

        val btnCancel: TextView = findViewById(R.id.btnCancel)
        btnCancel.setOnClickListener {
            showCancelDialog()
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Primeiro garante sessão e carrega draft.
        // Só depois monta o pager, assim os fragments já nascem com os dados restaurados.
        ensureDraftSessionAndInitUi()
    }

    override fun onStop() {
        super.onStop()
        if (shouldSaveDraft) {
            saveDraftProgress()
        }
    }

    private fun ensureDraftSessionAndInitUi() {
        val currentUser = auth.currentUser

        if (currentUser == null) {
            // Cadastro funciona sem sessao anonima; nesse caso o rascunho em nuvem
            // so fica disponivel apos autenticacao.
            applyAuthIdentityDefaults(null)
            loadLocalDraft()?.let { registerViewModel.restoreFromMap(it) }
            setupRegisterUi()
        } else {
            loadDraftThenSetupUi()
        }
    }

    private fun loadDraftThenSetupUi() {
        val uid = auth.currentUser?.uid

        // Se por algum motivo não tiver usuário, segue com UI limpa
        if (uid == null) {
            applyAuthIdentityDefaults(null)
            setupRegisterUi()
            return
        }

        db.collection("register_drafts").document(uid).get()
            .addOnSuccessListener { doc ->
                val data = doc.data
                if (data != null) {
                    registerViewModel.restoreFromMap(data)
                } else {
                    loadLocalDraft()?.let { registerViewModel.restoreFromMap(it) }
                }
                applyAuthIdentityDefaults(auth.currentUser)
                setupRegisterUi()
            }
            .addOnFailureListener {
                // Se der erro de rede, segue mesmo assim com UI
                loadLocalDraft()?.let { registerViewModel.restoreFromMap(it) }
                applyAuthIdentityDefaults(auth.currentUser)
                setupRegisterUi()
            }
    }

    private fun setupRegisterUi() {
        viewPager = findViewById(R.id.view_pager)

        val adapter = ViewPagerAdapter(this)
        viewPager.adapter = adapter
        viewPager.isUserInputEnabled = false
        viewPager.offscreenPageLimit = adapter.itemCount

        // Stepper (4 steps / 3 lines)
        steps = listOf(
            findViewById(R.id.step1),
            findViewById(R.id.step2),
            findViewById(R.id.step3),
            findViewById(R.id.step4)
        )

        lines = listOf(
            findViewById(R.id.line1),
            findViewById(R.id.line2),
            findViewById(R.id.line3)
        )

        btnBack = findViewById(R.id.btnBack)
        btnNext = findViewById(R.id.btnNext)

        stepStates = MutableList(adapter.itemCount) { StepState.PENDING }

        // Restaurar etapa salva
        val safeStep = registerViewModel.currentStep.coerceIn(0, (adapter.itemCount - 1).coerceAtLeast(0))
        viewPager.setCurrentItem(safeStep, false)

        btnBack.setOnClickListener {
            val prev = (viewPager.currentItem - 1).coerceAtLeast(0)
            registerViewModel.currentStep = prev
            viewPager.setCurrentItem(prev, true)
        }

        btnNext.setOnClickListener {
            val current = viewPager.currentItem
            val lastIndex = (viewPager.adapter?.itemCount ?: 1) - 1

            // 1) valida step atual antes de sair dele
            if (!validateStep(current, showErrors = true)) {
                markAlert(current)
                renderStepper(current)
                return@setOnClickListener
            } else {
                clearAlert(current)
            }

            // 2) Se é o último, tenta finalizar (validando tudo)
            if (current == lastIndex) {
                val firstInvalid = findFirstInvalidStep()
                if (firstInvalid != -1) {
                    markAlert(firstInvalid)
                    registerViewModel.currentStep = firstInvalid
                    viewPager.setCurrentItem(firstInvalid, true)
                    renderStepper(firstInvalid)

                    // Mostra erros no step problemático
                    validateStep(firstInvalid, showErrors = true)
                    return@setOnClickListener
                }

                // ✅ Tudo ok
                submitForm()
                return@setOnClickListener
            }

            // 3) Avança normalmente e salva draft
            val next = (current + 1).coerceAtMost(lastIndex)
            registerViewModel.currentStep = next
            saveDraftProgress()
            viewPager.setCurrentItem(next, true)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (viewPager.currentItem == 0) {
                    showCancelDialog()
                } else {
                    val prev = (viewPager.currentItem - 1).coerceAtLeast(0)
                    registerViewModel.currentStep = prev
                    viewPager.setCurrentItem(prev, true)
                }
            }
        })

        // Estado inicial
        renderStepper(viewPager.currentItem)
        updateButtons(viewPager.currentItem)
        notifyCurrentStepToFragment(viewPager.currentItem)

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                registerViewModel.currentStep = position
                renderStepper(position)
                updateButtons(position)
                notifyCurrentStepToFragment(position)
            }
        })
    }

    private fun showCancelDialog() {
        val hasTypedSomething = userHasProgress()

        AlertDialog.Builder(this)
            .setTitle("Cancelar cadastro?")
            .setMessage(
                if (hasTypedSomething)
                    "Se você sair agora, o rascunho será mantido e você pode continuar depois."
                else
                    "Você quer voltar para o login?"
            )
            .setNegativeButton("Continuar cadastrando", null)
            .setPositiveButton("Voltar para login") { _, _ ->
                goToLogin(clearAnonymous = false) // mantém rascunho
            }
            .setNeutralButton("Descartar rascunho") { _, _ ->
                discardDraftAndGoToLogin()
            }
            .show()
    }

    private fun goToLogin(clearAnonymous: Boolean) {
        if (clearAnonymous) {
            auth.signOut()
        }

        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun discardDraftAndGoToLogin() {
        val uid = auth.currentUser?.uid
        shouldSaveDraft = false
        clearLocalDraft()

        if (uid == null) {
            auth.signOut()
            goToLogin(clearAnonymous = false)
            return
        }

        // Se for usuário anônimo, apagamos o rascunho e encerramos a sessão
        db.collection("register_drafts").document(uid).delete()
            .addOnCompleteListener {
                auth.currentUser?.delete()
                    ?.addOnCompleteListener {
                        auth.signOut()
                        goToLogin(clearAnonymous = false)
                    }
            }
    }

    private fun userHasProgress(): Boolean {
        return registerViewModel.profileType != null ||
                !registerViewModel.name.isNullOrBlank() ||
                !registerViewModel.email.isNullOrBlank() ||
                !registerViewModel.phone.isNullOrBlank() ||
                !registerViewModel.cpf.isNullOrBlank() ||
                !registerViewModel.cep.isNullOrBlank() ||
                !registerViewModel.street.isNullOrBlank() ||
                !registerViewModel.number.isNullOrBlank() ||
                !registerViewModel.city.isNullOrBlank() ||
                viewPager.currentItem > 0
    }

    private fun submitForm() {
        val currentUser = auth.currentUser
        val email = registerViewModel.email?.trim().orEmpty()
            .ifBlank { currentUser?.email.orEmpty() }
        val password = registerViewModel.password.orEmpty()
        val needPassword = currentUser == null || currentUser.isAnonymous

        if (email.isBlank()) {
            Toast.makeText(this, "Preencha e-mail para finalizar", Toast.LENGTH_LONG).show()
            return
        }
        if (needPassword && password.isBlank()) {
            Toast.makeText(this, "Preencha senha para finalizar", Toast.LENGTH_LONG).show()
            return
        }

        registerViewModel.email = email

        // Salva draft antes de finalizar (seguranca extra)
        saveDraftProgress()

        if (currentUser == null) {
            auth.createUserWithEmailAndPassword(email, password)
                .addOnSuccessListener { result ->
                    val uid = result.user?.uid
                    if (uid == null) {
                        Toast.makeText(this, "Erro ao finalizar cadastro.", Toast.LENGTH_LONG).show()
                        return@addOnSuccessListener
                    }
                    persistFinalProfile(uid)
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "Erro ao criar conta: ${humanizeAuthError(e)}", Toast.LENGTH_LONG).show()
                }
            return
        }

        if (currentUser.isAnonymous) {
            val credential = EmailAuthProvider.getCredential(email, password)

            currentUser.linkWithCredential(credential)
                .addOnSuccessListener { result ->
                    val uid = result.user?.uid
                    if (uid == null) {
                        Toast.makeText(this, "Erro ao finalizar cadastro.", Toast.LENGTH_LONG).show()
                        return@addOnSuccessListener
                    }
                    persistFinalProfile(uid)
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "Erro ao finalizar cadastro: ${humanizeAuthError(e)}", Toast.LENGTH_LONG).show()
                }
        } else {
            // Caso o usuario ja seja real (ex.: fluxo de perfil incompleto)
            persistFinalProfile(currentUser.uid)
        }
    }

    private fun applyAuthIdentityDefaults(user: FirebaseUser?) {
        val isGoogle = user?.providerData?.any { it.providerId == GoogleAuthProvider.PROVIDER_ID } == true

        registerViewModel.isGoogleAccount = isGoogle
        registerViewModel.passwordRequired = !isGoogle

        val providerEmail = user?.email?.trim().orEmpty()
        val providerName = user?.displayName?.trim().orEmpty()

        registerViewModel.lockEmailFromProvider = isGoogle && providerEmail.isNotBlank()
        registerViewModel.lockNameFromProvider = isGoogle && providerName.isNotBlank()

        if (registerViewModel.lockEmailFromProvider) {
            registerViewModel.email = providerEmail
        }
        if (registerViewModel.lockNameFromProvider) {
            registerViewModel.name = providerName
        }

        if (!registerViewModel.passwordRequired) {
            registerViewModel.password = null
        }
    }

    private fun persistFinalProfile(uid: String) {
        val userData = registerViewModel.toUserMap()
            .filterValues { it != null }
            .toMutableMap()

        userData["updatedAt"] = FieldValue.serverTimestamp()
        userData["createdAt"] = FieldValue.serverTimestamp()

        db.collection("users").document(uid)
            .set(userData)
            .addOnSuccessListener {
                shouldSaveDraft = false
                clearLocalDraft()

                // remove rascunho, mas mesmo se falhar, segue fluxo
                db.collection("register_drafts").document(uid).delete()

                startActivity(Intent(this, MainActivity::class.java))
                finishAffinity()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Erro ao salvar perfil: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun saveDraftProgress() {
        registerViewModel.currentStep = if (::viewPager.isInitialized) viewPager.currentItem else registerViewModel.currentStep

        val payload = registerViewModel.toDraftMap()
            .filterValues { it != null }
            .toMutableMap()

        saveLocalDraft(payload)

        val uid = auth.currentUser?.uid ?: return

        payload["updatedAt"] = FieldValue.serverTimestamp()

        db.collection("register_drafts")
            .document(uid)
            .set(payload)
    }

    private fun saveLocalDraft(payload: Map<String, Any?>) {
        val json = JSONObject()
        payload.forEach { (key, value) ->
            when (value) {
                null -> Unit
                is String, is Number, is Boolean -> json.put(key, value)
                else -> json.put(key, value.toString())
            }
        }
        draftPrefs.edit().putString(LOCAL_DRAFT_KEY, json.toString()).apply()
    }

    private fun loadLocalDraft(): Map<String, Any>? {
        val raw = draftPrefs.getString(LOCAL_DRAFT_KEY, null) ?: return null

        return runCatching {
            val json = JSONObject(raw)
            val out = mutableMapOf<String, Any>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = json.get(key)
                if (value != JSONObject.NULL) {
                    out[key] = value
                }
            }
            out
        }.getOrNull()
    }

    private fun clearLocalDraft() {
        draftPrefs.edit().remove(LOCAL_DRAFT_KEY).apply()
    }

    private fun humanizeAuthError(e: Exception): String {
        val authEx = e as? FirebaseAuthException
        val code = authEx?.errorCode ?: "UNKNOWN"

        Log.e(TAG, "Firebase Auth error code=$code message=${e.message}", e)

        val message = when (code) {
            "ERROR_INVALID_EMAIL" -> "e-mail invalido."
            "ERROR_EMAIL_ALREADY_IN_USE" -> "esse e-mail ja esta em uso."
            "ERROR_WEAK_PASSWORD" -> "senha fraca. Use pelo menos 6 caracteres."
            "ERROR_OPERATION_NOT_ALLOWED" -> "cadastro por e-mail/senha esta desativado no Firebase."
            "ERROR_NETWORK_REQUEST_FAILED" -> "falha de rede. Verifique sua conexao."
            "ERROR_INTERNAL_ERROR" -> "erro interno do Firebase (verifique configuracao do projeto)."
            "ERROR_APP_NOT_AUTHORIZED" -> "app nao autorizado. Confira package name e SHA no Firebase."
            else -> e.message ?: "erro desconhecido."
        }

        return "$message [codigo: $code]"
    }

    /**
     * Pega o fragment do ViewPager2 pelo tag padrão "f0", "f1", "f2", ...
     */
    private fun getStepFragment(index: Int) =
        supportFragmentManager.findFragmentByTag("f$index")

    private fun validateStep(index: Int, showErrors: Boolean): Boolean {
        val frag = getStepFragment(index)

        if (frag is ProfileSelectionFragment) {
            return registerViewModel.profileType != null
        }

        val validatable = frag as? StepValidatable
        return validatable?.validateStep(showErrors) ?: false
    }

    private fun findFirstInvalidStep(): Int {
        val count = viewPager.adapter?.itemCount ?: 0
        for (i in 0 until count) {
            val ok = validateStep(i, showErrors = false)
            if (!ok) return i
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
        // Steps
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

        // Lines
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
        val lastIndex = (viewPager.adapter?.itemCount ?: 1) - 1

        btnBack.isEnabled = currentIndex > 0
        btnNext.text = if (currentIndex == lastIndex) "Finalizar" else "Avançar"

        val total = viewPager.adapter?.itemCount ?: 1
        tvStepTitle.text = "Etapa ${currentIndex + 1} de $total"
    }

    private fun notifyCurrentStepToFragment(position: Int) {
        val total = viewPager.adapter?.itemCount ?: 1
        val frag = supportFragmentManager.findFragmentByTag("f$position")
        (frag as? StepHeaderBindable)?.onStepChanged(position, total)
    }
}
