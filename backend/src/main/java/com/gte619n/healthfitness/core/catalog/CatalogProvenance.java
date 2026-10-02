package com.gte619n.healthfitness.core.catalog;

import java.time.Instant;

/**
 * IMPL-MULTIUSER-01 P3.1 (D12) — internal-only promotion provenance attached to
 * a catalog copy of a user-created object.
 *
 * <p><b>Never surfaced to non-admin users.</b> Contributor credit is strictly
 * internal (D12): {@code contributorId} is recorded so admins can see who
 * authored a promoted template, but it is stripped from any public browse-shared
 * response. The curation console (admin, {@code @AdminOnly}) is the only read
 * surface for these fields.
 *
 * @param contributorId  the user who authored (and submitted) the source object
 * @param promotedBy      the admin who approved the promotion; null until approved
 * @param promotedAt      when the admin approved; null until approved
 * @param rejectedReason  free-text reason an admin declined; null unless REJECTED
 * @param aliasOfId       dedupe pointer — when non-null this catalog row is an
 *                        alias of another catalog id and is hidden from listings
 *                        (mirrors Equipment/Exercise {@code aliasOf...})
 */
public record CatalogProvenance(
    String contributorId,
    String promotedBy,
    Instant promotedAt,
    String rejectedReason,
    String aliasOfId
) {
    /** A fresh provenance stamped at submit time with only the contributor known. */
    public static CatalogProvenance submittedBy(String contributorId) {
        return new CatalogProvenance(contributorId, null, null, null, null);
    }

    /** Copy stamped as approved by {@code adminId} at {@code at}. */
    public CatalogProvenance approved(String adminId, Instant at) {
        return new CatalogProvenance(contributorId, adminId, at, null, aliasOfId);
    }

    /** Copy stamped as rejected by {@code adminId} with {@code reason}. */
    public CatalogProvenance rejected(String adminId, String reason) {
        return new CatalogProvenance(contributorId, adminId, promotedAt, reason, aliasOfId);
    }

    /** True when this row aliases another catalog id (hidden from listings). */
    public boolean isAlias() {
        return aliasOfId != null && !aliasOfId.isBlank();
    }
}
