package com.gte619n.healthfitness.domain.bodycomposition

sealed interface DexaUploadEvent {
    /** "uploading" | "extracting" | "saving" — verbatim from backend. */
    data class Phase(val phase: String, val message: String?) : DexaUploadEvent
    data class Complete(val scan: DexaScan) : DexaUploadEvent
    data class Failed(val error: String) : DexaUploadEvent
}
