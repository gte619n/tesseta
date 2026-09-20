package com.gte619n.healthfitness.api.adhoc;

import java.util.List;

/**
 * Body of {@code POST /api/me/adhoc-workouts/generate} — the preview generation
 * request (IMPL-ADHOC-01 D14). Not persisted; returns an editable draft.
 *
 * <p>Equipment is specified as a {@code presetId} (hotel-gym / home / bodyweight
 * / full-gym / a saved gym), or an explicit {@code equipmentIds} set from the
 * ad-hoc picker, plus optional {@code freeText} the AI reads verbatim.
 */
public record GenerateAdHocRequest(
    String prompt,
    Integer targetDurationMinutes,
    String presetId,
    List<String> equipmentIds,
    String freeText
) {}
