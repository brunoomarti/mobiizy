package com.brunocodex.kotlinproject.fragments

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Filter
import android.widget.Filterable
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.brunocodex.kotlinproject.R
import com.brunocodex.kotlinproject.utils.ApiClient
import com.brunocodex.kotlinproject.utils.NominatimResult
import com.brunocodex.kotlinproject.utils.StepValidatable
import com.brunocodex.kotlinproject.utils.StepValidationUtils
import com.brunocodex.kotlinproject.viewmodels.RegisterViewModel
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.Normalizer

class AddressInfoFragment : Fragment(R.layout.fragment_address_info), StepValidatable {

    private val registerViewModel: RegisterViewModel by activityViewModels()
    private val queryFlow = MutableStateFlow("")
    private var ignoreTextChanges = false
    private var tvHeaderStep: TextView? = null

    // --- dropdown rows (loading / empty / result)
    private sealed class DropRow {
        data object Loading : DropRow()
        data object Empty : DropRow()
        data class Result(val item: NominatimResult) : DropRow()
    }

    private class AddressDropAdapter(
        private val ctx: Context,
        private val inflater: LayoutInflater
    ) : BaseAdapter(), Filterable {

        private val items = mutableListOf<DropRow>()

        fun submit(newItems: List<DropRow>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): DropRow = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun isEnabled(position: Int): Boolean {
            return getItem(position) is DropRow.Result
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: inflater.inflate(R.layout.item_address_dropdown, parent, false)

            val rowText = v.findViewById<TextView>(R.id.rowText)
            val rowProgress = v.findViewById<ProgressBar>(R.id.rowProgress)

            when (val row = getItem(position)) {
                is DropRow.Loading -> {
                    rowText.text = ctx.getString(R.string.address_dropdown_loading)
                    rowText.alpha = 0.9f
                    rowProgress.visibility = View.VISIBLE
                }

                is DropRow.Empty -> {
                    rowText.text = ctx.getString(R.string.address_dropdown_empty)
                    rowText.alpha = 0.6f
                    rowProgress.visibility = View.GONE
                }

                is DropRow.Result -> {
                    rowText.text = row.item.displayName.orEmpty()
                    rowText.alpha = 1f
                    rowProgress.visibility = View.GONE
                }
            }

            return v
        }

        private val noFilter = object : Filter() {
            override fun performFiltering(constraint: CharSequence?): FilterResults {
                return FilterResults().apply {
                    values = items
                    count = items.size
                }
            }

            override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                notifyDataSetChanged()
            }
        }

        override fun getFilter(): Filter = noFilter
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val addressInput = view.findViewById<MaterialAutoCompleteTextView>(R.id.addressInput)
        val streetInput = view.findViewById<TextInputEditText>(R.id.streetInput)
        val numberInput = view.findViewById<TextInputEditText>(R.id.numberInput)
        val neighborhoodInput = view.findViewById<TextInputEditText>(R.id.neighborhoodInput)
        val cityInput = view.findViewById<TextInputEditText>(R.id.cityInput)
        val stateInput = view.findViewById<TextInputEditText>(R.id.stateInput)
        val cepInput = view.findViewById<TextInputEditText>(R.id.cepInput)

        restoreData(cepInput, streetInput, numberInput, neighborhoodInput, cityInput, stateInput)
        setupDataSaving(cepInput, streetInput, numberInput, neighborhoodInput, cityInput, stateInput)

        tvHeaderStep = view.findViewById(R.id.tvStepTitle)
        view.findViewById<TextView>(R.id.stepHeadline).text = getString(R.string.address_step_headline)
        view.findViewById<TextView>(R.id.stepSubtitle).text = getString(R.string.address_step_subtitle)

        val dropAdapter = AddressDropAdapter(requireContext(), layoutInflater)
        addressInput.setAdapter(dropAdapter)

        addressInput.threshold = 1

        addressInput.doOnTextChanged { text, _, _, _ ->
            if (ignoreTextChanges) return@doOnTextChanged
            queryFlow.value = text?.toString().orEmpty()
        }

        addressInput.setOnItemClickListener { parent, _, position, _ ->
            val row = parent.getItemAtPosition(position) as? DropRow ?: return@setOnItemClickListener
            if (row !is DropRow.Result) return@setOnItemClickListener

            val picked = row.item

            ignoreTextChanges = true
            addressInput.setText(picked.displayName.orEmpty(), false)
            ignoreTextChanges = false

            addressInput.dismissDropDown()

            applyPickedAddress(
                picked,
                streetInput,
                numberInput,
                neighborhoodInput,
                cityInput,
                stateInput,
                cepInput
            )

            if (numberInput.text.isNullOrBlank()) numberInput.requestFocus()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            queryFlow
                .map { it.trim() }
                .distinctUntilChanged()
                .debounce(650)
                .collectLatest { raw ->
                    if (!addressInput.hasFocus()) return@collectLatest

                    if (raw.length < 3) {
                        dropAdapter.submit(emptyList())
                        addressInput.dismissDropDown()
                        return@collectLatest
                    }

                    dropAdapter.submit(listOf(DropRow.Loading))
                    addressInput.post { if (addressInput.hasFocus()) addressInput.showDropDown() }

                    val city = cityInput.text?.toString().orEmpty().trim()
                    val uf = stateInput.text?.toString().orEmpty().trim()

                    val merged = searchMerged(raw, city, uf)

                    val ranked = rankResults(raw, merged)
                        .filter { !it.displayName.isNullOrBlank() }
                        .take(8)

                    if (ranked.isEmpty()) {
                        dropAdapter.submit(listOf(DropRow.Empty))
                        addressInput.post { if (addressInput.hasFocus()) addressInput.showDropDown() }
                        return@collectLatest
                    }

                    dropAdapter.submit(ranked.map { DropRow.Result(it) })
                    addressInput.post { if (addressInput.hasFocus()) addressInput.showDropDown() }
                }
        }
    }

    private fun restoreData(
        cepInput: TextInputEditText,
        streetInput: TextInputEditText,
        numberInput: TextInputEditText,
        neighborhoodInput: TextInputEditText,
        cityInput: TextInputEditText,
        stateInput: TextInputEditText
    ) {
        cepInput.setText(registerViewModel.cep)
        streetInput.setText(registerViewModel.street)
        numberInput.setText(registerViewModel.number)
        neighborhoodInput.setText(registerViewModel.neighborhood)
        cityInput.setText(registerViewModel.city)
        stateInput.setText(registerViewModel.state)
    }

    private fun setupDataSaving(
        cepInput: TextInputEditText,
        streetInput: TextInputEditText,
        numberInput: TextInputEditText,
        neighborhoodInput: TextInputEditText,
        cityInput: TextInputEditText,
        stateInput: TextInputEditText
    ) {
        cepInput.doOnTextChanged { text, _, _, _ -> registerViewModel.cep = text.toString() }
        streetInput.doOnTextChanged { text, _, _, _ -> registerViewModel.street = text.toString() }
        numberInput.doOnTextChanged { text, _, _, _ -> registerViewModel.number = text.toString() }
        neighborhoodInput.doOnTextChanged { text, _, _, _ -> registerViewModel.neighborhood = text.toString() }
        cityInput.doOnTextChanged { text, _, _, _ -> registerViewModel.city = text.toString() }
        stateInput.doOnTextChanged { text, _, _, _ -> registerViewModel.state = text.toString() }
    }

    private suspend fun searchMerged(raw: String, city: String, uf: String): List<NominatimResult> {
        val ctx = buildContext(city, uf)
        val q1 = withContext(raw, ctx)
        val results1 = safeSearch(q1)

        val needAccentFallback = shouldTryAccentFallback(raw, results1)
        if (!needAccentFallback) {
            return results1.distinctBy { it.displayName.orEmpty() }
        }

        val fallbackQueries = buildAccentFallbackQueries(raw, maxVariants = 8)
        if (fallbackQueries.isEmpty()) return results1.distinctBy { it.displayName.orEmpty() }

        val merged = results1.toMutableList()
        for (q in fallbackQueries) {
            delay(220)
            merged += safeSearch(withContext(q, ctx))
        }

        return merged.distinctBy { it.displayName.orEmpty() }
    }

    private suspend fun safeSearch(query: String): List<NominatimResult> {
        return runCatching { ApiClient.nominatim.search(query = query) }.getOrElse { emptyList() }
    }

    private fun shouldTryAccentFallback(raw: String, results: List<NominatimResult>): Boolean {
        val qLast = normalizeForSearch(raw).lowercase().split(Regex("\\s+")).lastOrNull().orEmpty()
        if (qLast.length < 5) return false
        if (results.isEmpty()) return true
        return results.none { r ->
            val d = normalizeForSearch(r.displayName.orEmpty()).lowercase()
            d.contains(qLast)
        }
    }

    private fun buildAccentFallbackQueries(input: String, maxVariants: Int): List<String> {
        val parts = input.trim().split(Regex("\\s+")).toMutableList()
        if (parts.isEmpty()) return emptyList()

        val last = parts.last()
        val tokenVariants = mutableListOf<String>()

        for (i in last.indices) {
            val c = last[i]
            val options = when (c.lowercaseChar()) {
                'a' -> charArrayOf('á', 'à', 'â', 'ã')
                'e' -> charArrayOf('é', 'ê')
                'i' -> charArrayOf('í')
                'o' -> charArrayOf('ó', 'ô', 'õ')
                'u' -> charArrayOf('ú')
                'c' -> charArrayOf('ç')
                else -> null
            } ?: continue

            for (opt in options) {
                val sb = StringBuilder(last)
                val out = if (c.isUpperCase()) opt.uppercaseChar() else opt
                sb.setCharAt(i, out)
                tokenVariants += sb.toString()
            }
        }

        return tokenVariants
            .asSequence()
            .filter { it != last }
            .distinct()
            .take(maxVariants)
            .map { token ->
                parts[parts.lastIndex] = token
                parts.joinToString(" ")
            }
            .toList()
    }

    private fun buildContext(city: String, uf: String): String {
        val parts = mutableListOf<String>()
        if (city.isNotBlank()) parts += city
        if (uf.isNotBlank()) parts += uf
        parts += "Brasil"
        return parts.joinToString(", ")
    }

    private fun withContext(q: String, ctx: String): String {
        return if (ctx.isBlank()) q else "$q, $ctx"
    }

    private fun rankResults(userQuery: String, items: List<NominatimResult>): List<NominatimResult> {
        val qNorm = normalizeForSearch(userQuery).lowercase()
        val qLast = qNorm.split(Regex("\\s+")).lastOrNull().orEmpty()

        fun score(display: String): Int {
            val dNorm = normalizeForSearch(display).lowercase()
            var s = 0
            if (dNorm.contains(qNorm)) s += 60
            if (dNorm.startsWith(qNorm)) s += 25
            if (qLast.isNotBlank()) {
                val wordRegex = Regex("""\b${Regex.escape(qLast)}\b""")
                if (wordRegex.containsMatchIn(dNorm)) s += 50
            }
            return s
        }

        return items.distinctBy { it.displayName.orEmpty() }.sortedByDescending { score(it.displayName.orEmpty()) }
    }

    private fun normalizeForSearch(input: String): String {
        val nfd = Normalizer.normalize(input, Normalizer.Form.NFD)
        return nfd.replace("\\p{Mn}+".toRegex(), "").replace(Regex("\\s+"), " ").trim()
    }

    private fun applyPickedAddress(
        picked: NominatimResult,
        street: TextInputEditText,
        number: TextInputEditText,
        neighborhood: TextInputEditText,
        city: TextInputEditText,
        stateUf: TextInputEditText,
        cep: TextInputEditText
    ) {
        val a = picked.address

        street.setText(a?.road.orEmpty())
        number.setText(a?.houseNumber.orEmpty())

        val bairro = a?.suburb ?: a?.neighbourhood ?: ""
        neighborhood.setText(bairro)

        val cidade = a?.city ?: a?.town ?: a?.municipality ?: ""
        city.setText(cidade)

        stateUf.setText(toUf(a?.state.orEmpty()))
        cep.setText(formatCep(a?.postcode.orEmpty()))
    }

    private fun formatCep(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return if (digits.length == 8) "${digits.substring(0, 5)}-${digits.substring(5)}" else raw
    }

    private fun toUf(state: String): String {
        val s = state.trim().lowercase()
        val map = mapOf(
            "acre" to "AC", "alagoas" to "AL", "amapÃ¡" to "AP", "amapa" to "AP",
            "amazonas" to "AM", "bahia" to "BA", "cearÃ¡" to "CE", "ceara" to "CE",
            "distrito federal" to "DF", "espÃ­rito santo" to "ES", "espirito santo" to "ES",
            "goiÃ¡s" to "GO", "goias" to "GO", "maranhÃ£o" to "MA", "maranhao" to "MA",
            "mato grosso" to "MT", "mato grosso do sul" to "MS", "minas gerais" to "MG",
            "parÃ¡" to "PA", "para" to "PA", "paraÃ­ba" to "PB", "paraiba" to "PB",
            "paranÃ¡" to "PR", "parana" to "PR", "pernambuco" to "PE", "piauÃ­" to "PI",
            "piaui" to "PI", "rio de janeiro" to "RJ", "rio grande do norte" to "RN",
            "rio grande do sul" to "RS", "rondÃ´nia" to "RO", "rondonia" to "RO",
            "roraima" to "RR", "santa catarina" to "SC", "sÃ£o paulo" to "SP",
            "sao paulo" to "SP", "sergipe" to "SE", "tocantins" to "TO"
        )
        return map[s] ?: state.take(2).uppercase()
    }

    override fun validateStep(showErrors: Boolean): Boolean {
        val root = view ?: return false
        return StepValidationUtils.validateRequiredFields(root, showErrors)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        tvHeaderStep = null
    }
}
