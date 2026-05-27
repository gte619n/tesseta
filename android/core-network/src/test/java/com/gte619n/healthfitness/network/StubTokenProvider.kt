package com.gte619n.healthfitness.network

/**
 * Hand-rolled stub used by JVM unit tests for the auth-aware OkHttp stack.
 * We don't pull in Mockito or MockK because the contract is tiny and
 * `refresh()` needs explicit call-count assertions in the authenticator
 * tests.
 */
class StubTokenProvider(
    private val current: String? = null,
    private val refreshResults: List<String?> = emptyList(),
) : AuthTokenProvider {

    var refreshCount: Int = 0
        private set

    override suspend fun currentToken(): String? = current

    override suspend fun refresh(): String? {
        val index = refreshCount
        refreshCount += 1
        return if (index < refreshResults.size) refreshResults[index] else null
    }
}
