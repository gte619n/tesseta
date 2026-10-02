package com.gte619n.healthfitness.data.auth

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * IMPL-MULTIUSER-01 P1.4 — the bridge from the OkHttp layer (which sees the raw
 * `403 + X-Account-Status` response) up to the UI-layer [AuthCoordinator] (which
 * owns the [AuthState] and can render the locked-out screen + wipe local data).
 *
 * The network stack can't depend on the app-scoped AuthCoordinator (it lives in
 * `:app` and would form a Hilt cycle through the OkHttp client), so the
 * [AccountStatusInterceptor] instead emits onto this app-scoped @Singleton
 * SharedFlow and the AuthCoordinator collects it. One-way, fire-and-forget: a
 * detection is a fact that outlives the request that produced it.
 *
 * `replay = 1` so a collector that attaches just after a detection (e.g. the
 * coordinator's collector starting on launch while an in-flight background sync
 * already 403'd) still observes the most recent status rather than missing it to
 * a subscription-timing race — mirrors the `SyncEngine` signal flows.
 */
@Singleton
class AccountStatusSignal @Inject constructor() {

    /**
     * The non-active account statuses the backend reports via `X-Account-Status`.
     * [headerValue] is the exact wire token so the interceptor maps a header
     * string straight onto a case without a second lookup.
     */
    enum class Status(val headerValue: String) {
        PENDING("account-pending"),
        SUSPENDED("account-suspended"),
        DISABLED("account-disabled"),
        ;

        companion object {
            /** Map an `X-Account-Status` header value onto a [Status], or null if unknown/absent. */
            fun fromHeader(value: String?): Status? =
                value?.trim()?.lowercase()?.let { v -> entries.firstOrNull { it.headerValue == v } }
        }
    }

    private val _events = MutableSharedFlow<Status>(replay = 1, extraBufferCapacity = 8)
    val events: SharedFlow<Status> = _events

    /** Record a detected non-active account status (from the HTTP layer). */
    fun report(status: Status) {
        _events.tryEmit(status)
    }
}
