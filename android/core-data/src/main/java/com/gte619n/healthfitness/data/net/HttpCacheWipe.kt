package com.gte619n.healthfitness.data.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cache
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Evicts the shared OkHttp disk cache (`http_cache`) on sign-out / account
 * switch. API responses are `no-store` (Spring Security defaults), so the
 * cache holds mostly images and other media fetched during the old session —
 * still per-user content that must not survive it on a shared device.
 *
 * Lives in :core-data because OkHttp is an implementation detail of this
 * module; :app's SignOutSideEffects calls this instead of touching the cache
 * type directly.
 */
@Singleton
class HttpCacheWipe @Inject constructor(
    private val cache: Cache,
) {
    suspend fun wipe() {
        withContext(Dispatchers.IO) {
            runCatching { cache.evictAll() }
        }
    }
}
