package com.gte619n.uat.support

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Duration

/**
 * Client-agnostic core of the UAT harness: bootstraps a session via the
 * backend's dev-login, seeds/reads data through the real REST API, and resets
 * the Firestore emulator between runs. The same contract (dev-login + these
 * seed calls + the emulator wipe) is what a future Android instrumented suite
 * reuses — Selenium and Android differ only in how they render, not in how the
 * world is set up.
 */
class UatBackendClient(
    private val backendBaseUrl: String = UatConfig.backendBaseUrl,
    private val emulatorHost: String = UatConfig.firestoreEmulatorHost,
    private val projectId: String = UatConfig.firestoreProjectId,
) {
    private val http = OkHttpClient.Builder()
        .callTimeout(Duration.ofSeconds(30))
        .build()
    private val json = jacksonObjectMapper()
    private val JSON = "application/json".toMediaType()

    /** Mint a real backend session token for a test identity (no Google). */
    fun devLogin(userId: String, email: String = "$userId@uat.local", name: String = userId): String {
        val body = json.writeValueAsString(mapOf("userId" to userId, "email" to email, "name" to name))
        post("/api/auth/dev-login", null, body).use { res ->
            check(res.isSuccessful) { "dev-login failed: ${res.code}" }
            return json.readTree(res.body!!.string()).get("accessToken").asText()
        }
    }

    // --- Seeders (exercise the real write paths the UI uses) ---

    fun seedGoal(token: String, title: String, domain: String = "STRENGTH"): String {
        val body = json.writeValueAsString(mapOf("title" to title, "domain" to domain))
        post("/api/me/goals", token, body).use { res ->
            check(res.isSuccessful) { "seedGoal failed: ${res.code} ${res.body?.string()}" }
            return json.readTree(res.body!!.string()).get("goalId").asText()
        }
    }

    fun seedBloodReading(token: String, marker: String, value: Double, unit: String, sampleDate: String): String {
        val body = json.writeValueAsString(
            mapOf("marker" to marker, "value" to value, "unit" to unit, "sampleDate" to sampleDate),
        )
        post("/api/me/blood", token, body).use { res ->
            check(res.isSuccessful) { "seedBloodReading failed: ${res.code} ${res.body?.string()}" }
            return json.readTree(res.body!!.string()).path("readingId").asText("")
        }
    }

    /** Generic GET returning parsed JSON, for assertions that read state back. */
    fun getJson(path: String, token: String): JsonNode {
        val req = Request.Builder().url(url(path)).header("Authorization", "Bearer $token").get().build()
        http.newCall(req).execute().use { res ->
            check(res.isSuccessful) { "GET $path failed: ${res.code}" }
            return json.readTree(res.body!!.string())
        }
    }

    /** Wipe ALL emulator documents — call between suites for a clean slate. */
    fun wipeEmulator() {
        val url = "http://$emulatorHost/emulator/v1/projects/$projectId/databases/(default)/documents"
        val req = Request.Builder().url(url).delete().build()
        http.newCall(req).execute().use { res ->
            check(res.isSuccessful) { "emulator wipe failed: ${res.code}" }
        }
    }

    private fun post(path: String, token: String?, body: String): okhttp3.Response {
        val builder = Request.Builder().url(url(path)).post(body.toRequestBody(JSON))
        if (token != null) builder.header("Authorization", "Bearer $token")
        return http.newCall(builder.build()).execute()
    }

    private fun url(path: String) = backendBaseUrl.trimEnd('/') + path
}
