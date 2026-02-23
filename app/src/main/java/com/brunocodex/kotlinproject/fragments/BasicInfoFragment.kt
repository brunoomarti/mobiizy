package com.brunocodex.kotlinproject.fragments

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.brunocodex.kotlinproject.R
import com.brunocodex.kotlinproject.utils.StepValidatable
import com.brunocodex.kotlinproject.utils.StepValidationUtils
import com.brunocodex.kotlinproject.viewmodels.RegisterViewModel
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

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

    private var personType: PersonType = PersonType.CPF

    private var docWatcher: TextWatcher? = null
    private var dateWatcher: TextWatcher? = null
    private var phoneWatcher: TextWatcher? = null

    private var tvHeaderStep: TextView? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvHeaderStep = view.findViewById(R.id.tvStepTitle)
        view.findViewById<TextView>(R.id.stepHeadline).text = "Informações básicas"
        view.findViewById<TextView>(R.id.stepSubtitle).text =
            "Bora começar! Esses dados ajudam a proteger sua conta e agilizar a locação."

        bindViews(view)

        setupRequiredLiveClear()
        setupCommonMasks()
        setupPersonTypeToggle()

        // Descobre o tipo com base no doc salvo (se tiver)
        val savedDocDigits = registerViewModel.cpf.orEmpty().filter { it.isDigit() }
        val initialType = if (savedDocDigits.length > 11) PersonType.CNPJ else PersonType.CPF

        // Marca o rádio correto
        if (initialType == PersonType.CNPJ) {
            rbCnpj.isChecked = true
        } else {
            rbCpf.isChecked = true
        }

        // Aplica tipo e DEPOIS restaura os dados
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
    }

    private fun restoreData() {
        nameInput.setText(registerViewModel.name)
        phoneInput.setText(registerViewModel.phone)
        docInput.setText(registerViewModel.cpf) // Assumindo CPF por enquanto
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
            nameLayout.helperText = "Preenchido pela conta Google"
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

        // Ajusta hints
        if (type == PersonType.CPF) {
            nameLayout.hint = "Nome completo"
            dateLayout.hint = "Data de nascimento"
            docLayout.hint = "CPF"
            docInput.setText("")
            docInput.filters = arrayOf(android.text.InputFilter.LengthFilter(14)) // ###.###.###-##
        } else {
            nameLayout.hint = "Razão social"
            dateLayout.hint = "Data de abertura"
            docLayout.hint = "CNPJ"
            docInput.setText("")
            docInput.filters = arrayOf(android.text.InputFilter.LengthFilter(18)) // ##.###.###/####-##
        }

        // Remove erro visual ao trocar tipo
        clearError(docLayout)

        // Reaplica watcher de máscara do doc conforme tipo (fixo)
        docWatcher?.let { docInput.removeTextChangedListener(it) }
        docWatcher = if (type == PersonType.CPF) {
            FixedMaskWatcher(docInput, "###.###.###-##")
        } else {
            FixedMaskWatcher(docInput, "##.###.###/####-##")
        }
        docInput.addTextChangedListener(docWatcher)

        // valida doc ao digitar (real, mas só quando completo)
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

    /**
     * Validação real em tempo real:
     * - enquanto incompleto: neutro (sem erro)
     * - quando completo: valida DV de CPF ou CNPJ
     */
    private fun validateDocLive() {
        val digits = docInput.text?.toString()?.filter { it.isDigit() }.orEmpty()

        if (digits.isEmpty()) {
            clearError(docLayout)
            return
        }

        val expected = if (personType == PersonType.CPF) 11 else 14
        if (digits.length < expected) {
            // enquanto digita, não mostramos erro
            clearError(docLayout)
            return
        }

        val ok = if (personType == PersonType.CPF) isValidCPF(digits) else isValidCNPJ(digits)
        if (!ok) {
            docLayout.error = if (personType == PersonType.CPF) "CPF inválido" else "CNPJ inválido"
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
                docLayout.error = if (personType == PersonType.CPF)
                    "Informe um CPF válido"
                else
                    "Informe um CNPJ válido"
                docLayout.isErrorEnabled = true
            } else {
                clearError(docLayout)
            }
        }

        return baseOk && docOk
    }

    // -----------------------------
    // Validação REAL CPF
    // -----------------------------
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

    // -----------------------------
    // Validação REAL CNPJ
    // -----------------------------
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

    // -----------------------------
    // Watchers (máscaras)
    // -----------------------------

    /**
     * Máscara fixa (CPF OU CNPJ definido pelo rádio).
     * Não tenta "adivinhar" nada.
     */
    private class FixedMaskWatcher(
        private val editText: TextInputEditText,
        private val mask: String
    ) : TextWatcher {

        private var isUpdating = false
        private var lastDigits = ""

        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

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
            var i = 0
            for (c in mask) {
                if (c == '#') {
                    if (i >= digits.length) break
                    out.append(digits[i])
                    i++
                } else {
                    if (i >= digits.length) break
                    out.append(c)
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

        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

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
            var i = 0
            for (c in mask) {
                if (c == '#') {
                    if (i >= digits.length) break
                    out.append(digits[i])
                    i++
                } else {
                    if (i >= digits.length) break
                    out.append(c)
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

        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

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
            var i = 0
            for (c in mask) {
                if (c == '#') {
                    if (i >= digits.length) break
                    out.append(digits[i])
                    i++
                } else {
                    if (i >= digits.length) break
                    out.append(c)
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
