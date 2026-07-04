package com.gte619n.healthfitness.data.db.entity

import androidx.room.Entity

/**
 * offline-fix — a bounded local cache of **reference/catalog entities the user
 * has actually fetched** (equipment, foods, drugs, …). NOT a mirror table and NOT
 * wired into the sync engine (no `syncState`, no outbox, no delta cursor): it is a
 * plain, best-effort read cache so the network-only catalog repositories can serve
 * a previously-seen detail INSTANTLY on re-entry and survive offline, per ADR-0018.
 *
 * One table serves all the catalog repos; [type] namespaces the rows (e.g.
 * "equipment", "food", "drug") so a single migration + DAO covers every catalog.
 * [json] is the Moshi-serialized wire/domain object (opaque to the DB layer);
 * [updatedAt] is the device epoch-millis of the last fetch (freshness only — it is
 * never a sync cursor). Bounded by construction: the repos only upsert the exact
 * items they fetch, so the table grows with what the user has browsed, not with the
 * full backend catalog.
 */
@Entity(tableName = "catalog_cache", primaryKeys = ["type", "id"])
data class CatalogCacheEntity(
    val type: String,
    val id: String,
    val json: String,
    val updatedAt: Long,
)
