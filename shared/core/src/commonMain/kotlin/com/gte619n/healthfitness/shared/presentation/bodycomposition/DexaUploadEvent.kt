package com.gte619n.healthfitness.shared.presentation.bodycomposition

import com.gte619n.healthfitness.shared.domain.bodycomposition.DexaScan

/**
 * IMPL-IOS-01 Phase 3 Wave E2 — KMP port of
 * android/core-domain/.../domain/bodycomposition/DexaUploadEvent.kt.
 *
 * The phase stream the DEXA multipart-SSE upload emits. Co-located with the
 * body-composition presentation (the shared domain/bodycomposition package is a
 * frozen, already-ported reference), mirroring where [DexaScanRepository.uploadPdf]
 * returns it. Shape is 1:1 with the Android source.
 */
sealed interface DexaUploadEvent {
    /** "uploading" | "extracting" | "saving" — verbatim from backend. */
    data class Phase(val phase: String, val message: String?) : DexaUploadEvent
    data class Complete(val scan: DexaScan) : DexaUploadEvent
    data class Failed(val error: String) : DexaUploadEvent
}
