package com.gte619n.healthfitness.core.adhoc.catalog;

import com.gte619n.healthfitness.core.adhoc.AdHocSource;
import com.gte619n.healthfitness.core.adhoc.AdHocWorkout;
import com.gte619n.healthfitness.core.adhoc.AdHocWorkoutService;
import com.gte619n.healthfitness.core.catalog.CatalogProvenance;
import com.gte619n.healthfitness.core.catalog.CatalogStatus;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutDay;
import com.gte619n.healthfitness.core.workoutprogram.catalog.ProgramGeneralizer;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * IMPL-MULTIUSER-01 P3.3 — graduation of a user {@code AdHocWorkout} into the
 * shared app-wide ad-hoc catalog. Parallels {@code ProgramCatalogService}
 * (D7/D9/D10/D11). Reads/writes the user's templates only through the existing
 * {@link AdHocWorkoutService} public API.
 */
@Service
public class AdHocCatalogService {

    private final CatalogAdHocWorkoutRepository catalog;
    private final AdHocWorkoutService adhoc;

    public AdHocCatalogService(
        CatalogAdHocWorkoutRepository catalog,
        AdHocWorkoutService adhoc
    ) {
        this.catalog = catalog;
        this.adhoc = adhoc;
    }

    // ---- promotion (user → catalog) ----

    public CatalogAdHocWorkout submitForPromotion(String userId, String adhocId) {
        AdHocWorkout source = adhoc.findById(userId, adhocId)
            .orElseThrow(() -> new IllegalArgumentException("Ad-hoc workout not found: " + adhocId));
        Instant now = Instant.now();
        WorkoutDay generalized = ProgramGeneralizer.generalizeDay(source.day());
        CatalogAdHocWorkout copy = new CatalogAdHocWorkout(
            "ac_" + shortId(),
            source.title(),
            source.summary(),
            source.tags(),
            generalized,
            source.targetDurationMinutes(),
            source.estimatedDurationSeconds(),
            CatalogStatus.PENDING_REVIEW,
            CatalogProvenance.submittedBy(userId),
            now,
            now
        );
        catalog.save(copy);
        return copy;
    }

    // ---- admin curation ----

    public CatalogAdHocWorkout approve(String catalogId, String adminId) {
        CatalogAdHocWorkout e = require(catalogId);
        Instant now = Instant.now();
        CatalogAdHocWorkout published = new CatalogAdHocWorkout(
            e.catalogId(), e.title(), e.summary(), e.tags(), e.day(),
            e.targetDurationMinutes(), e.estimatedDurationSeconds(),
            CatalogStatus.PUBLISHED, e.provenance().approved(adminId, now),
            e.createdAt(), now
        );
        catalog.save(published);
        return published;
    }

    public CatalogAdHocWorkout reject(String catalogId, String adminId, String reason) {
        CatalogAdHocWorkout e = require(catalogId);
        CatalogAdHocWorkout rejected = new CatalogAdHocWorkout(
            e.catalogId(), e.title(), e.summary(), e.tags(), e.day(),
            e.targetDurationMinutes(), e.estimatedDurationSeconds(),
            CatalogStatus.REJECTED, e.provenance().rejected(adminId, reason),
            e.createdAt(), Instant.now()
        );
        catalog.save(rejected);
        return rejected;
    }

    // ---- browse-shared (PUBLISHED + non-aliased only) ----

    public List<CatalogAdHocWorkout> listPublished() {
        return catalog.findByStatus(CatalogStatus.PUBLISHED).stream()
            .filter(c -> c.provenance() == null || !c.provenance().isAlias())
            .toList();
    }

    public List<CatalogAdHocWorkout> listPendingReview() {
        return catalog.findByStatus(CatalogStatus.PENDING_REVIEW);
    }

    public Optional<CatalogAdHocWorkout> findPublished(String catalogId) {
        return catalog.findById(catalogId)
            .filter(c -> c.status() == CatalogStatus.PUBLISHED)
            .filter(c -> c.provenance() == null || !c.provenance().isAlias());
    }

    // ---- adopt (catalog → user, D11 snapshot copy) ----

    public AdHocWorkout adopt(String userId, String catalogId) {
        CatalogAdHocWorkout template = findPublished(catalogId)
            .orElseThrow(() -> new IllegalArgumentException(
                "Published catalog ad-hoc workout not found: " + catalogId));
        AdHocWorkout seed = new AdHocWorkout(
            userId,
            null,                            // service mints a fresh adhocId
            template.title(),
            template.summary(),
            AdHocSource.MANUAL,
            null,                            // prompt not carried
            null,                            // equipmentContext — service defaults to empty
            template.targetDurationMinutes(),
            template.estimatedDurationSeconds(),
            template.tags(),
            false,
            template.day(),
            0,
            null,
            null,
            null
        );
        return adhoc.create(seed);
    }

    private CatalogAdHocWorkout require(String catalogId) {
        return catalog.findById(catalogId)
            .orElseThrow(() -> new IllegalArgumentException(
                "Catalog ad-hoc workout not found: " + catalogId));
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 12);
    }
}
