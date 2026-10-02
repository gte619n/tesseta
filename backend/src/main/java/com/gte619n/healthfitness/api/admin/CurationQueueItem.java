package com.gte619n.healthfitness.api.admin;

import com.gte619n.healthfitness.core.catalog.CatalogStatus;
import java.time.Instant;

/**
 * IMPL-MULTIUSER-01 P3.5 — one row in the unified admin curation queue,
 * normalized to the shared {@link CatalogStatus} vocabulary across every catalog
 * type. This is an admin-only DTO (returned under {@code @AdminOnly}), so it MAY
 * carry internal provenance ({@code contributorId}) — that field must never
 * appear on a non-admin response (D12).
 *
 * @param type          "program" | "adhoc" | "equipment" | "exercise" | "food"
 * @param id            the entity/catalog id to act on via approve/reject
 * @param title         display title
 * @param status        normalized lifecycle status
 * @param contributorId internal-only contributor credit (D12); may be null
 * @param submittedAt   when the row entered review; may be null
 */
public record CurationQueueItem(
    String type,
    String id,
    String title,
    CatalogStatus status,
    String contributorId,
    Instant submittedAt
) {}
