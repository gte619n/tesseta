import { describe, expect, it } from "vitest";
import { movingAverage, toLinePath } from "@/lib/chart";

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
