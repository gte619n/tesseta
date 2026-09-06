import Link from "next/link";
import {
  getWeekReview,
  getBlockParameters,
  getStrength,
  getEnergyBalance,
  updateBlockParameters,
} from "@/lib/progression-api";
import { listGoals } from "@/lib/goals-api";
import { listPrograms } from "@/lib/workout-program-api";
import type { BlockParameters } from "@/lib/types/progression";
import { ProgressionConsole } from "@/components/workouts/ProgressionConsole";
import { pageMetadata } from "@/lib/page-metadata";

export const metadata = pageMetadata("Progression Engine");

export const dynamic = "force-dynamic";

export default async function ProgressionPage() {
  // Fetch in parallel; any endpoint failing degrades that section rather than
  // blanking the page. Strength/energy/goal are additive context.
  const [weekReview, block, strength, energy, goals, programs] =
    await Promise.all([
      getWeekReview().catch(() => []),
      getBlockParameters().catch(() => null),
      getStrength().catch(() => []),
      getEnergyBalance().catch(() => null),
      listGoals("ACTIVE").catch(() => []),
      listPrograms().catch(() => []),
    ]);

  // The goal this progression is serving: prefer the active program's goal,
  // else the first active goal.
  const activeProgram = programs.find((p) => p.status === "ACTIVE") ?? null;
  const activeGoal =
    (activeProgram?.goalId
      ? goals.find((g) => g.goalId === activeProgram.goalId)
      : undefined) ??
    goals[0] ??
    null;
  const goal = activeGoal
    ? { title: activeGoal.title, domain: activeGoal.domain }
    : null;

  // Persist the chosen training mode and return the updated block so the client
  // can settle its optimistic state (pinning the mode sets manualOverride).
  async function selectMode(mode: string): Promise<BlockParameters> {
    "use server";
    return updateBlockParameters({ mode });
  }

  return (
    <main className="min-h-screen bg-canvas p-8">
      <div className="mx-auto max-w-[720px] space-y-6">
        <Link
          href="/me/workouts"
          className="inline-flex items-center gap-1.5 font-mono text-[11px] uppercase tracking-[0.04em] text-tertiary hover:text-secondary"
        >
          ← Workouts
        </Link>

        <header>
          <h1 className="m-0 text-[22px] font-medium tracking-[-0.015em] text-primary">
            Progression Engine
          </h1>
          <p className="mt-1 max-w-[600px] text-[13px] leading-relaxed text-secondary">
            The engine tracks your true strength per exercise and adjusts your
            weights, reps, and sets after every workout. These trends and
            settings are tied to <em>you</em>, not to any one program — so they
            carry across every program you run. Your program decides{" "}
            <em>which</em> exercises; the engine decides the <em>numbers</em>.
          </p>
        </header>

        <ProgressionConsole
          weekReview={weekReview}
          block={block}
          strength={strength}
          goal={goal}
          energy={energy}
          onSelectMode={selectMode}
        />
      </div>
    </main>
  );
}
