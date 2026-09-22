import { describe, expect, it } from "vitest";
import {
  addDays,
  mondayOf,
  buildHeatmapGrid,
  heatmapIntensityClass,
} from "./workout-stats-format";
import type { HeatmapDay } from "./types/workout-stats";

describe("date helpers", () => {
  it("addDays crosses month boundaries in UTC", () => {
    expect(addDays("2026-01-31", 1)).toBe("2026-02-01");
    expect(addDays("2026-03-01", -1)).toBe("2026-02-28");
  });

  it("mondayOf returns the Monday of the week", () => {
    // 2026-06-17 is a Wednesday; its Monday is 2026-06-15.
    expect(mondayOf("2026-06-17")).toBe("2026-06-15");
    // A Monday maps to itself; a Sunday maps back to that week's Monday.
    expect(mondayOf("2026-06-15")).toBe("2026-06-15");
    expect(mondayOf("2026-06-14")).toBe("2026-06-08");
  });
});

describe("buildHeatmapGrid", () => {
  const today = "2026-06-17"; // Wednesday, week of Mon 2026-06-15

  it("produces one Monday→Sunday column per week, ending with the current week", () => {
    const grid = buildHeatmapGrid([], today, 4);
    expect(grid).toHaveLength(4);
    expect(grid[3]!.weekStart).toBe("2026-06-15");
    expect(grid[0]!.weekStart).toBe("2026-05-25");
    for (const col of grid) {
      expect(col.cells).toHaveLength(7);
      expect(col.cells[0]!.date).toBe(col.weekStart); // Monday first
    }
  });

  it("marks a workout day with its count + ref and flags future cells", () => {
    const days: HeatmapDay[] = [
      {
        date: "2026-06-15",
        sessionCount: 2,
        first: { programId: "p1", scheduledId: "2026-06-15_d1" },
        isDeload: false,
      },
    ];
    const grid = buildHeatmapGrid(days, today, 2);
    const current = grid[1]!; // week of 06-15
    const monday = current.cells[0]!;
    expect(monday.date).toBe("2026-06-15");
    expect(monday.count).toBe(2);
    expect(monday.ref?.scheduledId).toBe("2026-06-15_d1");
    expect(monday.future).toBe(false);

    // Thursday 06-18 is after today → future, no data.
    const thursday = current.cells[3]!;
    expect(thursday.date).toBe("2026-06-18");
    expect(thursday.future).toBe(true);
    expect(thursday.count).toBe(0);
  });

  it("leaves days without sessions at zero", () => {
    const grid = buildHeatmapGrid([], today, 1);
    expect(grid[0]!.cells.every((c) => c.count === 0 && c.ref === null)).toBe(true);
  });
});

describe("heatmapIntensityClass", () => {
  it("ramps with count and blanks future cells", () => {
    const base = { date: "2026-06-15", ref: null };
    expect(heatmapIntensityClass({ ...base, count: 0, future: true })).toBe("bg-transparent");
    expect(heatmapIntensityClass({ ...base, count: 0, future: false })).toContain("border-subtle");
    expect(heatmapIntensityClass({ ...base, count: 1, future: false })).toBe("bg-accent/40");
    expect(heatmapIntensityClass({ ...base, count: 2, future: false })).toBe("bg-accent/70");
    expect(heatmapIntensityClass({ ...base, count: 5, future: false })).toBe("bg-accent");
  });
});
