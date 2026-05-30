package com.gte619n.healthfitness.persistence.medication;

import static com.gte619n.healthfitness.persistence.FirestoreMapper.serverTimestamp;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.toInstant;

import com.gte619n.healthfitness.core.medication.*;
import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.SetOptions;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * Firestore-backed medication repository.
 * Documents live at users/{userId}/medications/{medicationId}.
 */
@Repository
@ConditionalOnProperty(name = "app.persistence.firestore-enabled", havingValue = "true", matchIfMissing = true)
public class MedicationRepositoryImpl implements MedicationRepository {

    private static final String SUBCOLLECTION = "medications";

    private final Firestore firestore;

    public MedicationRepositoryImpl(Firestore firestore) {
        this.firestore = firestore;
    }

    @Override
    public Optional<Medication> findById(String userId, String medicationId) {
        DocumentSnapshot snapshot = await(collection(userId).document(medicationId).get());
        if (!snapshot.exists()) return Optional.empty();
        return Optional.of(toMedication(userId, snapshot));
    }

    @Override
    public List<Medication> findByUser(String userId) {
        List<QueryDocumentSnapshot> docs = await(collection(userId)
            .orderBy("startDate", Query.Direction.DESCENDING)
            .limit(500)
            .get()).getDocuments();
        return docs.stream().map(d -> toMedication(userId, d)).toList();
    }

    @Override
    public List<Medication> findByUserAndStatus(String userId, MedicationStatus status) {
        List<QueryDocumentSnapshot> docs = await(collection(userId)
            .whereEqualTo("status", status.name())
            .orderBy("startDate", Query.Direction.DESCENDING)
            .limit(500)
            .get()).getDocuments();
        return docs.stream().map(d -> toMedication(userId, d)).toList();
    }

    @Override
    public List<Medication> findByProtocol(String userId, String protocolId) {
        List<QueryDocumentSnapshot> docs = await(collection(userId)
            .whereEqualTo("protocolId", protocolId)
            .limit(100)
            .get()).getDocuments();
        return docs.stream().map(d -> toMedication(userId, d)).toList();
    }

    @Override
    public void save(Medication medication) {
        DocumentReference docRef = collection(medication.userId()).document(medication.medicationId());
        DocumentSnapshot existing = await(docRef.get());
        Map<String, Object> body = toBody(medication, !existing.exists());
        await(docRef.set(body, SetOptions.merge()));
    }

    @Override
    public void delete(String userId, String medicationId) {
        await(collection(userId).document(medicationId).delete());
    }

    @Override
    public List<Medication> findAllReferencingDrug(String drugId) {
        if (drugId == null || drugId.isBlank()) return List.of();
        List<QueryDocumentSnapshot> docs = await(
            firestore.collectionGroup(SUBCOLLECTION)
                .whereEqualTo("drugId", drugId)
                .get()
        ).getDocuments();
        return docs.stream()
            .map(d -> {
                String userId = d.getReference().getParent().getParent().getId();
                return toMedication(userId, d);
            })
            .toList();
    }

    private CollectionReference collection(String userId) {
        return firestore.collection("users").document(userId).collection(SUBCOLLECTION);
    }

    @SuppressWarnings("unchecked")
    private Medication toMedication(String userId, DocumentSnapshot snapshot) {
        // Parse frequency config
        Map<String, Object> freqMap = (Map<String, Object>) snapshot.get("frequency");
        FrequencyConfig frequency = parseFrequencyConfig(freqMap);

        // Parse time slots
        List<Map<String, Object>> slotsRaw = (List<Map<String, Object>>) snapshot.get("timeSlots");
        List<TimeSlot> timeSlots = slotsRaw != null
            ? slotsRaw.stream().map(this::parseTimeSlot).toList()
            : List.of();

        String endDateStr = snapshot.getString("endDate");
        String discontinueReasonStr = snapshot.getString("discontinueReason");

        // Dosage periods: reconstruct from stored array, or migrate legacy docs
        // (no dosagePeriods field) by synthesizing a single open-ended period from
        // the denormalized dose/unit/startDate.
        Double dose = snapshot.getDouble("dose");
        String unit = snapshot.getString("unit");
        LocalDate startDate = LocalDate.parse(snapshot.getString("startDate"));
        List<Map<String, Object>> periodsRaw =
            (List<Map<String, Object>>) snapshot.get("dosagePeriods");
        List<DosagePeriod> dosagePeriods = periodsRaw != null && !periodsRaw.isEmpty()
            ? periodsRaw.stream().map(this::parseDosagePeriod).toList()
            : List.of(DosagePeriod.initial(dose != null ? dose : 0.0, unit, startDate));

        return new Medication(
            userId,
            snapshot.getId(),
            snapshot.getString("drugId"),
            snapshot.getString("customName"),
            MedicationStatus.valueOf(snapshot.getString("status")),
            snapshot.getDouble("dose"),
            snapshot.getString("unit"),
            frequency,
            timeSlots,
            snapshot.getString("protocolId"),
            snapshot.getString("notes"),
            snapshot.getString("prescribedBy"),
            LocalDate.parse(snapshot.getString("startDate")),
            endDateStr != null ? LocalDate.parse(endDateStr) : null,
            discontinueReasonStr != null ? DiscontinueReason.valueOf(discontinueReasonStr) : null,
            snapshot.getString("discontinueNotes"),
            (List<String>) snapshot.get("correlatedMarkers"),
            dosagePeriods,
            toInstant(snapshot.get("createdAt")),
            toInstant(snapshot.get("updatedAt"))
        );
    }

    private DosagePeriod parseDosagePeriod(Map<String, Object> map) {
        Object endDate = map.get("endDate");
        return new DosagePeriod(
            ((Number) map.get("dose")).doubleValue(),
            (String) map.get("unit"),
            LocalDate.parse((String) map.get("startDate")),
            endDate != null ? LocalDate.parse((String) endDate) : null
        );
    }

    @SuppressWarnings("unchecked")
    private FrequencyConfig parseFrequencyConfig(Map<String, Object> map) {
        if (map == null) {
            return FrequencyConfig.daily(1);
        }
        FrequencyType type = FrequencyType.valueOf((String) map.get("type"));
        Integer timesPerPeriod = map.get("timesPerPeriod") != null
            ? ((Number) map.get("timesPerPeriod")).intValue()
            : null;
        List<String> daysRaw = (List<String>) map.get("specificDays");
        List<DayOfWeek> specificDays = daysRaw != null
            ? daysRaw.stream().map(DayOfWeek::valueOf).toList()
            : null;

        CycleConfig cycle = null;
        Map<String, Object> cycleMap = (Map<String, Object>) map.get("cycle");
        if (cycleMap != null) {
            cycle = new CycleConfig(
                ((Number) cycleMap.get("onWeeks")).intValue(),
                ((Number) cycleMap.get("offWeeks")).intValue(),
                LocalDate.parse((String) cycleMap.get("startDate"))
            );
        }

        return new FrequencyConfig(type, timesPerPeriod, specificDays, cycle);
    }

    private TimeSlot parseTimeSlot(Map<String, Object> map) {
        return new TimeSlot(
            TimeWindow.valueOf((String) map.get("window")),
            ((Number) map.get("dose")).doubleValue()
        );
    }

    private static Map<String, Object> toBody(Medication m, boolean isNew) {
        Map<String, Object> body = new HashMap<>();
        body.put("drugId", m.drugId());
        body.put("customName", m.customName());
        body.put("status", m.status().name());
        body.put("dose", m.dose());
        body.put("unit", m.unit());
        body.put("frequency", toFrequencyMap(m.frequency()));
        body.put("timeSlots", toTimeSlotsMap(m.timeSlots()));
        body.put("protocolId", m.protocolId());
        body.put("notes", m.notes());
        body.put("prescribedBy", m.prescribedBy());
        body.put("startDate", m.startDate().toString());
        body.put("endDate", m.endDate() != null ? m.endDate().toString() : null);
        body.put("discontinueReason", m.discontinueReason() != null ? m.discontinueReason().name() : null);
        body.put("discontinueNotes", m.discontinueNotes());
        body.put("correlatedMarkers", m.correlatedMarkers());
        body.put("dosagePeriods", toDosagePeriodsMap(m.dosagePeriods()));
        body.put("updatedAt", serverTimestamp());
        if (isNew) {
            body.put("createdAt", serverTimestamp());
        }
        return body;
    }

    private static Map<String, Object> toFrequencyMap(FrequencyConfig freq) {
        Map<String, Object> map = new HashMap<>();
        map.put("type", freq.type().name());
        map.put("timesPerPeriod", freq.timesPerPeriod());
        if (freq.specificDays() != null) {
            map.put("specificDays", freq.specificDays().stream().map(Enum::name).toList());
        }
        if (freq.cycle() != null) {
            Map<String, Object> cycleMap = new HashMap<>();
            cycleMap.put("onWeeks", freq.cycle().onWeeks());
            cycleMap.put("offWeeks", freq.cycle().offWeeks());
            cycleMap.put("startDate", freq.cycle().startDate().toString());
            map.put("cycle", cycleMap);
        }
        return map;
    }

    private static List<Map<String, Object>> toTimeSlotsMap(List<TimeSlot> slots) {
        if (slots == null) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (TimeSlot slot : slots) {
            Map<String, Object> map = new HashMap<>();
            map.put("window", slot.window().name());
            map.put("dose", slot.dose());
            result.add(map);
        }
        return result;
    }

    private static List<Map<String, Object>> toDosagePeriodsMap(List<DosagePeriod> periods) {
        if (periods == null) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (DosagePeriod p : periods) {
            Map<String, Object> map = new HashMap<>();
            map.put("dose", p.dose());
            map.put("unit", p.unit());
            map.put("startDate", p.startDate().toString());
            map.put("endDate", p.endDate() != null ? p.endDate().toString() : null);
            result.add(map);
        }
        return result;
    }

    private static <T> T await(ApiFuture<T> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Firestore call interrupted", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Firestore call failed", e.getCause());
        }
    }
}
