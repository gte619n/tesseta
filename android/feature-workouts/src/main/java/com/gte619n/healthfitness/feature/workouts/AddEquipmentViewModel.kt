package com.gte619n.healthfitness.feature.workouts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.domain.workouts.CreateEquipmentRequest
import com.gte619n.healthfitness.domain.workouts.Equipment
import com.gte619n.healthfitness.domain.workouts.EquipmentRepository
import com.gte619n.healthfitness.domain.workouts.LocationRepository
import com.gte619n.healthfitness.domain.workouts.SpecSchemaTag
import com.gte619n.healthfitness.feature.workouts.ui.specFromMap
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(FlowPreview::class)
@HiltViewModel
class AddEquipmentViewModel @Inject constructor(
    private val locationRepo: LocationRepository,
    private val equipmentRepo: EquipmentRepository,
) : ViewModel() {

    enum class Tab { CATALOG, SUBMIT }

    data class CatalogState(
        val query: String = "",
        val category: String? = null,
        val subcategory: String? = null,
        val results: List<Equipment> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
    )

    data class SubmitState(
        val name: String = "",
        val category: String = "",
        val subcategory: String = "",
        val schema: SpecSchemaTag = SpecSchemaTag.SELECTORIZED,
        val specs: Map<String, Any?> = emptyMap(),
        val submitting: Boolean = false,
        val error: String? = null,
    )

    private val _tab = MutableStateFlow(Tab.CATALOG)
    val tab: StateFlow<Tab> = _tab.asStateFlow()

    private val _catalog = MutableStateFlow(CatalogState())
    val catalog: StateFlow<CatalogState> = _catalog.asStateFlow()

    private val _submitForm = MutableStateFlow(SubmitState())
    val submitForm: StateFlow<SubmitState> = _submitForm.asStateFlow()

    private val queryFlow = MutableStateFlow("")

    init {
        viewModelScope.launch {
            queryFlow
                .debounce(300)
                .distinctUntilChanged()
                .collect { runSearch(it) }
        }
        // Initial unfiltered catalog load.
        runSearchImmediate()
    }

    fun selectTab(tab: Tab) {
        _tab.value = tab
    }

    fun setQuery(q: String) {
        _catalog.update { it.copy(query = q) }
        queryFlow.value = q
    }

    fun setCategory(category: String?, subcategory: String?) {
        _catalog.update { it.copy(category = category, subcategory = subcategory) }
        runSearchImmediate()
    }

    private fun runSearchImmediate() {
        viewModelScope.launch { runSearch(_catalog.value.query) }
    }

    private suspend fun runSearch(query: String) {
        val c = _catalog.value
        _catalog.update { it.copy(loading = true, error = null) }
        equipmentRepo.searchCatalog(
            search = query.ifBlank { null },
            category = c.category,
            subcategory = c.subcategory,
        ).fold(
            onSuccess = { results -> _catalog.update { it.copy(loading = false, results = results) } },
            onFailure = { e -> _catalog.update { it.copy(loading = false, error = e.message ?: "Search failed") } },
        )
    }

    fun addFromCatalog(locationId: String, equipmentId: String, onDone: () -> Unit) {
        viewModelScope.launch {
            locationRepo.addEquipment(locationId, equipmentId).fold(
                onSuccess = { onDone() },
                onFailure = { e -> _catalog.update { it.copy(error = e.message ?: "Failed to add equipment") } },
            )
        }
    }

    fun updateSubmit(state: SubmitState) {
        _submitForm.value = state
    }

    fun submitNew(locationId: String, onDone: () -> Unit) {
        val form = _submitForm.value
        if (form.name.isBlank() || form.category.isBlank() || form.subcategory.isBlank()) {
            _submitForm.update { it.copy(error = "Name, category, and subcategory are required") }
            return
        }
        _submitForm.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            val req = CreateEquipmentRequest(
                name = form.name.trim(),
                category = form.category.trim(),
                subcategory = form.subcategory.trim(),
                specSchema = form.schema,
                specs = specFromMap(form.schema, form.specs),
            )
            equipmentRepo.submit(req).fold(
                onSuccess = { created ->
                    // Atomically attach the new equipment to the current gym.
                    locationRepo.addEquipment(locationId, created.equipmentId).fold(
                        onSuccess = {
                            _submitForm.update { it.copy(submitting = false) }
                            onDone()
                        },
                        onFailure = { e ->
                            _submitForm.update { it.copy(submitting = false, error = e.message ?: "Submitted but failed to attach") }
                        },
                    )
                },
                onFailure = { e ->
                    _submitForm.update { it.copy(submitting = false, error = e.message ?: "Failed to submit equipment") }
                },
            )
        }
    }
}
