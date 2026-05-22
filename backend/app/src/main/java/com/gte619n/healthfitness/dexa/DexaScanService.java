package com.gte619n.healthfitness.dexa;

import com.gte619n.healthfitness.core.dexa.DexaScan;
import com.gte619n.healthfitness.core.dexa.DexaScanRepository;
import com.gte619n.healthfitness.integrations.dexa.DexaExtraction;
import com.gte619n.healthfitness.integrations.dexa.DexaExtractor;
import com.gte619n.healthfitness.integrations.dexa.DexaPdfStorage;
import java.time.Instant;
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

    public void delete(String userId, String scanId) {
        pdfStorage.delete(userId, scanId);
        scans.delete(userId, scanId);
    }
}
