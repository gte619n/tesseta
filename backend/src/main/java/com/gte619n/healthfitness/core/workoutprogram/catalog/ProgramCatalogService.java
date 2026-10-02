package com.gte619n.healthfitness.core.workoutprogram.catalog;

import com.gte619n.healthfitness.core.catalog.CatalogProvenance;
import com.gte619n.healthfitness.core.catalog.CatalogStatus;
import com.gte619n.healthfitness.core.workoutprogram.ProgramPhase;
import com.gte619n.healthfitness.core.workoutprogram.ProgramStatus;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgram;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgramService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * IMPL-MULTIUSER-01 P3.2 — graduation of a user {@code WorkoutProgram} into the
 * shared app-wide program catalog (D7/D9/D10/D11).
 *
 * <p>{@code submitForPromotion} performs the D9 <b>copy</b>: it reads the user's
 * program (through the existing {@link WorkoutProgramService} public API only),
 * strips the per-user fields into a {@link CatalogWorkoutProgram}, runs it
 * through {@link ProgramGeneralizer} (D10), and persists it as
 * {@code PENDING_REVIEW}. The source program is never mutated.
 *
 * <p>{@code approve}/{@code reject} are the admin sign-off (D7).
 *
 * <p>{@code adopt} is the D11 <b>snapshot</b>: it mints a brand-new per-user
 * {@code WorkoutProgram} from the published template and writes it via
 * {@code WorkoutProgramService.create}. The adopted copy shares no live link to
 * the catalog row — later edits to either are independent.
 */
@Service
public class ProgramCatalogService {

    private final CatalogWorkoutProgramRepository catalog;
    private final WorkoutProgramService programs;

    public ProgramCatalogService(
        CatalogWorkoutProgramRepository catalog,
        WorkoutProgramService programs
    ) {
        this.catalog = catalog;
        this.programs = programs;
    }

    // ---- promotion (user → catalog) ----

    /**
     * Copy a user's program into the catalog as {@code PENDING_REVIEW}, stripping
     * per-user fields and generalizing loads (D9/D10). Returns the catalog copy.
     */
    public CatalogWorkoutProgram submitForPromotion(String userId, String programId) {
        WorkoutProgram source = programs.findById(userId, programId)
            .orElseThrow(() -> new IllegalArgumentException("Program not found: " + programId));
        Instant now = Instant.now();
        List<ProgramPhase> generalized = ProgramGeneralizer.generalizePhases(source.phases());
        CatalogWorkoutProgram copy = new CatalogWorkoutProgram(
            "pc_" + shortId(),
            source.title(),
            source.description(),
            // schedule carries training-day/gym intent; gym ids are per-user but
            // harmless metadata — the generalizer strips the authoritative gym
            // binding from each day (locationId). Keep training-day cadence.
            source.schedule(),
            source.phaseOrder(),
            generalized,
            CatalogStatus.PENDING_REVIEW,
            CatalogProvenance.submittedBy(userId),
            now,
            now
        );
        catalog.save(copy);
        return copy;
    }

    // ---- admin curation ----

    public CatalogWorkoutProgram approve(String catalogId, String adminId) {
        CatalogWorkoutProgram e = require(catalogId);
        Instant now = Instant.now();
        CatalogWorkoutProgram published = new CatalogWorkoutProgram(
            e.catalogId(), e.title(), e.description(), e.schedule(), e.phaseOrder(), e.phases(),
            CatalogStatus.PUBLISHED, e.provenance().approved(adminId, now),
            e.createdAt(), now
        );
        catalog.save(published);
        return published;
    }

    public CatalogWorkoutProgram reject(String catalogId, String adminId, String reason) {
        CatalogWorkoutProgram e = require(catalogId);
        CatalogWorkoutProgram rejected = new CatalogWorkoutProgram(
            e.catalogId(), e.title(), e.description(), e.schedule(), e.phaseOrder(), e.phases(),
            CatalogStatus.REJECTED, e.provenance().rejected(adminId, reason),
            e.createdAt(), Instant.now()
        );
        catalog.save(rejected);
        return rejected;
    }

    // ---- browse-shared (PUBLISHED + non-aliased only) ----

    public List<CatalogWorkoutProgram> listPublished() {
        return catalog.findByStatus(CatalogStatus.PUBLISHED).stream()
            .filter(c -> c.provenance() == null || !c.provenance().isAlias())
            .toList();
    }

    public List<CatalogWorkoutProgram> listPendingReview() {
        return catalog.findByStatus(CatalogStatus.PENDING_REVIEW);
    }

    public Optional<CatalogWorkoutProgram> findPublished(String catalogId) {
        return catalog.findById(catalogId)
            .filter(c -> c.status() == CatalogStatus.PUBLISHED)
            .filter(c -> c.provenance() == null || !c.provenance().isAlias());
    }

    // ---- adopt (catalog → user, D11 snapshot copy) ----

    /**
     * Snapshot-copy a PUBLISHED template into {@code userId}'s own programs. The
     * new program is a fresh DRAFT with its own id/dates; it holds no reference
     * back to the catalog row (D11), so later edits to either are independent.
     */
    public WorkoutProgram adopt(String userId, String catalogId) {
        CatalogWorkoutProgram template = findPublished(catalogId)
            .orElseThrow(() -> new IllegalArgumentException(
                "Published catalog program not found: " + catalogId));
        WorkoutProgram seed = new WorkoutProgram(
            userId,
            null,                            // service mints a fresh programId
            template.title(),
            template.description(),
            null,                            // goalId — not linked on adopt
            ProgramStatus.DRAFT,
            com.gte619n.healthfitness.core.workoutprogram.ProgramSource.MANUAL,
            null,                            // startDate — service defaults to today
            template.schedule(),
            template.phaseOrder(),
            template.phases(),
            null, null, null
        );
        return programs.create(seed);
    }

    private CatalogWorkoutProgram require(String catalogId) {
        return catalog.findById(catalogId)
            .orElseThrow(() -> new IllegalArgumentException(
                "Catalog program not found: " + catalogId));
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 12);
    }
}
