import Link from "next/link";
import type { Route } from "next";
import { listGoals } from "@/lib/goals-api";
import { GoalCard } from "@/components/goals/GoalCard";
import type { GoalStatus } from "@/lib/types/goals";
import { pageMetadata } from "@/lib/page-metadata";

export const metadata = pageMetadata("Goals");

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
          <div className="flex items-center gap-2">
            <Link
              href={"/me/goals/new" as Route}
              className="caps-mono inline-flex items-center gap-1.5 rounded-md border-[0.5px] border-border-default bg-canvas px-3 py-2 text-[10px] tracking-[0.06em] text-secondary hover:text-primary"
            >
              Create manually
            </Link>
            <Link
              href={"/me/goals/chat" as Route}
              className="caps-mono inline-flex items-center gap-1.5 rounded-md bg-accent px-3 py-2 text-[10px] tracking-[0.06em] text-inverse hover:opacity-90"
            >
              New goal
            </Link>
          </div>
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
              <Link
                key={g.goalId}
                href={`/me/goals/${g.goalId}` as Route}
                className="block transition-opacity hover:opacity-95"
              >
                <GoalCard goal={g} />
              </Link>
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
      body: "Create one manually to build a roadmap by hand, or start a chat to have a plan proposed. Steps resolve against live module data and auto-check when targets are met.",
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
