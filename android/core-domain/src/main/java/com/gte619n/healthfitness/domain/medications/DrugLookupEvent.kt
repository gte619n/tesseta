package com.gte619n.healthfitness.domain.medications

sealed interface DrugLookupEvent {
    data class Progress(val phase: String, val message: String?) : DrugLookupEvent
    data class Found(val drug: Drug) : DrugLookupEvent
    data class NotFound(val message: String?) : DrugLookupEvent
    data class Failed(val error: String) : DrugLookupEvent
}
