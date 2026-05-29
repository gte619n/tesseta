import type { GoalResponse } from "@/lib/types/goals";
import { DOMAIN_LABEL } from "@/lib/types/goals";

function formatTargetDate(iso: string): string {
  const d = new Date(`${iso}T00:00:00`);
  return d
    .toLocaleDateString("en-US", {
      month: "short",
      day: "numeric",
      year: "numeric",
    })
    .toUpperCase();
}

function isBehindSchedule(goal: GoalResponse): boolean {
  if (goal.status !== "ACTIVE") return false;
  const target = new Date(`${goal.targetDate}T00:00:00`).getTime();
  return Date.now() > target;
}

export function GoalCard({ goal }: { goal: GoalResponse }) {
  const phaseCount = goal.phaseOrder.length;
  const behind = isBehindSchedule(goal);
  return (
    <div
      className={`rounded-[14px] border-[0.5px] border-border-default bg-surface px-6 py-5 ${
        behind ? "opacity-90" : ""
      }`}
    >
      <div className="flex items-start justify-between gap-4">
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <span className="caps-mono rounded-[3px] bg-canvas-muted px-1.5 py-px text-[9px] tracking-[0.06em] text-tertiary">
              {DOMAIN_LABEL[goal.domain]}
            </span>
            {behind ? (
              <span className="caps-mono rounded-[3px] bg-warn-bg px-1.5 py-px text-[9px] tracking-[0.06em] text-[#7C3F0F]">
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
          <h3 className="mt-2 text-[16px] font-medium leading-[1.25] tracking-[-0.01em] text-primary">
            {goal.title}
          </h3>
          {goal.description ? (
            <p className="mt-1 line-clamp-2 text-[13px] text-secondary">
              {goal.description}
            </p>
          ) : null}
        </div>
        <div className="shrink-0 text-right">
          <div className="caps-mono text-[9px] tracking-[0.06em] text-tertiary">
            Target
          </div>
          <div
            className={`caps-mono mt-px text-[11px] tracking-[0.04em] ${
              behind ? "text-[#7C3F0F]" : "text-secondary"
            }`}
          >
            {formatTargetDate(goal.targetDate)}
          </div>
        </div>
      </div>

      <div className="mt-4 flex items-center gap-2">
        <PhaseSpine count={phaseCount} dimmed={behind || goal.status !== "ACTIVE"} />
        <div className="caps-mono shrink-0 text-[10px] tracking-[0.06em] text-tertiary">
          {phaseCount} phase{phaseCount === 1 ? "" : "s"}
        </div>
      </div>
    </div>
  );
}

// A row of N segments — visual placeholder for the phase progress spine.
// Once the shallow list endpoint returns per-phase status, color each
// segment by completed / active / locked.
function PhaseSpine({ count, dimmed }: { count: number; dimmed: boolean }) {
  if (count === 0) {
    return (
      <div className="h-1 flex-1 rounded-full bg-canvas-muted" aria-hidden />
    );
  }
  const segments = Array.from({ length: count });
  return (
    <div className="flex flex-1 items-center gap-[3px]" aria-hidden>
      {segments.map((_, i) => (
        <div
          key={i}
          className={`h-1 flex-1 rounded-full ${
            dimmed ? "bg-canvas-muted" : "bg-accent-bg"
          }`}
        />
      ))}
    </div>
  );
}
