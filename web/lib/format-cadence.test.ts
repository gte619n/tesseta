import { describe, expect, it } from "vitest";
import { formatCadence } from "./format-cadence";

describe("formatCadence", () => {
  it("returns a placeholder when there are no readings", () => {
    expect(formatCadence(null)).toBe("no recent data");
  });

  it("labels a ~daily cadence as daily", () => {
    expect(formatCadence(1.0)).toBe("≈ daily");
    expect(formatCadence(1.1)).toBe("≈ daily");
  });

  it("labels multi-day intervals with the interval", () => {
    expect(formatCadence(7.5)).toBe("≈ every 7.5d");
  });

  it("labels sub-daily cadence as a weekly rate", () => {
    expect(formatCadence(0.5)).toBe("≈ 14×/wk");
  });
});
