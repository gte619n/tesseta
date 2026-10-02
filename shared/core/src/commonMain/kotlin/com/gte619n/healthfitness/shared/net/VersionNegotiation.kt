package com.gte619n.healthfitness.shared.net

import kotlinx.serialization.Serializable

/**
 * IMPL-IOS-01 Phase 0B (XPLAT-002) — minimum-client-version handshake, shared by
 * Android and iOS. "Two continuously-deployed-against clients is survivable;
 * three is not" (ios-client-strategy.md): before a third client ships, the
 * backend must be able to tell an out-of-date client to upgrade rather than let
 * it silently speak a stale contract.
 *
 * Wire protocol (additive, backend-side implemented in the 0B backend PR — see
 * docs/plans/IMPL-IOS-01-decision-log.md D-EXEC-3 for the exact endpoint spec):
 *   - Every request carries `X-Client: <platform>/<buildNumber>` (e.g.
 *     `ios/142`, `android/873`).
 *   - The backend compares against its configured floor per platform. If the
 *     client is below the floor it responds 426 Upgrade Required with an
 *     [UpgradeInfo] body; the client shows a blocking "please update" screen.
 *   - A soft floor (below-recommended-but-allowed) rides back on a
 *     `X-Client-Upgrade: recommended` response header for a non-blocking nudge.
 */
enum class ClientPlatform(val wire: String) { ANDROID("android"), IOS("ios") }

/** The `X-Client` header value builder — one place, both platforms. */
fun clientHeader(platform: ClientPlatform, buildNumber: Int): String =
    "${platform.wire}/$buildNumber"

/** Body of a 426 Upgrade Required response. */
@Serializable
data class UpgradeInfo(
    val minBuild: Int,
    val recommendedBuild: Int,
    val message: String,
    val storeUrl: String? = null,
)

/** The client's local verdict after a request completes. */
enum class VersionVerdict { OK, UPGRADE_RECOMMENDED, UPGRADE_REQUIRED }

object VersionNegotiation {
    const val HEADER_CLIENT = "X-Client"
    const val HEADER_UPGRADE = "X-Client-Upgrade"
    const val HTTP_UPGRADE_REQUIRED = 426

    /** Map an HTTP status + upgrade header into a verdict the UI acts on. */
    fun verdict(httpStatus: Int, upgradeHeader: String?): VersionVerdict = when {
        httpStatus == HTTP_UPGRADE_REQUIRED -> VersionVerdict.UPGRADE_REQUIRED
        upgradeHeader == "recommended" -> VersionVerdict.UPGRADE_RECOMMENDED
        else -> VersionVerdict.OK
    }
}
