package com.gte619n.healthfitness.shared.domain.common

/**
 * IMPL-IOS-01 Phase 1A — extracted from
 * android/core-domain/.../domain/common/DayOfWeek.kt (D3: Moshi→kotlinx.serialization).
 *
 * Day-of-week shared by medications (weekly schedules, IMPL-AND-03) and gym
 * hours (IMPL-AND-06).
 *
 * ⚠️ CONTRACT SUBTLETY (verified against backend
 * `config/DayOfWeekJacksonConfig.java` — do NOT "simplify"): the wire case is
 * NOT uniform.
 *   - **Medication `specificDays`** (a `List<DayOfWeek>` of enum VALUES): the
 *     backend emits **UPPERCASE** (`["MON","THU"]`) — it registers only a
 *     case-insensitive *deserializer*, so serialization stays the enum name.
 *     kotlinx.serialization's default enum codec (serializes by `name()`)
 *     matches this exactly, so no custom serializer is needed here.
 *   - **Gym/location hours** (a `Map<DayOfWeek, HoursSlot>` keyed by day): the
 *     backend emits **lowercase** keys (`"mon"`…`"sun"`) via a key (de)serializer.
 *     TODO(IMPL-IOS-01 Phase 1C): the location model must apply a lowercasing
 *     KSerializer for the map-key context (the Android `DayOfWeekMoshiAdapter`
 *     lowercases unconditionally; here the two contexts must be split).
 *
 * Design note: the per-feature IMPL specs each declared their own `DayOfWeek`;
 * one shared enum keeps the two usages consistent.
 */
enum class DayOfWeek { MON, TUE, WED, THU, FRI, SAT, SUN }

/**
 * Sunday-first ordering for day-picker / calendar UI. Kept separate from the
 * enum's own Monday-first declaration so the wire order (ordinal) stays stable.
 */
val weekDisplayOrder: List<DayOfWeek> = listOf(
    DayOfWeek.SUN, DayOfWeek.MON, DayOfWeek.TUE, DayOfWeek.WED,
    DayOfWeek.THU, DayOfWeek.FRI, DayOfWeek.SAT,
)
