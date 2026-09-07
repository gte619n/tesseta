import { describe, expect, it } from "vitest";
import { formatObserved } from "./format-observed";

describe("formatObserved", () => {
  // Local noon, so date-only values (parsed at local midnight) compare cleanly.
  const now = new Date("2026-09-07T12:00:00");

  it("labels a same-day calendar date as today", () => {
    expect(formatObserved("2026-09-07", now)).toBe("today");
  });

  it("labels the prior day as yesterday", () => {
    expect(formatObserved("2026-09-06", now)).toBe("yesterday");
  });

  it("labels within a week as Nd ago", () => {
    expect(formatObserved("2026-09-04", now)).toBe("3d ago");
  });

  it("labels older dates as short month + day", () => {
    expect(formatObserved("2026-08-20", now)).toBe("Aug 20");
  });

  it("treats an ISO timestamp earlier today as today (day granularity)", () => {
    expect(formatObserved("2026-09-07T06:30:00", now)).toBe("today");
  });

  it("returns empty string for blank or invalid input", () => {
    expect(formatObserved("", now)).toBe("");
    expect(formatObserved("not-a-date", now)).toBe("");
  });
});
