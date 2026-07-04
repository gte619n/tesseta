import { describe, expect, it } from "vitest";
import {
  cmToFtIn,
  formatHeight,
  formatTemperature,
  formatWeight,
  ftInToCm,
  kgToLb,
  lbToKg,
} from "@/lib/units";

// Phase-0 sample: proves the Vitest harness runs and locks the pure unit-
// conversion behaviour that Phase 5 will lean on. cmToFtIn carries 12" up to a
// foot — the edge case worth pinning.
describe("units", () => {
  it("round-trips weight lb <-> kg", () => {
    expect(kgToLb(lbToKg(100))).toBeCloseTo(100, 5);
  });

  it("formats weight in the chosen unit", () => {
    expect(formatWeight(189.2, "LB")).toBe("189.2 lb");
    expect(formatWeight(0, "KG", { withUnit: false })).toBe("0.0");
  });

  it("carries 12 inches up to the next foot", () => {
    // 182.7 cm rounds to 6 ft 0 in, never 5 ft 12 in.
    expect(cmToFtIn(182.7)).toEqual({ ft: 6, in: 0 });
  });

  it("round-trips height ft/in <-> cm within rounding", () => {
    const cm = ftInToCm(6, 2);
    const { ft, in: inches } = cmToFtIn(cm);
    expect({ ft, inches }).toEqual({ ft: 6, inches: 2 });
  });

  it("formats height and temperature", () => {
    expect(formatHeight(188, "CM")).toBe("188 cm");
    expect(formatTemperature(98.6, "F")).toBe("98.6 °F");
    expect(formatTemperature(98.6, "C")).toBe("37.0 °C");
  });
});
