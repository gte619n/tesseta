import Link from "next/link";
import type { Route } from "next";
import { notFound } from "next/navigation";
import { revalidatePath } from "next/cache";
import { BackendError } from "@/lib/api";
import {
  getGoalDeep,
  updateStep,
  archiveGoal as archiveGoalApi,
  reevaluateGoal as reevaluateGoalApi,
} from "@/lib/goals-api";
import { DOMAIN_LABEL } from "@/lib/types/goals";
import { RoadmapTimeline } from "@/components/goals/RoadmapTimeline";
import { GoalDetailActions } from "@/components/goals/GoalDetailActions";
import { entityMetadata } from "@/lib/page-metadata";

export const generateMetadata = entityMetadata(
  ({ id }: { id: string }) => getGoalDeep(id),
  (goal) => goal.title,
);

export const dynamic = "force-dynamic";

function formatDate(iso: string): string {
  return new Date(`${iso}T00:00:00`)
    .toLocaleDateString("en-US", { month: "short", day: "numeric", year: "numeric" })
    .toUpperCase();
}

function isBehindSchedule(targetDate: string, status: string): boolean {
  if (status !== "ACTIVE") return false;
  return Date.now() > new Date(`${targetDate}T00:00:00`).getTime();
}

export default async function GoalDetailPage(props: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await props.params;

  let goal;
  try {
    goal = await getGoalDeep(id);
  } catch (e) {
    if (e instanceof BackendError && e.status === 404) notFound();
    throw e;
  }

  const detailPath = `/me/goals/${id}`;

  async function toggleStep(args: {
    phaseId: string;
    stepId: string;
    done: boolean;
  }) {
    "use server";
    await updateStep(id, args.phaseId, args.stepId, { done: args.done });
    revalidatePath(detailPath);
  }

  async function resetStep(args: { phaseId: string; stepId: string }) {
    "use server";
    await updateStep(id, args.phaseId, args.stepId, { resetToAuto: true });
    revalidatePath(detailPath);
  }

  async function archiveGoal() {
    "use server";
    await archiveGoalApi(id);
    revalidatePath("/me/goals");
    revalidatePath(detailPath);
  }

  async function reevaluateGoal() {
    "use server";
    await reevaluateGoalApi(id);
    revalidatePath(detailPath);
  }

  const behind = isBehindSchedule(goal.targetDate, goal.status);
  const chatHref = `/me/goals/chat?goalId=${goal.goalId}` as Route;

  return (
    <main className="min-h-screen bg-canvas p-8">
      <div className="mx-auto max-w-[860px] space-y-6">
        <Link
          href={"/me/goals" as Route}
          className="inline-flex items-center gap-1.5 font-mono text-[11px] uppercase tracking-[0.04em] text-tertiary hover:text-secondary"
        >
          ← Goals
        </Link>

        <header className="flex flex-wrap items-start justify-between gap-4">
          <div className="min-w-0 flex-1">
            <div className="flex flex-wrap items-center gap-2">
              <span className="caps-mono rounded-[3px] bg-canvas-muted px-1.5 py-px text-[9px] tracking-[0.06em] text-tertiary">
                {DOMAIN_LABEL[goal.domain]}
              </span>
              {behind ? (
                <span className="caps-mono rounded-[3px] bg-warn-bg px-1.5 py-px text-[9px] tracking-[0.06em] text-warn">
                  Behind schedule
                </span>
              ) : null}
              {goal.status === "COMPLETED" ? (
                <span className="caps-mono rounded-[3px] bg-accent-bg px-1.5 py-px text-[9px] tracking-[0.06em] text-accent-dim">
                  Completed
                </span>
              ) : null}
              {goal.status === "ARCHIVED" ? (
                <span className="caps-mono rounded-[3px] bg-canvas-muted px-1.5 py-px text-[9px] tracking-[0.06em] text-tertiary">
                  Archived
                </span>
              ) : null}
            </div>
            <h1 className="mt-2 text-[22px] font-medium tracking-[-0.015em] text-primary">
              {goal.title}
            </h1>
            {goal.description ? (
              <p className="mt-1 max-w-[640px] text-[13px] leading-[1.5] text-secondary">
                {goal.description}
              </p>
            ) : null}
            <div className="caps-mono mt-2 text-[10px] tracking-[0.06em] text-tertiary">
              Target {formatDate(goal.targetDate)}
            </div>
          </div>

          <div className="flex shrink-0 flex-col items-end gap-2">
            <GoalDetailActions
              goalTitle={goal.title}
              archiveGoal={archiveGoal}
              reevaluateGoal={reevaluateGoal}
            />
            <Link
              href={chatHref}
              className="caps-mono inline-flex items-center gap-1.5 rounded-md bg-accent px-3 py-1.5 text-[10px] tracking-[0.06em] text-inverse hover:opacity-90"
            >
              Edit in chat
            </Link>
          </div>
        </header>

        <section className="rounded-[14px] border-[0.5px] border-border-default bg-surface px-6 py-6">
          {goal.phases.length === 0 ? (
            <p className="text-[13px] text-secondary">
              This goal has no phases yet.
            </p>
          ) : (
            <RoadmapTimeline
              goal={goal}
              toggleStep={toggleStep}
              resetStep={resetStep}
            />
          )}
        </section>
      </div>
    </main>
  );
}
