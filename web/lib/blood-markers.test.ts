import { describe, expect, it } from "vitest";
import { normalizeBloodMarkerName } from "@/lib/blood-markers";

// The dashboard maps free-text lab marker names onto its four tracked markers;
// getting the aliasing wrong silently drops or misfiles a reading.
describe("normalizeBloodMarkerName", () => {
  it.each([
    ["Testosterone", "TESTOSTERONE"],
    ["Total Testosterone", "TESTOSTERONE"],
    ["LDL Cholesterol", "LDL"],
    ["ApoB", "APO_B"],
    ["Apo-B", "APO_B"],
    ["Apolipoprotein B", "APO_B"],
    ["HbA1c", "HBA1C"],
    ["Hemoglobin A1c", "HBA1C"],
    ["A1C", "HBA1C"],
    ["Glycated Hemoglobin", "HBA1C"],
  ])("maps %s -> %s", (input, expected) => {
    expect(normalizeBloodMarkerName(input)).toBe(expected);
  });

  it("returns null for an unrecognised marker", () => {
    expect(normalizeBloodMarkerName("Vitamin D")).toBeNull();
  });
});
