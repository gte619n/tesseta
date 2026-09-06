import { notFound } from "next/navigation";
import { PlanCoherence } from "@/components/plan/PlanCoherence";
import type { PlanChain } from "@/lib/types/plan";
import { pageMetadata } from "@/lib/page-metadata";

export const metadata = pageMetadata("Plan preview");

// Dev-only UI harness for the plan-coherence view. Public (under /auth/*), 404s
// in production. Sample chain is deliberately INCOHERENT so every flag renders:
// a body-comp goal, an unlinked program whose phase kcal ≠ the current target,
// and a target above measured maintenance (i.e. not the deficit the goal wants).
const SAMPLE: PlanChain = {
  goal: { id: "g1", title: "Drop to 12% body fat", domain: "BODY_COMPOSITION" },
  program: {
    id: "p1",
    title: "Hypertrophy Block — Fall",
    goalId: "g1", // linked to this goal (goal-scoped view)
    phaseTitle: "Phase 2 · Cut",
    guidance: { kcal: 2300, proteinG: 190, carbsG: 200, fatG: 70 },
  },
  target: {
    caloriesKcal: 2750,
    proteinGrams: 170,
    carbsGrams: 300,
    fatGrams: 80,
    fiberGrams: null,
    sugarGrams: null,
  },
  today: {
    date: "2026-09-06",
    totals: {
      caloriesKcal: 1850,
      proteinGrams: 120,
      carbsGrams: 190,
      fatGrams: 60,
      fiberGrams: null,
      sugarGrams: null,
    },
  },
  energy: {
    maintenanceKcal: 2680,
    meanIntakeKcal: 2740,
    balanceKcal: 60,
    mode: "RECOMP",
    hasIntakeData: true,
  },
  block: { mode: "GAINING", manualOverride: true },
};

export default function PlanPreviewPage() {
  if (process.env.NODE_ENV === "production") notFound();

  return (
    <main className="min-h-screen bg-canvas p-8">
      <div className="mx-auto max-w-[720px] space-y-6">
        <span className="inline-block rounded-full bg-warn-bg px-2 py-0.5 caps-mono text-[9px] tracking-[0.06em] text-warn">
          Preview · sample data
        </span>
        <header>
          <h1 className="m-0 text-[22px] font-medium tracking-[-0.015em] text-primary">
            Plan
          </h1>
          <p className="mt-1 max-w-[600px] text-[13px] leading-relaxed text-secondary">
            How your goal, program, calorie target, and actual eating line up —
            and where they don&apos;t. Reconcile the gaps in one tap.
          </p>
        </header>
        <PlanCoherence chain={SAMPLE} />
      </div>
    </main>
  );
}
