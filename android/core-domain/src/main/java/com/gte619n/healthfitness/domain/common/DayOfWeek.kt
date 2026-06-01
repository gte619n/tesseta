package com.gte619n.healthfitness.domain.common

/**
 * Day-of-week shared by medications (weekly schedules, IMPL-AND-03) and gym
 * hours (IMPL-AND-06). The backend serializes these **lowercase** on the wire
 * (`"mon"`…`"sun"`) via a Jackson key (de)serializer; the Moshi
 * `DayOfWeekMoshiAdapter` in core-data mirrors that.
 *
 * Design note: the per-feature IMPL specs each declared their own `DayOfWeek`;
 * we use one shared enum so a single Moshi adapter can cover both `List` and
 * `Map`-key usages without registering two conflicting adapters.
 */
enum class DayOfWeek { MON, TUE, WED, THU, FRI, SAT, SUN }
