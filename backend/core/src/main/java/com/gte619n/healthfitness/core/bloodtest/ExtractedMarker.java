package com.gte619n.healthfitness.core.bloodtest;

// A single marker value extracted from a blood test PDF. Contains the
// raw value, unit, and reference range as printed on the lab report.
public record ExtractedMarker(
    String name,           // canonical marker name e.g. "LDL", "HDL", "HBA1C"
    Double value,
    String unit,           // e.g. "mg/dL", "%", "mg/L"
    Double refRangeLow,    // reference range lower bound (nullable)
    Double refRangeHigh,   // reference range upper bound (nullable)
    String flag            // "H", "L", or null if within range
) {}
