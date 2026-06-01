package com.gte619n.healthfitness.integrations.googlehealth;

import java.time.LocalDate;

// Normalized shape of a single day-grained data point returned by the
// Google Health API (steps, resting heart rate, HRV, sleep). Parallels
// GoogleHealthDataPoint but keyed to a calendar day instead of an instant.
//
// `value` carries the primary integer reading for the type:
//   STEPS               -> step count
//   RESTING_HEART_RATE  -> beats per minute
//   HRV                 -> milliseconds
//   SLEEP               -> total sleep minutes
// `sleepScore` is populated only for SLEEP (a 0-100 quality score that
// rides along on the same data point); it is null for every other type.
public record DailyMetricDataPoint(
    String name,
    String healthUserId,
    String recordId,
    DailyMetricDataType type,
    LocalDate date,
    int value,
    Integer sleepScore,
    String sourcePlatform,
    String recordingMethod
) {}
