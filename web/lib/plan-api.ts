import { listPrograms, getProgramDeep } from "./workout-program-api";
import { getTarget, getDay } from "./nutrition-api";
import { getBlockParameters, getEnergyBalance } from "./progression-api";
import type { WorkoutProgramResponse } from "./types/workout-program";
import type { PlanChain } from "./types/plan";

// Server-only aggregator for the plan-coherence view (goal-scoped). Given a
// goal, pulls the program linked to it (+ its current phase's nutrition
// guidance) together with the user's calorie target, today's intake, and the
// progression engine's measured energy state — the last three are
// globally-singular, so they're the same regardless of which goal you view.
// Every piece is fetched defensively so a failure in one domain degrades that
// section rather than blanking the page.

function today(): string {
  return new Date().toISOString().split("T")[0] ?? "";
}

// The program linked to this goal — preferring an ACTIVE one, then the most
// recently updated (mirrors the backend's findActiveForGoal).
function programForGoal(
  programs: WorkoutProgramResponse[],
  goalId: string,
): WorkoutProgramResponse | null {
  const linked = programs.filter((p) => p.goalId === goalId);
  if (linked.length === 0) return null;
  return (
    linked.find((p) => p.status === "ACTIVE") ??
    [...linked].sort((a, b) => b.updatedAt.localeCompare(a.updatedAt))[0] ??
    null
  );
}

export async function getPlanChainForGoal(goal: {
  id: string;
  title: string;
  domain: string;
}): Promise<PlanChain> {
  const [programs, target, day, energy, block] = await Promise.all([
    listPrograms().catch(() => []),
    getTarget().catch(() => null),
    getDay(today()).catch(() => null),
    getEnergyBalance().catch(() => null),
    getBlockParameters().catch(() => null),
  ]);

  const linkedProgram = programForGoal(programs, goal.id);

  // The active phase's guidance, falling back to the program-level guidance.
  let program: PlanChain["program"] = null;
  if (linkedProgram) {
    const deep = await getProgramDeep(linkedProgram.programId).catch(() => null);
    const activePhase = deep?.phases.find((ph) => ph.status === "ACTIVE") ?? null;
    const g = activePhase?.nutritionGuidance ?? deep?.nutritionGuidance ?? null;
    program = {
      id: linkedProgram.programId,
      title: linkedProgram.title,
      goalId: linkedProgram.goalId,
      phaseTitle: activePhase?.title ?? null,
      guidance: g
        ? { kcal: g.kcal, proteinG: g.proteinG, carbsG: g.carbsG, fatG: g.fatG }
        : null,
    };
  }

  return {
    goal: { id: goal.id, title: goal.title, domain: goal.domain },
    program,
    target,
    today: day ? { date: day.date, totals: day.totals } : null,
    energy,
    block: block
      ? { mode: block.mode, manualOverride: block.manualOverride }
      : null,
  };
}
