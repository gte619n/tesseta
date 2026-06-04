package com.gte619n.healthfitness.integrations.googlehealth;

import java.time.Instant;

// Normalized shape of a single data point returned by the Google Health
// API. Mapping from the raw JSON response is GoogleHealthClient's job;
// this record is what the rest of the codebase sees.
//
// The `name` field carries Google's resource path:
//   users/{healthUserId}/dataTypes/{type}/dataPoints/{recordId}
// Both `healthUserId` and `recordId` are parsed out of it.
public record GoogleHealthDataPoint(
    String name,
    String healthUserId,
    String recordId,
    GoogleHealthDataType dataType,
    double value,
    Instant sampleTime,
    String sourcePlatform,
    String recordingMethod
) {}
