package com.gte619n.healthfitness.data.nutrition

/**
 * Wire response for the lazy "typical serving" hint endpoint. [hint] is null when
 * no explanation could be generated (analyzer unavailable, or the entry has no
 * weight/name to describe) — the edit sheet then shows nothing.
 */
data class ServingHintResponse(val hint: String? = null)
