import Link from "next/link";
import type { Route } from "next";
import type { SessionDetailResponse } from "@/lib/types/workout-stats";
import type { LoggedSet, Prescription } from "@/lib/types/workout-program";
import { formatDateUpper } from "@/lib/format-date";
import { formatNumber } from "@/lib/format-number";

// Read-only session detail (IMPL-WEB-WORKOUT-01 §5.5): the full block →
// exercise → prescribed-vs-logged tree, PR badges on the sets that set a record,
// and prev/next navigation across history. No edit affordances — backfill lives
// on the History page (D4). Pure presentational; the page owns the fetch.

const FEELING_LABEL: Record<number, string> = {
  1: "😖 Rough",
  2: "😕 Meh",
  3: "😐 OK",
  4: "🙂 Good",
  5: "💪 Great",
};

function setKey(blockId: string, orderIndex: number, setIndex: number): string {
  return `${blockId}:${orderIndex}:${setIndex}`;
}

function loggedLabel(set: LoggedSet): string {
  const weight = set.weightLbs != null && set.weightLbs > 0 ? `${formatNumber(set.weightLbs)} lb` : "BW";
  if (set.reps != null) return `${weight} × ${set.reps}`;
  if (set.restSeconds == null && set.reps == null && set.weightLbs != null) return weight;
  return weight;
}

function prescribedLabel(rx: Prescription): string {
  const parts: string[] = [];
  if (rx.sets != null) {
    const reps =
      rx.repsMin != null && rx.repsMax != null
        ? rx.repsMin === rx.repsMax
          ? `${rx.repsMin}`
          : `${rx.repsMin}–${rx.repsMax}`
        : rx.durationSeconds != null
          ? `${rx.durationSeconds}s`
          : "";
    parts.push(reps ? `${rx.sets} × ${reps}` : `${rx.sets} sets`);
  }
  if (rx.targetWeightLbs != null) parts.push(`@ ${formatNumber(rx.targetWeightLbs)} lb`);
  else if (rx.intensity?.value != null)
    parts.push(`@ ${rx.intensity.kind === "RPE" ? "RPE " : ""}${rx.intensity.value}${rx.intensity.kind === "PERCENT_1RM" ? "%" : ""}`);
  return parts.join(" ");
}

export function SessionDetail({ detail }: { detail: SessionDetailResponse }) {
  const { session, prSetKeys, prev, next } = detail;
  const prKeys = new Set(prSetKeys);
  const durationMin = session.durationSeconds ? Math.round(session.durationSeconds / 60) : null;
  const feeling =
    session.feeling && session.feeling >= 1 && session.feeling <= 5
      ? FEELING_LABEL[session.feeling]
      : null;

  return (
    <div className="space-y-5">
      <header>
        <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
          <h2 className="text-[20px] font-medium tracking-[-0.01em] text-primary">
            {session.dayLabel}
          </h2>
          <span className="font-mono text-[12px] text-tertiary tabular-nums">
            {formatDateUpper(session.date)}
          </span>
        </div>
        <div className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-[12px] text-secondary">
          {session.programTitle && <span>{session.programTitle}</span>}
          {session.phaseTitle && (
            <>
              <span className="text-border-default">·</span>
              <span>{session.phaseTitle}</span>
            </>
          )}
          {durationMin != null && (
            <>
              <span className="text-border-default">·</span>
              <span className="tabular-nums">{durationMin} min</span>
            </>
          )}
          {feeling && (
            <>
              <span className="text-border-default">·</span>
              <span>{feeling}</span>
            </>
          )}
        </div>
      </header>

      <div className="space-y-4">
        {(session.session?.blocks ?? []).map((block) => (
          <div
            key={block.blockId}
            className="rounded-[12px] border-[0.5px] border-border-default bg-surface px-5 py-4"
          >
            <div className="caps-mono text-[9px] tracking-[0.08em] text-tertiary">
              {block.title || block.type}
            </div>
            <div className="mt-3 space-y-3">
              {(block.prescriptions ?? []).map((rx) => {
                const name = rx.exercise?.name ?? rx.exerciseId;
                const logged = rx.loggedSets ?? [];
                const prescribed = prescribedLabel(rx);
                return (
                  <div key={`${block.blockId}:${rx.orderIndex}`}>
                    <div className="flex items-baseline justify-between gap-2">
                      <span className="text-[14px] font-medium text-primary">{name}</span>
                      {prescribed && (
                        <span className="font-mono text-[11px] text-tertiary tabular-nums">
                          {prescribed}
                        </span>
                      )}
                    </div>
                    {logged.length > 0 ? (
                      <ul className="mt-1.5 flex flex-wrap gap-1.5">
                        {logged.map((set, i) => {
                          const isPr = prKeys.has(setKey(block.blockId, rx.orderIndex, i));
                          return (
                            <li
                              key={i}
                              data-testid="logged-set"
                              data-pr={isPr ? "true" : "false"}
                              className={
                                "inline-flex items-center gap-1 rounded-[6px] px-2 py-0.5 font-mono text-[11px] tabular-nums " +
                                (isPr
                                  ? "bg-accent/15 text-accent-dim ring-1 ring-accent/30"
                                  : "bg-canvas text-secondary")
                              }
                            >
                              {loggedLabel(set)}
                              {set.rir != null && (
                                <span className="text-tertiary">· {set.rir} RIR</span>
                              )}
                              {isPr && (
                                <span
                                  className="caps-mono rounded-full bg-accent/20 px-1 text-[8px] tracking-[0.06em] text-accent-dim"
                                  data-testid="pr-badge"
                                >
                                  PR
                                </span>
                              )}
                            </li>
                          );
                        })}
                      </ul>
                    ) : (
                      <p className="mt-1 text-[12px] text-tertiary">Not logged.</p>
                    )}
                  </div>
                );
              })}
            </div>
          </div>
        ))}
      </div>

      <nav className="flex items-center justify-between border-t border-border-subtle pt-4">
        {prev ? (
          <Link
            href={`/me/workouts/history/${prev.programId}/${prev.scheduledId}` as Route}
            className="inline-flex items-center gap-1.5 text-[13px] text-secondary hover:text-accent-dim"
            data-testid="session-prev"
          >
            ← <span className="font-mono text-[11px] tabular-nums">{formatDateUpper(prev.date)}</span>
          </Link>
        ) : (
          <span />
        )}
        {next ? (
          <Link
            href={`/me/workouts/history/${next.programId}/${next.scheduledId}` as Route}
            className="inline-flex items-center gap-1.5 text-[13px] text-secondary hover:text-accent-dim"
            data-testid="session-next"
          >
            <span className="font-mono text-[11px] tabular-nums">{formatDateUpper(next.date)}</span> →
          </Link>
        ) : (
          <span />
        )}
      </nav>
    </div>
  );
}
