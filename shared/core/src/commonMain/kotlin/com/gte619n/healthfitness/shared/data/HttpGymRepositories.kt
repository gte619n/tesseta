package com.gte619n.healthfitness.shared.data

import com.gte619n.healthfitness.shared.domain.common.DayOfWeek
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.content.ByteArrayContent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * IMPL-IOS-01 Phase 3 Wave D(iii) (iOS wiring) — online-first repositories for the
 * GYMS vertical: [LocationRepository], [EquipmentRepository], [GymScanRepository].
 * These back the three previously interface-only gym contracts so their shared
 * ViewModels (gyms list / detail / new / edit / scan) can finally be wired on iOS.
 *
 * All online-first over the EXISTING backend — endpoints matched 1:1 to Android's
 * `LocationApi` / `EquipmentApi` / `GymScanApi` (and verified against the backend
 * `LocationController` / `EquipmentController` / `GymVideoScanController`). No
 * offline mirror read yet (same deferral as the nutrition/goals/workouts passes):
 * the shared VMs' `cached*` seeds degrade to empty/null and the network read fills
 * the screen.
 *
 * WIRE SUBTLETIES (verified against the backend):
 *  - The gym-hours map serializes with **lowercase day keys** (`"mon"`…`"sun"`)
 *    via `DayOfWeekJacksonConfig`'s key (de)serializer — NOT the enum name. So the
 *    wire DTOs below key `hours` by a `String` and map to/from [DayOfWeek]
 *    explicitly, sidestepping kotlinx's default UPPERCASE enum-key codec.
 *  - `create` / `update` return a `WriteResult<LocationResponse>` whose body is
 *    `@JsonUnwrapped` — i.e. the LocationResponse fields FLAT plus a sibling
 *    `lastUpdate`. A lenient decode straight into [LocationWire] ignores
 *    `lastUpdate`; no envelope unwrap is needed.
 *  - There is NO per-equipment DELETE endpoint on the gym controller; equipment is
 *    detached by PATCHing the location with the reduced `equipmentIds` list.
 *  - The cover photo is a multipart POST to `…/gyms/{id}/photo` (field name
 *    `file`); the response is the updated LocationResponse carrying `coverPhotoUrl`.
 *  - The scan video is PUT directly to a signed GCS URL via a SEPARATE bare client
 *    (no Authorization / JSON content-type), mirroring Android's `SignedUploadClient`.
 */

private val gymJson = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

// --- Wire DTOs -------------------------------------------------------------

@Serializable
private data class HoursSlotWire(val open: String, val close: String)

@Serializable
private data class LocationWire(
    val locationId: String,
    val name: String,
    val address: String? = null,
    val coverPhotoUrl: String? = null,
    val is24Hours: Boolean = false,
    // Lowercase day-string keys on the wire ("mon"…"sun").
    val hours: Map<String, HoursSlotWire>? = null,
    val amenities: List<String> = emptyList(),
    val equipmentIds: List<String> = emptyList(),
    val isDefault: Boolean = false,
    val isActive: Boolean = true,
)

@Serializable
private data class CreateLocationBody(
    val name: String,
    val address: String? = null,
    val is24Hours: Boolean = false,
    val hours: Map<String, HoursSlotWire>? = null,
    val amenities: List<String> = emptyList(),
    val equipmentIds: List<String> = emptyList(),
)

@Serializable
private data class UpdateLocationBody(
    val name: String? = null,
    val address: String? = null,
    val is24Hours: Boolean? = null,
    val hours: Map<String, HoursSlotWire>? = null,
    val amenities: List<String>? = null,
    val equipmentIds: List<String>? = null,
)

@Serializable
private data class EquipmentWire(
    val equipmentId: String,
    val name: String,
    val category: String? = null,
)

// --- Mappers ---------------------------------------------------------------

private fun dayFromWire(key: String): DayOfWeek? =
    DayOfWeek.entries.firstOrNull { it.name.equals(key, ignoreCase = true) }

private fun dayToWire(day: DayOfWeek): String = day.name.lowercase()

private fun Map<String, HoursSlotWire>.toDomainHours(): Map<DayOfWeek, HoursSlot> =
    entries.mapNotNull { (k, v) -> dayFromWire(k)?.let { it to HoursSlot(v.open, v.close) } }.toMap()

private fun Map<DayOfWeek, HoursSlot>.toWireHours(): Map<String, HoursSlotWire> =
    entries.associate { (d, s) -> dayToWire(d) to HoursSlotWire(s.open, s.close) }

private fun LocationWire.toDomain(): Location = Location(
    locationId = locationId,
    name = name,
    address = address,
    coverPhotoUrl = coverPhotoUrl,
    is24Hours = is24Hours,
    hours = hours?.toDomainHours(),
    amenities = amenities.mapNotNull { Amenity.fromId(it) },
    equipmentIds = equipmentIds,
    isDefault = isDefault,
    isActive = isActive,
)

private fun EquipmentWire.toDomain(): Equipment =
    Equipment(equipmentId = equipmentId, name = name, category = category)

// ---------------------------------------------------------------------------
// Gyms (locations) — GET/POST/PATCH/DELETE api/me/gyms (+ /default, /photo).
// ---------------------------------------------------------------------------

class HttpLocationRepository(
    private val client: HttpClient,
) : LocationRepository {

    // No mirror read yet: the cached seeds degrade to empty/null so the VMs show a
    // spinner pre-first-fetch, then the network read fills them (online-first).
    override suspend fun cachedList(): List<Location> = emptyList()

    override suspend fun list(): Result<List<Location>> = runCatching {
        val wires: List<LocationWire> = client.get("api/me/gyms").body()
        wires.map { it.toDomain() }
    }

    override suspend fun cached(locationId: String): Location? = null

    override suspend fun get(locationId: String): Result<Location> = runCatching {
        client.get("api/me/gyms/$locationId").body<LocationWire>().toDomain()
    }

    override suspend fun create(request: CreateLocationRequest): Result<Location> = runCatching {
        val body = CreateLocationBody(
            name = request.name,
            address = request.address,
            is24Hours = request.is24Hours,
            hours = request.hours?.takeUnless { request.is24Hours }?.toWireHours(),
            amenities = request.amenities,
        )
        // WriteResult<LocationResponse> is @JsonUnwrapped → the LocationResponse
        // fields are flat; a lenient decode drops the sibling `lastUpdate`.
        client.post("api/me/gyms") { setBody(body) }.body<LocationWire>().toDomain()
    }

    override suspend fun update(locationId: String, request: UpdateLocationRequest): Result<Unit> = runCatching {
        val body = UpdateLocationBody(
            name = request.name,
            address = request.address,
            is24Hours = request.is24Hours,
            // On a 24-hour gym the hours are cleared (null); else send what's set.
            hours = if (request.is24Hours) null else request.hours?.toWireHours(),
            amenities = request.amenities,
        )
        client.patch("api/me/gyms/$locationId") { setBody(body) }
        Unit
    }

    override suspend fun delete(locationId: String): Result<Unit> = runCatching {
        client.delete("api/me/gyms/$locationId")
        Unit
    }

    override suspend fun setDefault(locationId: String): Result<Unit> = runCatching {
        client.post("api/me/gyms/$locationId/default")
        Unit
    }

    /**
     * No per-equipment DELETE endpoint exists on the gym controller — equipment is
     * detached by PATCHing the location with the reduced `equipmentIds` list. Reads
     * the current ids first (so a stale local list can't resurrect a removed id).
     */
    override suspend fun removeEquipment(locationId: String, equipmentId: String): Result<Unit> = runCatching {
        val current: LocationWire = client.get("api/me/gyms/$locationId").body()
        val remaining = current.equipmentIds.filterNot { it == equipmentId }
        client.patch("api/me/gyms/$locationId") {
            setBody(UpdateLocationBody(equipmentIds = remaining))
        }
        Unit
    }

    override suspend fun uploadCoverPhoto(locationId: String, file: PendingUpload): Result<String> = runCatching {
        // Relative path resolves against the client's base URL (defaultRequest); the
        // multipart body supplies its own Content-Type, overriding the JSON default.
        val response: HttpResponse = client.post("api/me/gyms/$locationId/photo") {
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append(
                            "file",
                            file.bytes,
                            Headers.build {
                                append(HttpHeaders.ContentType, file.contentType)
                                append(HttpHeaders.ContentDisposition, "filename=\"cover.jpg\"")
                            },
                        )
                    },
                ),
            )
        }
        // The endpoint returns the updated LocationResponse; pull the coverPhotoUrl.
        val wire = runCatching { gymJson.decodeFromString(LocationWire.serializer(), response.bodyAsText()) }.getOrNull()
        wire?.coverPhotoUrl ?: ""
    }

    override suspend fun deleteCoverPhoto(locationId: String): Result<Unit> = runCatching {
        client.delete("api/me/gyms/$locationId/photo")
        Unit
    }
}

// ---------------------------------------------------------------------------
// Equipment catalog — GET api/equipment/{id} (cache-first; no mirror yet).
// ---------------------------------------------------------------------------

class HttpEquipmentRepository(
    private val client: HttpClient,
) : EquipmentRepository {

    override suspend fun cached(equipmentId: String): Equipment? = null

    override suspend fun get(equipmentId: String): Result<Equipment> = runCatching {
        client.get("api/equipment/$equipmentId").body<EquipmentWire>().toDomain()
    }
}

// ---------------------------------------------------------------------------
// Gym video scan — register → upload (signed PUT) → start → poll → confirm.
//
//   POST   api/me/gyms/{id}/equipment/scan                 -> ScanRegisterResponse
//   PUT    <signed GCS url>                                (raw video bytes)
//   POST   api/me/gyms/{id}/equipment/scan/{scanId}/start
//   GET    api/me/gyms/{id}/equipment/scan/{scanId}        -> ScanStatusResponse
//   POST   api/me/gyms/{id}/equipment/scan/{scanId}/confirm -> BulkImportConfirmResponse
// ---------------------------------------------------------------------------

class HttpGymScanRepository(
    private val client: HttpClient,
    /** A bare client with NO auth/JSON defaults for the signed-URL PUT. */
    private val uploadClient: HttpClient,
) : GymScanRepository {

    override suspend fun register(
        locationId: String,
        mimeType: String,
        sizeBytes: Long,
    ): Result<ScanTarget> = runCatching {
        val resp: ScanRegisterWire = client.post("api/me/gyms/$locationId/equipment/scan") {
            setBody(ScanRegisterBody(mimeType = mimeType, sizeBytes = sizeBytes))
        }.body()
        ScanTarget(scanId = resp.scanId, uploadUrl = resp.uploadUrl)
    }

    override suspend fun uploadVideo(
        target: ScanTarget,
        mimeType: String,
        sizeBytes: Long,
        openStream: () -> ByteArray,
    ): Result<Unit> = runCatching {
        val bytes = openStream()
        // Direct-to-GCS signed PUT: no Authorization, content-type must match.
        uploadClient.put(target.uploadUrl) {
            setBody(ByteArrayContent(bytes, io.ktor.http.ContentType.parse(mimeType)))
        }
        Unit
    }

    override suspend fun start(locationId: String, scanId: String): Result<Unit> = runCatching {
        client.post("api/me/gyms/$locationId/equipment/scan/$scanId/start")
        Unit
    }

    override suspend fun status(locationId: String, scanId: String): Result<ScanStatus> = runCatching {
        val resp: ScanStatusWire = client.get("api/me/gyms/$locationId/equipment/scan/$scanId").body()
        ScanStatus(
            status = resp.status,
            preview = resp.preview?.toDomain(),
            error = resp.error,
        )
    }

    override suspend fun confirm(
        locationId: String,
        scanId: String,
        request: ScanConfirmRequest,
    ): Result<ScanConfirmResult> = runCatching {
        val body = ScanConfirmBody(
            items = request.items.map { item ->
                ScanConfirmItemBody(
                    index = item.index,
                    action = item.action,
                    matchedEquipmentId = item.matchedEquipmentId,
                    parsed = item.parsed?.let { ParsedEquipmentBody(name = it.name, category = it.category) },
                    overrides = item.overrides?.let { OverridesBody(it.name) },
                )
            },
        )
        val resp: ConfirmResponseWire =
            client.post("api/me/gyms/$locationId/equipment/scan/$scanId/confirm") { setBody(body) }.body()
        ScanConfirmResult(addedCount = resp.addedToLocation)
    }

    // --- scan wire DTOs ---

    @Serializable
    private data class ScanRegisterBody(val mimeType: String, val sizeBytes: Long)

    @Serializable
    private data class ScanRegisterWire(
        val scanId: String,
        val uploadUrl: String,
        val method: String = "PUT",
        val headers: Map<String, String> = emptyMap(),
        val status: String = "REGISTERED",
    )

    @Serializable
    private data class ScanStatusWire(
        val scanId: String? = null,
        val status: String,
        val detectedCount: Int? = null,
        val preview: PreviewWire? = null,
        val error: String? = null,
    )

    @Serializable
    private data class PreviewWire(
        val items: List<PreviewItemWire> = emptyList(),
    ) {
        fun toDomain() = ScanPreview(items = items.map { it.toDomain() })
    }

    @Serializable
    private data class PreviewItemWire(
        val index: Int,
        val action: String,
        val parsed: ParsedWire,
        val match: MatchWire? = null,
    ) {
        fun toDomain() = ScanPreviewItem(
            index = index,
            action = action,
            parsed = ParsedEquipment(name = parsed.name, category = parsed.category),
            match = match?.let { EquipmentMatch(equipmentId = it.equipmentId, name = it.name, score = it.score) },
        )
    }

    @Serializable
    private data class ParsedWire(
        val name: String,
        val category: String? = null,
    )

    @Serializable
    private data class MatchWire(
        val equipmentId: String,
        val name: String,
        val score: Double = 0.0,
        val reason: String = "",
    )

    @Serializable
    private data class ScanConfirmBody(val items: List<ScanConfirmItemBody>)

    @Serializable
    private data class ScanConfirmItemBody(
        val index: Int,
        val action: String,
        val matchedEquipmentId: String? = null,
        val parsed: ParsedEquipmentBody? = null,
        val overrides: OverridesBody? = null,
    )

    @Serializable
    private data class ParsedEquipmentBody(val name: String, val category: String? = null)

    @Serializable
    private data class OverridesBody(val name: String)

    @Serializable
    private data class ConfirmResponseWire(
        val addedToLocation: Int = 0,
        val skipped: Int = 0,
    )
}
