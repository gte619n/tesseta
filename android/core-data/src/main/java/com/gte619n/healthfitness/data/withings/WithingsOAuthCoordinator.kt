package com.gte619n.healthfitness.data.withings

import android.net.Uri
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

// Result of the Withings browser redirect (healthfitness://withings-callback).
data class WithingsCallback(val code: String?, val state: String?, val error: String?)

// Relays the Withings OAuth redirect from the Activity (which receives the deep
// link) to the WithingsViewModel (which finishes the connect), and holds the
// CSRF `state` across the browser round-trip. App-scoped so it survives the
// ViewModel being recreated while the browser is foregrounded.
//
// Withings uses a browser authorization-code redirect (not Google Sign-In), so
// there's no consent IntentSender like Google Health — the Activity forwards the
// callback Uri here via handleRedirect() from onNewIntent.
@Singleton
class WithingsOAuthCoordinator @Inject constructor() {

    // replay=0 + a small buffer: a callback that arrives a beat before the VM
    // subscribes is still delivered, but it's never re-played to a new collector
    // (which would double-submit the code).
    private val _callbacks = MutableSharedFlow<WithingsCallback>(
        replay = 0,
        extraBufferCapacity = 1,
    )
    val callbacks: SharedFlow<WithingsCallback> = _callbacks.asSharedFlow()

    @Volatile
    private var pendingState: String? = null

    fun rememberState(state: String) {
        pendingState = state
    }

    fun consumeState(): String? {
        val s = pendingState
        pendingState = null
        return s
    }

    /** Returns true if the Uri was a Withings OAuth redirect we consumed. */
    fun handleRedirect(uri: Uri?): Boolean {
        if (uri == null || uri.scheme != SCHEME || uri.host != HOST) return false
        _callbacks.tryEmit(
            WithingsCallback(
                code = uri.getQueryParameter("code"),
                state = uri.getQueryParameter("state"),
                error = uri.getQueryParameter("error"),
            ),
        )
        return true
    }

    companion object {
        const val SCHEME = "healthfitness"
        const val HOST = "withings-callback"
        const val REDIRECT_URI = "$SCHEME://$HOST"
        const val AUTHORIZE_URL = "https://account.withings.com/oauth2_user/authorize2"

        // Sleep Analyzer data needs user.activity; weight/body needs user.metrics.
        const val SCOPE = "user.metrics,user.activity"

        // Pure string builder (no android.net.Uri) so it's unit-testable on the JVM.
        fun buildAuthorizeUrl(clientId: String, state: String): String {
            fun enc(v: String) = URLEncoder.encode(v, "UTF-8")
            return AUTHORIZE_URL +
                "?response_type=code" +
                "&client_id=" + enc(clientId) +
                "&scope=" + enc(SCOPE) +
                "&redirect_uri=" + enc(REDIRECT_URI) +
                "&state=" + enc(state)
        }
    }
}
