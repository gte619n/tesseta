package com.gte619n.healthfitness.data.net

import com.gte619n.healthfitness.data.auth.AccountStatusSignal
import okhttp3.Interceptor
import okhttp3.Response

/**
 * IMPL-MULTIUSER-01 P1.4 — detects a non-active account and routes it to the
 * lockout UI.
 *
 * The backend's `AccountStatusFilter` returns **HTTP 403** with a
 * `X-Account-Status: account-pending | account-suspended | account-disabled`
 * header for any request from a user whose status is not ACTIVE (and
 * `/api/auth/exchange`|`/refresh` do the same for a non-active account — NOT 401,
 * precisely so token refresh is never attempted). This application interceptor
 * inspects every response, and on that signal reports the status onto the
 * app-scoped [AccountStatusSignal] that [AuthCoordinator] collects to flip into
 * the Pending / Suspended state (and wipe local PHI on suspend/disable).
 *
 * Why an interceptor and not the [TokenAuthenticator]: OkHttp only invokes the
 * authenticator on **401**, so a 403 never reaches it — there is therefore no
 * refresh loop risk here (the authenticator simply never fires on this status).
 * We still want to *observe* the 403, which only an interceptor positioned to see
 * every response can do. We read the header and pass the response through
 * unchanged — the body is never consumed, so the original caller still receives
 * the 403 and fails normally.
 */
class AccountStatusInterceptor(
    private val signal: AccountStatusSignal,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (response.code == HTTP_FORBIDDEN) {
            AccountStatusSignal.Status.fromHeader(response.header(HEADER_ACCOUNT_STATUS))
                ?.let(signal::report)
        }
        return response
    }

    private companion object {
        const val HTTP_FORBIDDEN = 403
        const val HEADER_ACCOUNT_STATUS = "X-Account-Status"
    }
}
