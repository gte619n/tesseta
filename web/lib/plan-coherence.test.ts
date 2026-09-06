import { describe, it, expect } from "vitest";
import { computeFlags, kcalOf } from "./plan-coherence";
import type { PlanChain } from "./types/plan";

const macros = (kcal: number | null, p = 0, c = 0, f = 0) => ({
  caloriesKcal: kcal,
  proteinGrams: p,
  carbsGrams: c,
  fatGrams: f,
  fiberGrams: null,
  sugarGrams: null,
});

// A fully coherent baseline: linked program, target matches guidance, target in
// a deficit for a body-comp goal, engine auto (not pinned).
function coherent(): PlanChain {
  return {
    goal: { id: "g1", title: "Cut", domain: "BODY_COMPOSITION" },
    program: {
      id: "p1",
      title: "Prog",
      goalId: "g1",
      phaseTitle: "Cut",
      guidance: { kcal: 2300, proteinG: 190, carbsG: 200, fatG: 70 },
    },
    target: macros(2300, 190, 200, 70),
    today: { date: "2026-09-06", totals: macros(1500) },
    energy: {
      maintenanceKcal: 2700,
      meanIntakeKcal: 2300,
      balanceKcal: -400,
      mode: "MAINTENANCE",
      hasIntakeData: true,
    },
    block: { mode: "MAINTENANCE", manualOverride: false },
  };
}

const ids = (c: PlanChain) => computeFlags(c).map((f) => f.id);

describe("kcalOf", () => {
  it("prefers stored calories", () => {
    expect(kcalOf(macros(2000, 100, 100, 50))).toBe(2000);
  });
  it("falls back to Atwater 4/4/9", () => {
    expect(kcalOf(macros(null, 100, 100, 50))).toBe(100 * 4 + 100 * 4 + 50 * 9);
  });
  it("is null with nothing to compute", () => {
    expect(kcalOf(null)).toBeNull();
    expect(kcalOf(macros(null, 0, 0, 0))).toBe(0); // zeros are data, not absence
  });
});

describe("computeFlags", () => {
  it("a fully coherent chain raises no divergences beyond the info row", () => {
    const flags = computeFlags(coherent());
    // Only the informational target-vs-maintenance row remains.
    expect(flags.every((f) => f.severity === "info")).toBe(true);
    expect(ids(coherent())).toContain("target-vs-maintenance");
  });

  it("flags an unlinked program", () => {
    const c = coherent();
    c.program!.goalId = null;
    expect(ids(c)).toContain("program-goal-unlinked");
  });

  it("flags target drift from the program's phase guidance with an apply action", () => {
    const c = coherent();
    c.target = macros(2750, 170, 300, 80);
    const drift = computeFlags(c).find((f) => f.id === "target-drift");
    expect(drift?.action?.kind).toBe("applyProgramNutrition");
  });

  it("flags a body-comp goal whose target isn't a deficit", () => {
    const c = coherent();
    c.target = macros(2800);
    expect(ids(c)).toContain("goal-wants-deficit");
  });

  it("offers a maintenance-seed when no target and no guidance exist", () => {
    const c = coherent();
    c.target = null;
    c.program!.guidance = null;
    const flag = computeFlags(c).find((f) => f.id === "no-target-has-maintenance");
    expect(flag?.action?.kind).toBe("setTargetFromMaintenance");
  });

  it("flags a pinned mode that diverges from the measured mode", () => {
    const c = coherent();
    c.block = { mode: "GAINING", manualOverride: true };
    expect(ids(c)).toContain("mode-pinned-diverges");
  });

  it("orders actionable flags before warnings and info", () => {
    const c = coherent();
    c.program!.goalId = null; // warn
    c.target = null; // action (apply program nutrition, since guidance exists)
    const sev = computeFlags(c).map((f) => f.severity);
    const firstWarn = sev.indexOf("warn");
    const firstInfo = sev.indexOf("info");
    const lastAction = sev.lastIndexOf("action");
    if (lastAction !== -1 && firstWarn !== -1) expect(lastAction).toBeLessThan(firstWarn);
    if (firstWarn !== -1 && firstInfo !== -1) expect(firstWarn).toBeLessThan(firstInfo);
  });
});
