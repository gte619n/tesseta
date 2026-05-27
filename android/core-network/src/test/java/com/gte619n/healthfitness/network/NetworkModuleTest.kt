package com.gte619n.healthfitness.network

import okhttp3.logging.HttpLoggingInterceptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hilt-free sanity check that exercises the `@Provides` methods in
 * [NetworkModule] in isolation. We don't spin up a real Hilt graph — that
 * needs `@HiltAndroidTest` + Robolectric — but we do verify the wiring is
 * coherent: both the auth interceptor and the token authenticator end up
 * attached to the OkHttp client, and Retrofit resolves the base URL we
 * gave it.
 */
class NetworkModuleTest {

    private val tokenProvider = StubTokenProvider(current = "stub-token")

    @Test
    fun `okhttp client carries auth interceptor and authenticator`() {
        val auth = NetworkModule.authInterceptor(tokenProvider)
        val logging = NetworkModule.loggingInterceptor()
        val authenticator = NetworkModule.tokenAuthenticator(tokenProvider)
        val client = NetworkModule.okHttpClient(auth, logging, authenticator)

        assertTrue("AuthInterceptor must be installed", client.interceptors.any { it is AuthInterceptor })
        assertTrue("Logging interceptor must be installed", client.interceptors.any { it is HttpLoggingInterceptor })
        assertTrue("TokenAuthenticator must be installed", client.authenticator is TokenAuthenticator)
    }

    @Test
    fun `retrofit honors backend base url provider`() {
        val moshi = NetworkModule.moshi()
        val auth = NetworkModule.authInterceptor(tokenProvider)
        val logging = NetworkModule.loggingInterceptor()
        val authenticator = NetworkModule.tokenAuthenticator(tokenProvider)
        val client = NetworkModule.okHttpClient(auth, logging, authenticator)
        val retrofit = NetworkModule.retrofit(
            client,
            moshi,
            object : BackendBaseUrlProvider {
                override val baseUrl: String = "https://example.test/"
            },
        )

        assertEquals("https://example.test/", retrofit.baseUrl().toString())
    }

    @Test
    fun `sse factory derives from shared okhttp client`() {
        val auth = NetworkModule.authInterceptor(tokenProvider)
        val logging = NetworkModule.loggingInterceptor()
        val authenticator = NetworkModule.tokenAuthenticator(tokenProvider)
        val client = NetworkModule.okHttpClient(auth, logging, authenticator)

        val factory = NetworkModule.sseFactory(client)
        assertNotNull(factory)
    }
}
