package com.gte619n.healthfitness.feature.workouts.addequipment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.domain.workouts.CreateEquipmentRequest
import com.gte619n.healthfitness.domain.workouts.Equipment
import com.gte619n.healthfitness.domain.workouts.EquipmentRepository
import com.gte619n.healthfitness.domain.workouts.EquipmentSpec
import com.gte619n.healthfitness.domain.workouts.LocationRepository
import com.gte619n.healthfitness.domain.workouts.SpecSchemaTag
import com.gte619n.healthfitness.domain.workouts.UpdateLocationRequest
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

/**
 * Drives the "Add equipment to gym" bottom sheet.
 *
 * Two tabs:
 *   - CATALOG: debounced search across `/api/equipment`. Tapping an
 *     equipment row adds it to the gym's `equipmentIds[]` via
 *     PATCH (the backend has no dedicated add endpoint).
 *   - SUBMIT: form for a new equipment record. Submits to
 *     `/api/me/equipment` then immediately adds the returned id to
 *     the gym.
 *
 * Catalog search query is debounced (300ms) — typing five chars
 * shouldn't fire five requests. Server-side filtering only; no
 * client-side cache.
 */
@HiltViewModel
class AddEquipmentViewModel @Inject constructor(
    private val locationRepo: LocationRepository,
    private val equipmentRepo: EquipmentRepository,
) : ViewModel() {

    enum class Tab { CATALOG, SUBMIT }

    data class CatalogState(
        val query: String = "",
        val results: List<Equipment> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
    )

    data class SubmitState(
        val name: String = "",
        val category: String = "",
        val subcategory: String = "",
        val schema: SpecSchemaTag = SpecSchemaTag.SELECTORIZED,
        val specs: EquipmentSpec = EquipmentSpec.Selectorized(0.0, 0.0, 0.0),
        val submitting: Boolean = false,
        val error: String? = null,
    )

    private val _activeTab = MutableStateFlow(Tab.CATALOG)
    val activeTab: StateFlow<Tab> = _activeTab.asStateFlow()

    private val _catalog = MutableStateFlow(CatalogState())
    val catalog: StateFlow<CatalogState> = _catalog.asStateFlow()

    private val _submit = MutableStateFlow(SubmitState())
    val submit: StateFlow<SubmitState> = _submit.asStateFlow()

    private val queryFlow = MutableStateFlow("")

    init {
        observeQueryWithDebounce()
        // Prime the catalog with the initial (empty) query → recent /
        // popular ordering returned by the backend.
        runSearch("")
    }

    fun selectTab(tab: Tab) { _activeTab.value = tab }

    @OptIn(FlowPreview::class)
    private fun observeQueryWithDebounce() {
        viewModelScope.launch {
            queryFlow
                .debounce(300)
                .distinctUntilChanged()
                .collect { q -> runSearch(q) }
        }
    }

    fun setQuery(q: String) {
        _catalog.update { it.copy(query = q) }
        queryFlow.value = q
    }

    private fun runSearch(q: String) {
        viewModelScope.launch {
            _catalog.update { it.copy(loading = true, error = null) }
            equipmentRepo.searchCatalog(search = q.takeIf { it.isNotBlank() }).fold(
                onSuccess = { results ->
                    _catalog.update { it.copy(loading = false, results = results) }
                },
                onFailure = { e ->
                    _catalog.update {
                        it.copy(
                            loading = false,
                            error = e.localizedMessage ?: "Couldn't load catalog",
                        )
                    }
                },
            )
        }
    }

    /**
     * Add an existing catalog equipment to [locationId]. The backend
     * has no dedicated endpoint — we PATCH the location with the new
     * equipmentIds list.
     */
    fun addFromCatalog(
        locationId: String,
        equipmentId: String,
        onDone: () -> Unit,
    ) {
        viewModelScope.launch {
            val location = locationRepo.get(locationId).getOrNull() ?: return@launch
            if (equipmentId in location.equipmentIds) {
                onDone()
                return@launch
            }
            val nextIds = location.equipmentIds + equipmentId
            locationRepo.update(
                locationId = locationId,
                req = UpdateLocationRequest(equipmentIds = nextIds),
            ).onSuccess { onDone() }
                .onFailure { e ->
                    _catalog.update {
                        it.copy(error = e.localizedMessage ?: "Couldn't add equipment")
                    }
                }
        }
    }

    fun updateSubmitForm(transform: (SubmitState) -> SubmitState) {
        _submit.update(transform)
    }

    /**
     * Submit a new equipment record, then PATCH the location to
     * include the new id.
     */
    fun submitNew(locationId: String, onDone: () -> Unit) {
        val current = _submit.value
        val validation = validate(current)
        if (validation != null) {
            _submit.update { it.copy(error = validation) }
            return
        }
        _submit.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            val req = CreateEquipmentRequest(
                name = current.name.trim(),
                category = current.category.trim(),
                subcategory = current.subcategory.trim(),
                specSchema = current.schema,
                specs = current.specs,
            )
            val submitted = equipmentRepo.submit(req).getOrNull()
            if (submitted == null) {
                _submit.update {
                    it.copy(
                        submitting = false,
                        error = "Couldn't submit equipment",
                    )
                }
                return@launch
            }
            // Now wire it into the gym
            val location = locationRepo.get(locationId).getOrNull()
            if (location == null) {
                _submit.update {
                    it.copy(submitting = false, error = "Couldn't load gym")
                }
                return@launch
            }
            val nextIds = location.equipmentIds + submitted.equipmentId
            locationRepo.update(
                locationId = locationId,
                req = UpdateLocationRequest(equipmentIds = nextIds),
            ).fold(
                onSuccess = {
                    _submit.update { SubmitState() }
                    onDone()
                },
                onFailure = { e ->
                    _submit.update {
                        it.copy(
                            submitting = false,
                            error = e.localizedMessage ?: "Couldn't add equipment to gym",
                        )
                    }
                },
            )
        }
    }

    private fun validate(state: SubmitState): String? = when {
        state.name.isBlank() -> "Name is required"
        state.category.isBlank() -> "Category is required"
        state.subcategory.isBlank() -> "Subcategory is required"
        else -> null
    }
}
