import { describe, expect, it } from "vitest";
import { movingAverage, toLinePath, niceTicks, barLayout } from "@/lib/chart";

describe("movingAverage", () => {
  it("averages within a trailing window", () => {
    expect(movingAverage([2, 4, 6], 2)).toEqual([2, 3, 5]);
  });

  it("clamps the window to available history at the start", () => {
    expect(movingAverage([10, 20], 5)).toEqual([10, 15]);
  });

  it("returns an empty array for an empty series", () => {
    expect(movingAverage([], 3)).toEqual([]);
  });
});

describe("toLinePath", () => {
  it("builds an SVG polyline path from points", () => {
    expect(toLinePath([{ x: 0, y: 0 }, { x: 10, y: 5 }])).toBe("M 0.0 0.0 L 10.0 5.0");
  });

  it("returns an empty string for no points", () => {
    expect(toLinePath([])).toBe("");
  });
});

describe("niceTicks", () => {
  it("produces 1/2/5×10ⁿ ticks covering the range", () => {
    const ticks = niceTicks(0, 47000, 4);
    expect(ticks[0]).toBe(0);
    expect(ticks[ticks.length - 1]).toBeGreaterThanOrEqual(47000);
    // Even spacing.
    const step = ticks[1]! - ticks[0]!;
    for (let i = 1; i < ticks.length; i++) {
      expect(ticks[i]! - ticks[i - 1]!).toBeCloseTo(step, 6);
    }
  });

  it("collapses a flat/zero series to a single tick", () => {
    expect(niceTicks(0, 0)).toEqual([0]);
  });
});

describe("barLayout", () => {
  const geom = { width: 100, height: 100, padX: 10, padTop: 10, padBottom: 10 };

  it("lays out one bar per value on a shared baseline", () => {
    const { bars, baselineY, yMax } = barLayout([0, 5, 10], geom);
    expect(bars).toHaveLength(3);
    expect(baselineY).toBe(90); // height - padBottom
    // Zero value → zero height, sitting on the baseline.
    expect(bars[0]!.height).toBe(0);
    expect(bars[0]!.y).toBe(90);
    // The tallest bar reaches full plot height when it equals yMax.
    const tallest = bars[2]!;
    expect(tallest.height).toBeCloseTo((10 / yMax) * 80, 6);
    // Bars are left-to-right, non-overlapping.
    expect(bars[1]!.x).toBeGreaterThan(bars[0]!.x);
  });

  it("respects a yMax override so multiple charts share a scale", () => {
    const { bars, yMax } = barLayout([5], geom, 20);
    expect(yMax).toBeGreaterThanOrEqual(20);
    expect(bars[0]!.height).toBeCloseTo((5 / yMax) * 80, 6);
  });
});
