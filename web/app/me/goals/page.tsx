import Link from "next/link";
import type { Route } from "next";
import { listGoals } from "@/lib/goals-api";
import { GoalCard } from "@/components/goals/GoalCard";
import type { GoalStatus } from "@/lib/types/goals";

export const dynamic = "force-dynamic";

const FILTERS: { label: string; status: GoalStatus }[] = [
  { label: "Active", status: "ACTIVE" },
  { label: "Completed", status: "COMPLETED" },
  { label: "Archived", status: "ARCHIVED" },
];

function parseStatus(raw: string | string[] | undefined): GoalStatus {
  if (typeof raw === "string" && (raw === "ACTIVE" || raw === "COMPLETED" || raw === "ARCHIVED")) {
    return raw;
  }
  return "ACTIVE";
}

export default async function GoalsPage(props: {
  searchParams: Promise<{ status?: string }>;
}) {
  const { status: rawStatus } = await props.searchParams;
  const status = parseStatus(rawStatus);
  const goals = await listGoals(status);

  return (
    <main className="min-h-screen bg-canvas p-8">
      <div className="mx-auto max-w-[920px] space-y-6">
        <Link
          href="/"
          className="inline-flex items-center gap-1.5 font-mono text-[11px] uppercase tracking-[0.04em] text-tertiary hover:text-secondary"
        >
          ← Dashboard
        </Link>

        <header className="flex flex-wrap items-start justify-between gap-4">
          <div>
            <h1 className="m-0 text-[22px] font-medium tracking-[-0.015em] text-primary">
              Goals
            </h1>
            <p className="mt-1 text-[13px] text-secondary">
              Health objectives organized as Goal → Phase → Step. Steps resolve
              against live module data and auto-check when targets are met.
            </p>
          </div>
          <span
            className="caps-mono inline-flex cursor-not-allowed items-center gap-1.5 rounded-md border-[0.5px] border-border-default bg-canvas-muted px-3 py-2 text-[10px] tracking-[0.06em] text-tertiary"
            title="Chat-driven and manual goal creation are coming in the next PR"
          >
            New goal
            <span className="rounded-full bg-canvas px-1.5 py-px text-[9px] text-tertiary">
              soon
            </span>
          </span>
        </header>

        <nav className="flex items-center gap-1 rounded-md border-[0.5px] border-border-default bg-surface p-1">
          {FILTERS.map((f) => {
            const isActive = f.status === status;
            const href = (
              f.status === "ACTIVE" ? "/me/goals" : `/me/goals?status=${f.status}`
            ) as Route;
            return (
              <Link
                key={f.status}
                href={href}
                className={`caps-mono rounded px-3 py-1.5 text-[10px] tracking-[0.06em] transition-colors ${
                  isActive
                    ? "bg-accent-bg text-accent-dim"
                    : "text-tertiary hover:text-secondary"
                }`}
              >
                {f.label}
              </Link>
            );
          })}
        </nav>

        {goals.length === 0 ? (
          <EmptyState status={status} />
        ) : (
          <div className="space-y-3">
            {goals.map((g) => (
              <GoalCard key={g.goalId} goal={g} />
            ))}
          </div>
        )}
      </div>
    </main>
  );
}

function EmptyState({ status }: { status: GoalStatus }) {
  const messages: Record<GoalStatus, { title: string; body: string }> = {
    ACTIVE: {
      title: "No active goals yet",
      body: "Goals will appear here once you create one. The next PR adds chat-driven and manual goal creation; the backend foundation (data model, metric resolver, step evaluator, daily SUSTAINED re-evaluation) is already live.",
    },
    COMPLETED: {
      title: "No completed goals",
      body: "Finished goals show up here. A goal completes when every Step in its last Phase is done.",
    },
    ARCHIVED: {
      title: "No archived goals",
      body: "Soft-deleted goals show up here. Archiving keeps the history without removing data.",
    },
  };
  const m = messages[status];
  return (
    <div className="rounded-[14px] border-[0.5px] border-border-default bg-surface px-7 py-12 text-center">
      <h2 className="m-0 text-[15px] font-medium text-primary">{m.title}</h2>
      <p className="mx-auto mt-2 max-w-[480px] text-[13px] leading-[1.5] text-secondary">
        {m.body}
      </p>
    </div>
  );
}
