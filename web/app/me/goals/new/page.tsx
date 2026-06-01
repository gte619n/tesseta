import Link from "next/link";
import type { Route } from "next";
import { revalidatePath } from "next/cache";
import {
  createGoal,
  createPhase,
  createStep,
} from "@/lib/goals-api";
import type { CreateStepInput, MetricBindingInput } from "@/lib/goals-api";
import { ManualGoalEditor } from "@/components/goals/ManualGoalEditor";
import type { GoalProposalDraft } from "@/components/goals/GoalProposalCard";
import { pageMetadata } from "@/lib/page-metadata";

export const metadata = pageMetadata("New Goal");

export const dynamic = "force-dynamic";

export default function NewGoalPage() {
  // Persist the whole proposal structure in order: create the Goal, then
  // each Phase in sequence, then each Step within the phase. The backend
  // appends in call order, so phaseOrder / stepOrder follow the editor.
  async function save(draft: GoalProposalDraft): Promise<{ goalId: string }> {
    "use server";
    const goal = await createGoal({
      title: draft.title.trim(),
      description: draft.description.trim(),
      domain: draft.domain,
      startDate: draft.startDate ?? new Date().toISOString().slice(0, 10),
      targetDate: draft.targetDate,
      source: "MANUAL",
    });

    for (const phase of draft.phases) {
      const created = await createPhase(goal.goalId, {
        title: phase.title.trim(),
        description: phase.description.trim(),
        targetStartDate: phase.targetStartDate,
        targetEndDate: phase.targetEndDate,
      });
      for (const step of phase.steps) {
        let metric: MetricBindingInput | null = null;
        if (step.kind !== "MANUAL" && step.metric) {
          const m = step.metric;
          metric = {
            metricKey: m.metricKey,
            comparator: m.comparator,
            targetValue: m.targetValue === "" ? 0 : Number(m.targetValue),
            windowDays:
              step.kind === "SUSTAINED" && m.windowDays !== "" && m.windowDays != null
                ? Number(m.windowDays)
                : null,
            countFrom: step.kind === "COUNT" ? m.countFrom ?? null : null,
          };
        }
        const body: CreateStepInput = {
          title: step.title.trim(),
          kind: step.kind,
          metric,
        };
        await createStep(goal.goalId, created.phaseId, body);
      }
    }

    revalidatePath("/me/goals");
    revalidatePath(`/me/goals/${goal.goalId}`);
    return { goalId: goal.goalId };
  }

  return (
    <main className="min-h-screen bg-canvas p-8">
      <div className="mx-auto max-w-[720px] space-y-6">
        <Link
          href={"/me/goals" as Route}
          className="inline-flex items-center gap-1.5 font-mono text-[11px] uppercase tracking-[0.04em] text-tertiary hover:text-secondary"
        >
          ← Goals
        </Link>

        <header>
          <h1 className="m-0 text-[22px] font-medium tracking-[-0.015em] text-primary">
            Create a goal
          </h1>
          <p className="mt-1 text-[13px] text-secondary">
            Build the roadmap by hand: add phases in sequence, then steps.
            Metric-bound steps auto-check against live data once saved.
          </p>
        </header>

        <ManualGoalEditor save={save} />
      </div>
    </main>
  );
}
