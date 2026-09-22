import Link from "next/link";
import type { Route } from "next";
import type { ProgressionLog, ProgressionLogRow } from "@/lib/types/progression";
import { formatDateUpper } from "@/lib/format-date";
import { formatNumber } from "@/lib/format-number";
import { isPerHand } from "@/lib/per-hand";

// The per-lift progression audit (IMPL-DELOAD-01 D4): one row per completed
// session, newest first — what the engine wanted (target + why) vs what was
// performed (top set), the path that produced the number, and deload flags.
// Rows completed before target retention (P0) have no target — they render "—".
// Per-hand lifts (IMPL-PROG-LOAD-01) display TOTAL load. Pure presentational.

const PATH_LABEL: Record<string, string> = {
  KALMAN: "Engine",
  DOUBLE_PROGRESSION: "Double progression",
  WARMUP: "Warm-up",
  DELOAD: "Deload",
  FALLBACK_COLD_START: "Cold start",
  FALLBACK_STALE: "Last session",
  FALLBACK_SANITY: "Held (sanity)",
  FALLBACK_UNMATERIALIZED: "Last session",
};

const DIRECTION_GLYPH: Record<string, string> = {
  UP: "▲",
  DOWN: "▼",
  HOLD: "→",
};

function pathLabel(path: string | null): string {
  if (!path) return "—";
  return PATH_LABEL[path] ?? path;
}

function loadLabel(totalLbs: number | null, perHand: boolean): string {
  if (totalLbs == null) return "—";
  if (!perHand) return `${formatNumber(totalLbs)}`;
  return `${formatNumber(totalLbs)} (${formatNumber(totalLbs / 2)}/hand)`;
}

function RowLine({ row, perHand }: { row: ProgressionLogRow; perHand: boolean }) {
  const achieved =
    row.topSetTotalLbs != null && row.topSetReps != null
      ? `${loadLabel(row.topSetTotalLbs, perHand)} × ${row.topSetReps}`
      : row.topSetTotalLbs != null
        ? loadLabel(row.topSetTotalLbs, perHand)
        : "—";
  return (
    <li data-testid="progression-log-row" data-deload={row.isDeload ? "true" : "false"}>
      <Link
        href={`/me/workouts/history/${row.programId}/${row.scheduledId}` as Route}
        className="flex flex-wrap items-baseline gap-x-3 gap-y-1 rounded-[6px] px-1 py-2.5 transition-colors hover:bg-canvas"
      >
        <span className="w-[92px] shrink-0 font-mono text-[11px] text-tertiary tabular-nums">
          {formatDateUpper(row.date)}
        </span>
        <span className="w-[130px] shrink-0">
          <span
            className={
              "caps-mono rounded-[3px] px-1.5 py-px text-[9px] tracking-[0.06em] " +
              (row.isDeload || row.path === "DELOAD"
                ? "bg-warn-bg text-warn"
                : "bg-canvas text-tertiary")
            }
            data-testid="log-path"
          >
            {row.isDeload && row.path !== "DELOAD" ? "Deload" : pathLabel(row.path)}
          </span>
        </span>
        <span className="font-mono text-[12px] text-primary tabular-nums" data-testid="log-target">
          {row.direction && (
            <span className="mr-1 text-tertiary">{DIRECTION_GLYPH[row.direction] ?? ""}</span>
          )}
          target {loadLabel(row.targetTotalLbs, perHand)}
        </span>
        <span className="font-mono text-[12px] text-secondary tabular-nums" data-testid="log-achieved">
          did {achieved}
        </span>
        {row.rationaleInputs.length > 0 && (
          <span className="basis-full text-[11px] text-tertiary" data-testid="log-rationale">
            {row.rationaleInputs.join(" · ")}
          </span>
        )}
      </Link>
    </li>
  );
}

export function ProgressionLogTable({ log }: { log: ProgressionLog }) {
  const perHand = isPerHand(log.loadFactor);
  return (
    <div className="rounded-[14px] border-[0.5px] border-border-default bg-surface px-6 py-5">
      <div className="flex items-baseline justify-between gap-3">
        <div>
          <div className="caps-mono text-[9px] tracking-[0.08em] text-tertiary">
            Progression log
          </div>
          <h2 className="mt-1 text-[16px] font-medium text-primary">{log.exerciseName}</h2>
        </div>
        {perHand && (
          <span className="caps-mono rounded-[3px] bg-canvas px-1.5 py-px text-[9px] tracking-[0.06em] text-tertiary">
            per-hand ×2
          </span>
        )}
      </div>

      {log.rows.length === 0 ? (
        <p className="mt-4 text-[13px] text-secondary">
          No completed sessions for this lift yet.
        </p>
      ) : (
        <ul className="mt-3 divide-y divide-border-subtle" data-testid="progression-log">
          {log.rows.map((row) => (
            <RowLine key={`${row.scheduledId}:${row.date}`} row={row} perHand={perHand} />
          ))}
        </ul>
      )}

      <p className="mt-3 text-[11px] leading-relaxed text-tertiary">
        Target = what the engine prescribed and why; did = your top performed
        set. Sessions completed before target retention shipped show “—”.
      </p>
    </div>
  );
}
