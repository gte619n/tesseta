import type { Macros } from "./types/nutrition";
import type { PlanChain, PlanFlag, PhaseGuidance } from "./types/plan";

// Pure divergence logic for the plan-coherence view — no I/O, unit-testable.
// Given the assembled chain, produce the ordered list of coherence flags
// (actionable first, then warnings, then informational).

// kcal a macro bundle implies, using the stored calories or the Atwater 4/4/9
// fallback. Returns null when there's nothing to compute from.
export function kcalOf(
  m: Pick<Macros, "caloriesKcal" | "proteinGrams" | "carbsGrams" | "fatGrams"> | null,
): number | null {
  if (!m) return null;
  if (m.caloriesKcal != null) return m.caloriesKcal;
  if (m.proteinGrams == null && m.carbsGrams == null && m.fatGrams == null) {
    return null;
  }
  return (m.proteinGrams ?? 0) * 4 + (m.carbsGrams ?? 0) * 4 + (m.fatGrams ?? 0) * 9;
}

function guidanceKcal(g: PhaseGuidance | null): number | null {
  if (!g) return null;
  if (g.kcal != null) return g.kcal;
  return kcalOf({
    caloriesKcal: null,
    proteinGrams: g.proteinG,
    carbsGrams: g.carbsG,
    fatGrams: g.fatG,
  });
}

// Bands that keep noise down: ignore sub-band kcal gaps as "in sync".
const TARGET_DRIFT_KCAL = 60; // program vs. current target
const DEFICIT_EPSILON_KCAL = 75; // "is the target actually below maintenance?"

const SEVERITY_ORDER = { action: 0, warn: 1, info: 2 } as const;

export function computeFlags(chain: PlanChain): PlanFlag[] {
  const flags: PlanFlag[] = [];
  const { goal, program, target, energy, block } = chain;

  const targetKcal = kcalOf(target);
  const progKcal = guidanceKcal(program?.guidance ?? null);
  const maint =
    energy && energy.hasIntakeData ? Math.round(energy.maintenanceKcal) : null;

  // ── Structural links ───────────────────────────────────────────────
  if (!goal) {
    flags.push({
      id: "no-goal",
      severity: "info",
      icon: "flag",
      title: "No active goal",
      detail:
        "The plan has nothing to anchor to — a goal is what your program and nutrition point at.",
      hint: "Create a goal to give the rest of the chain a shared target.",
    });
  }
  if (!program) {
    flags.push({
      id: "no-program",
      severity: "info",
      icon: "barbell",
      title: "No active program",
      detail:
        "There's no training or per-phase nutrition guidance for this goal yet.",
      hint: "Design a program and attach it to this goal.",
    });
  }

  // Active program not linked to the active goal.
  if (goal && program && program.goalId !== goal.id) {
    flags.push({
      id: "program-goal-unlinked",
      severity: "warn",
      icon: "unlink",
      title: "Program isn't linked to your goal",
      detail: `“${program.title}” isn't attached to “${goal.title}”.`,
      hint: "Linking lets the goal inherit the program's nutrition guidance.",
      action: { label: "Link", kind: "linkProgramToGoal" },
    });
  }

  // ── Calorie target vs. program guidance ────────────────────────────
  if (program && progKcal != null && targetKcal == null) {
    flags.push({
      id: "no-target-has-guidance",
      severity: "action",
      icon: "target",
      title: "No calorie target set",
      detail: `Your program's current phase suggests ${fmt(progKcal)} kcal/day.`,
      hint: "Apply it so your daily logging has something to measure against.",
      action: { label: "Apply", kind: "applyProgramNutrition" },
    });
  } else if (
    program &&
    progKcal != null &&
    targetKcal != null &&
    Math.abs(progKcal - targetKcal) > TARGET_DRIFT_KCAL
  ) {
    flags.push({
      id: "target-drift",
      severity: "action",
      icon: "arrows-diff",
      title: "Target doesn't match your program",
      detail: `Phase suggests ${fmt(progKcal)} kcal, but your target is ${fmt(targetKcal)}.`,
      hint: "Usually means the phase changed — re-apply to sync them.",
      action: { label: "Apply", kind: "applyProgramNutrition" },
    });
  }

  // No target at all, but we have a measured maintenance to seed from.
  if (targetKcal == null && progKcal == null && maint != null) {
    flags.push({
      id: "no-target-has-maintenance",
      severity: "action",
      icon: "flame",
      title: "No calorie target set",
      detail: `The engine measures your maintenance at about ${fmt(maint)} kcal/day.`,
      hint: "Seed a target from it, then adjust for your goal.",
      action: { label: "Set target", kind: "setTargetFromMaintenance" },
    });
  }

  // ── Target vs. measured maintenance (+ goal intent) ────────────────
  if (targetKcal != null && maint != null) {
    const delta = targetKcal - maint;
    const cutLeaning = goal?.domain === "BODY_COMPOSITION";
    if (cutLeaning && delta > -DEFICIT_EPSILON_KCAL) {
      flags.push({
        id: "goal-wants-deficit",
        severity: "warn",
        icon: "trending-down",
        title: "Target isn't in a deficit",
        detail: `Your target (${fmt(targetKcal)}) sits at or above your measured maintenance (~${fmt(maint)}).`,
        hint: "A body-composition goal needs a deficit to lose fat.",
      });
    } else {
      flags.push({
        id: "target-vs-maintenance",
        severity: "info",
        icon: "scale",
        title: "Target vs. measured maintenance",
        detail: `Target ${fmt(targetKcal)} kcal — ${describeDelta(delta)} your measured maintenance of ~${fmt(maint)}.`,
        hint: "Measured from your logged intake and bodyweight trend.",
      });
    }
  }

  // ── Engine mode: pinned vs. measured ───────────────────────────────
  if (block?.manualOverride && energy?.hasIntakeData && block.mode !== energy.mode) {
    flags.push({
      id: "mode-pinned-diverges",
      severity: "warn",
      icon: "pin",
      title: "Engine mode is pinned",
      detail: `Pinned to ${titleCase(block.mode)}, but your measured balance suggests ${titleCase(energy.mode)}.`,
      hint: "Unpin it on the Progression Engine to let it auto-adjust.",
    });
  }

  return flags.sort(
    (a, b) => SEVERITY_ORDER[a.severity] - SEVERITY_ORDER[b.severity],
  );
}

function describeDelta(delta: number): string {
  const rounded = Math.round(delta);
  if (Math.abs(rounded) <= DEFICIT_EPSILON_KCAL) return "roughly at";
  return rounded > 0
    ? `a ${fmt(rounded)} kcal surplus over`
    : `a ${fmt(Math.abs(rounded))} kcal deficit below`;
}

function fmt(n: number): string {
  return Math.round(n).toLocaleString("en-US");
}

function titleCase(s: string): string {
  return s.charAt(0).toUpperCase() + s.slice(1).toLowerCase();
}
