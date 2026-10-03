package com.gte619n.healthfitness.shared.data

import com.gte619n.healthfitness.shared.domain.nutrition.Macros
import com.gte619n.healthfitness.shared.presentation.settings.DrinkProposal
import com.gte619n.healthfitness.shared.presentation.settings.Food
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable

/**
 * IMPL-IOS-01 — online-first concrete implementation of [DrinkRepository] over the
 * EXISTING backend endpoints Android's `data/nutrition/DrinkApi.kt` uses (IMPL-DRINK-01,
 * the Settings › Drinks MANAGEMENT surface: list / analyze / create / update /
 * regenerate-image / reorder / archive). Mirrors the shape of the other Http* repos:
 * plain suspend calls over the one authenticated Ktor client; no offline mirror/outbox
 * (that is the later sync layer). The shared [DrinkSettingsViewModel] owns all the
 * management logic + the PENDING-image poll, so this repo is deliberately thin.
 *
 * Wire shapes (verified against the Android `DrinkApi` contract — same backend):
 *  - list   `GET  api/me/drinks` → bare `List<FoodResponse>` == [Food] (settings DTO,
 *    carries the `alcohol` + `servingMacros` blocks; the backend's extra `alcoholGrams`
 *    macro key is dropped by `ignoreUnknownKeys`).
 *  - analyze `POST api/me/drinks/analyze` {name} → [DrinkProposal]; HTTP 422 == AI
 *    unavailable → the [DrinkRepository.AnalyzeResult.Unavailable] manual-entry fallback.
 *  - create `POST api/me/drinks` {DrinkUpsertRequest} → [Food] (201; kicks image gen).
 *  - update `PUT  api/me/drinks/{id}` {DrinkUpsertRequest} → [Food] (re-derives alcohol).
 *  - regen  `POST api/me/drinks/{id}/image/regenerate` → [Food] (202).
 *  - reorder `PUT api/me/drinks/order` {orderedIds} → 204.
 *  - archive `DELETE api/me/drinks/{id}` → 204.
 *
 * Under `expectSuccess = true` a non-2xx throws [ClientRequestException]; [analyze]
 * catches it to split out the 422 path, everything else lets the throw propagate to the
 * VM's `runCatching`. No AI call here uses SSE (analyze is a single JSON round-trip, image
 * regeneration is a fire-and-poll the VM already drives), so nothing degrades.
 */
class HttpDrinkRepository(private val client: HttpClient) : DrinkRepository {

    override suspend fun listMyDrinks(): List<Food> =
        client.get("api/me/drinks").body()

    override suspend fun analyze(name: String): DrinkRepository.AnalyzeResult {
        return try {
            val proposal: DrinkProposal =
                client.post("api/me/drinks/analyze") { setBody(AnalyzeDrinkWire(name)) }.body()
            DrinkRepository.AnalyzeResult.Success(proposal)
        } catch (e: ClientRequestException) {
            if (e.response.status == HttpStatusCode.UnprocessableEntity) {
                DrinkRepository.AnalyzeResult.Unavailable
            } else {
                DrinkRepository.AnalyzeResult.Error(e)
            }
        } catch (e: Throwable) {
            DrinkRepository.AnalyzeResult.Error(e)
        }
    }

    override suspend fun createDrink(
        name: String,
        abvPercent: Double,
        servingVolumeMl: Double,
        servingLabel: String?,
        macros: Macros?,
    ): Food =
        client.post("api/me/drinks") {
            setBody(DrinkUpsertWire(name, abvPercent, servingVolumeMl, servingLabel, macros))
        }.body()

    override suspend fun updateDrink(
        id: String,
        name: String,
        abvPercent: Double,
        servingVolumeMl: Double,
        servingLabel: String?,
        macros: Macros?,
    ): Food =
        client.put("api/me/drinks/$id") {
            setBody(DrinkUpsertWire(name, abvPercent, servingVolumeMl, servingLabel, macros))
        }.body()

    override suspend fun regenerateImage(id: String): Food =
        client.post("api/me/drinks/$id/image/regenerate").body()

    override suspend fun reorder(orderedIds: List<String>) {
        client.put("api/me/drinks/order") { setBody(ReorderDrinksWire(orderedIds)) }
    }

    override suspend fun archiveDrink(id: String) {
        client.delete("api/me/drinks/$id")
    }
}

// --- request wire DTOs (match the Android DrinkApi bodies 1:1) ---------------

/** Body for `POST api/me/drinks/analyze`. */
@Serializable
private data class AnalyzeDrinkWire(val name: String)

/**
 * Body for `POST api/me/drinks` + `PUT api/me/drinks/{id}`. ABV% + serving volume are
 * required; [macros] is the per-serving MIXER contribution (carbs/sugar — the backend
 * adds the alcohol calories). `explicitNulls = false` on the shared Json omits the null
 * fields, matching the Android Moshi body.
 */
@Serializable
private data class DrinkUpsertWire(
    val name: String,
    val abvPercent: Double,
    val servingVolumeMl: Double,
    val servingLabel: String? = null,
    val macros: Macros? = null,
)

/** Body for `PUT api/me/drinks/order` — my drink ids in the desired order. */
@Serializable
private data class ReorderDrinksWire(val orderedIds: List<String>)
