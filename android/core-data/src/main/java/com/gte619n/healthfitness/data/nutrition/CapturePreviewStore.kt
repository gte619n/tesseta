package com.gte619n.healthfitness.data.nutrition

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Maps a photographed-meal server entry id → the local cache-file path of the
 * user's just-captured JPEG, so the day row can show the real photo (with a
 * loader over it) through the upload → analyze → image-generation stages, instead
 * of a spinner, until the server's generated image lands READY.
 *
 * In-memory only: the association is a display nicety. It survives backgrounding
 * (process-scoped singleton) but not process death — after a kill the row falls
 * back to the normal analyzing/placeholder states, and the generated image still
 * arrives via the settle-poll / sync, so nothing is lost. The JPEG itself lives
 * in cacheDir; [remove] deletes it once the generated image supersedes the
 * preview.
 */
@Singleton
class CapturePreviewStore @Inject constructor() {

    private val _previews = MutableStateFlow<Map<String, String>>(emptyMap())

    /** entryId → local JPEG path. Emits so the day view re-assembles on change. */
    val previews: StateFlow<Map<String, String>> = _previews.asStateFlow()

    /** Associate a captured JPEG with the server entry the capture produced. */
    fun put(entryId: String, jpegPath: String) =
        _previews.update { it + (entryId to jpegPath) }

    /** The local preview path for [entryId], or null. */
    fun path(entryId: String): String? = _previews.value[entryId]

    /**
     * Drop the preview for [entryId] (the generated image has taken over) and
     * best-effort delete the cache file so captures don't accumulate on disk.
     */
    fun remove(entryId: String) {
        val path = _previews.value[entryId] ?: return
        _previews.update { it - entryId }
        runCatching { File(path).delete() }
    }
}
