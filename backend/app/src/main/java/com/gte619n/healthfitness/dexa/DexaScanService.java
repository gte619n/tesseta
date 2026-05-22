package com.gte619n.healthfitness.dexa;

import com.gte619n.healthfitness.core.dexa.DexaRegion;
import com.gte619n.healthfitness.core.dexa.DexaScan;
import com.gte619n.healthfitness.core.dexa.DexaScanRepository;
import com.gte619n.healthfitness.integrations.dexa.DexaDuplicateException;
import com.gte619n.healthfitness.integrations.dexa.DexaExtraction;
import com.gte619n.healthfitness.integrations.dexa.DexaExtractor;
import com.gte619n.healthfitness.integrations.dexa.DexaPdfStorage;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

// Orchestrates a DEXA upload: store the PDF in GCS first (so we always
// have the original if extraction fails later), then run Gemini, then
// persist the parsed scan. Returns the saved DexaScan.
@Service
@ConditionalOnProperty(name = "app.dexa.enabled", havingValue = "true", matchIfMissing = true)
public class DexaScanService {

    private final DexaPdfStorage pdfStorage;
    private final DexaExtractor extractor;
    private final DexaScanRepository scans;

    public DexaScanService(
        DexaPdfStorage pdfStorage,
        DexaExtractor extractor,
        DexaScanRepository scans
    ) {
        this.pdfStorage = pdfStorage;
        this.extractor = extractor;
        this.scans = scans;
    }

    public DexaScan upload(String userId, byte[] pdfBytes) {
        return upload(userId, pdfBytes, phase -> {});
    }

    // Upload + extract + persist with phase callbacks for SSE clients.
    // Phase strings are stable and contract with the frontend:
    //   "uploading"  — PDF being written to GCS
    //   "extracting" — Gemini reading the PDF (the slow step)
    //   "saving"     — Firestore write
    public DexaScan upload(String userId, byte[] pdfBytes, Consumer<String> onPhase) {
        // Dedup BEFORE any expensive work. Same bytes for the same user
        // → reject up-front so we don't pay for storage or a Gemini call
        // on a duplicate.
        String contentHash = sha256Hex(pdfBytes);
        Optional<DexaScan> existing = scans.findByContentHash(userId, contentHash);
        if (existing.isPresent()) {
            DexaScan dup = existing.get();
            String when = dup.measuredOn() == null ? "earlier" : dup.measuredOn().toString();
            throw new DexaDuplicateException(
                "This DEXA report has already been uploaded (measured " + when + ").",
                dup.scanId());
        }

        onPhase.accept("uploading");
        String scanId = UUID.randomUUID().toString();
        String pdfPath = pdfStorage.upload(userId, scanId, pdfBytes);

        onPhase.accept("extracting");
        DexaExtraction extracted = extractor.extract(pdfBytes);

        onPhase.accept("saving");
        DexaScan scan = new DexaScan(
            userId,
            scanId,
            extracted.measuredOn(),
            extracted.sourceFacility(),
            pdfPath,
            contentHash,
            extracted.totalMassLb(),
            extracted.leanTissueLb(),
            extracted.fatTissueLb(),
            extracted.totalBodyFatPercent(),
            extracted.visceralFatLb(),
            extracted.androidGynoidRatio(),
            extracted.trunk(),
            extracted.android(),
            extracted.gynoid(),
            extracted.armsTotal(),
            extracted.armsRight(),
            extracted.armsLeft(),
            extracted.legsTotal(),
            extracted.legsRight(),
            extracted.legsLeft(),
            extracted.bmdTScore(),
            extracted.bmdZScore(),
            extracted.restingMetabolicRateKcal(),
            Instant.now(),
            Instant.now()
        );
        scans.save(scan);
        return scan;
    }

    // Whitelist of editable field paths. Anything not here is rejected
    // — keeps the API surface tight and avoids inadvertent edits to
    // immutable identity fields like scanId / userId / contentHash.
    private static final java.util.Set<String> EDITABLE_PATHS = java.util.Set.of(
        "totalMassLb", "leanTissueLb", "fatTissueLb", "totalBodyFatPercent",
        "visceralFatLb", "androidGynoidRatio",
        "bmdTScore", "bmdZScore",
        "trunk.totalMassLb", "trunk.leanTissueLb", "trunk.fatTissueLb", "trunk.regionFatPercent",
        "android.totalMassLb", "android.leanTissueLb", "android.fatTissueLb", "android.regionFatPercent",
        "gynoid.totalMassLb", "gynoid.leanTissueLb", "gynoid.fatTissueLb", "gynoid.regionFatPercent",
        "armsTotal.totalMassLb", "armsTotal.leanTissueLb", "armsTotal.fatTissueLb", "armsTotal.regionFatPercent",
        "armsRight.totalMassLb", "armsRight.leanTissueLb", "armsRight.fatTissueLb", "armsRight.regionFatPercent",
        "armsLeft.totalMassLb", "armsLeft.leanTissueLb", "armsLeft.fatTissueLb", "armsLeft.regionFatPercent",
        "legsTotal.totalMassLb", "legsTotal.leanTissueLb", "legsTotal.fatTissueLb", "legsTotal.regionFatPercent",
        "legsRight.totalMassLb", "legsRight.leanTissueLb", "legsRight.fatTissueLb", "legsRight.regionFatPercent",
        "legsLeft.totalMassLb", "legsLeft.leanTissueLb", "legsLeft.fatTissueLb", "legsLeft.regionFatPercent"
    );

    public DexaScan updateField(String userId, String scanId, String path, Double value) {
        if (!EDITABLE_PATHS.contains(path)) {
            throw new IllegalArgumentException("Unknown or read-only field: " + path);
        }
        DexaScan existing = scans.findById(userId, scanId).orElseThrow(
            () -> new java.util.NoSuchElementException("Scan not found"));
        DexaScan updated = applyPatch(existing, path, value);
        scans.save(updated);
        return updated;
    }

    public void delete(String userId, String scanId) {
        pdfStorage.delete(userId, scanId);
        scans.delete(userId, scanId);
    }

    // Records are immutable; produce a new DexaScan with the named
    // field set to `value`. Top-level paths set the field directly;
    // dotted paths (region.subfield) rebuild the region record with the
    // single subfield changed.
    private static DexaScan applyPatch(DexaScan s, String path, Double value) {
        if (!path.contains(".")) {
            return switch (path) {
                case "totalMassLb" -> withTopLevel(s, "totalMassLb", value);
                case "leanTissueLb" -> withTopLevel(s, "leanTissueLb", value);
                case "fatTissueLb" -> withTopLevel(s, "fatTissueLb", value);
                case "totalBodyFatPercent" -> withTopLevel(s, "totalBodyFatPercent", value);
                case "visceralFatLb" -> withTopLevel(s, "visceralFatLb", value);
                case "androidGynoidRatio" -> withTopLevel(s, "androidGynoidRatio", value);
                case "bmdTScore" -> withTopLevel(s, "bmdTScore", value);
                case "bmdZScore" -> withTopLevel(s, "bmdZScore", value);
                default -> throw new IllegalArgumentException(path);
            };
        }
        String[] parts = path.split("\\.", 2);
        String region = parts[0];
        String sub = parts[1];
        DexaRegion current = readRegion(s, region);
        DexaRegion next = patchRegion(current, sub, value);
        return withRegion(s, region, next);
    }

    private static DexaScan withTopLevel(DexaScan s, String field, Double v) {
        return new DexaScan(
            s.userId(), s.scanId(), s.measuredOn(), s.sourceFacility(),
            s.pdfStoragePath(), s.contentHash(),
            "totalMassLb".equals(field) ? v : s.totalMassLb(),
            "leanTissueLb".equals(field) ? v : s.leanTissueLb(),
            "fatTissueLb".equals(field) ? v : s.fatTissueLb(),
            "totalBodyFatPercent".equals(field) ? v : s.totalBodyFatPercent(),
            "visceralFatLb".equals(field) ? v : s.visceralFatLb(),
            "androidGynoidRatio".equals(field) ? v : s.androidGynoidRatio(),
            s.trunk(), s.android(), s.gynoid(),
            s.armsTotal(), s.armsRight(), s.armsLeft(),
            s.legsTotal(), s.legsRight(), s.legsLeft(),
            "bmdTScore".equals(field) ? v : s.bmdTScore(),
            "bmdZScore".equals(field) ? v : s.bmdZScore(),
            s.restingMetabolicRateKcal(),
            s.createdAt(), Instant.now()
        );
    }

    private static DexaRegion readRegion(DexaScan s, String name) {
        return switch (name) {
            case "trunk" -> s.trunk();
            case "android" -> s.android();
            case "gynoid" -> s.gynoid();
            case "armsTotal" -> s.armsTotal();
            case "armsRight" -> s.armsRight();
            case "armsLeft" -> s.armsLeft();
            case "legsTotal" -> s.legsTotal();
            case "legsRight" -> s.legsRight();
            case "legsLeft" -> s.legsLeft();
            default -> throw new IllegalArgumentException(name);
        };
    }

    private static DexaRegion patchRegion(DexaRegion r, String sub, Double v) {
        Double total = r == null ? null : r.totalMassLb();
        Double lean = r == null ? null : r.leanTissueLb();
        Double fat = r == null ? null : r.fatTissueLb();
        Double pct = r == null ? null : r.regionFatPercent();
        return switch (sub) {
            case "totalMassLb" -> new DexaRegion(v, lean, fat, pct);
            case "leanTissueLb" -> new DexaRegion(total, v, fat, pct);
            case "fatTissueLb" -> new DexaRegion(total, lean, v, pct);
            case "regionFatPercent" -> new DexaRegion(total, lean, fat, v);
            default -> throw new IllegalArgumentException(sub);
        };
    }

    private static DexaScan withRegion(DexaScan s, String name, DexaRegion r) {
        DexaRegion trunk = "trunk".equals(name) ? r : s.trunk();
        DexaRegion android = "android".equals(name) ? r : s.android();
        DexaRegion gynoid = "gynoid".equals(name) ? r : s.gynoid();
        DexaRegion armsTotal = "armsTotal".equals(name) ? r : s.armsTotal();
        DexaRegion armsRight = "armsRight".equals(name) ? r : s.armsRight();
        DexaRegion armsLeft = "armsLeft".equals(name) ? r : s.armsLeft();
        DexaRegion legsTotal = "legsTotal".equals(name) ? r : s.legsTotal();
        DexaRegion legsRight = "legsRight".equals(name) ? r : s.legsRight();
        DexaRegion legsLeft = "legsLeft".equals(name) ? r : s.legsLeft();
        return new DexaScan(
            s.userId(), s.scanId(), s.measuredOn(), s.sourceFacility(),
            s.pdfStoragePath(), s.contentHash(),
            s.totalMassLb(), s.leanTissueLb(), s.fatTissueLb(), s.totalBodyFatPercent(),
            s.visceralFatLb(), s.androidGynoidRatio(),
            trunk, android, gynoid, armsTotal, armsRight, armsLeft,
            legsTotal, legsRight, legsLeft,
            s.bmdTScore(), s.bmdZScore(),
            s.restingMetabolicRateKcal(),
            s.createdAt(), Instant.now()
        );
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed by the JDK spec.
            throw new IllegalStateException(e);
        }
    }
}
