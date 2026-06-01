import type { Macros } from "@/lib/types/nutrition";

type Props = {
  totals: Macros;
  target: Macros | null;
};

type MacroMeta = {
  key: keyof Macros;
  label: string;
  unit: string;
  barClass: string;
};

// Calories is the hero; protein / carbs / fat are the primary macros most
// people track. Sugar + fibre live behind a "Sugar & fiber" expander so the
// default view stays focused on the three.
const PRIMARY: MacroMeta[] = [
  { key: "proteinGrams", label: "Protein", unit: "g", barClass: "bg-[var(--color-accent)]" },
  { key: "carbsGrams", label: "Carbs", unit: "g", barClass: "bg-[var(--color-good)]" },
  { key: "fatGrams", label: "Fat", unit: "g", barClass: "bg-[var(--color-warn)]" },
];

const SECONDARY: MacroMeta[] = [
  { key: "sugarGrams", label: "Sugar", unit: "g", barClass: "bg-[var(--color-muted)]" },
  { key: "fiberGrams", label: "Fiber", unit: "g", barClass: "bg-[var(--color-tertiary)]" },
];

function pct(consumed: number, target: number | null): number | null {
  if (!target || target <= 0) return null;
  return Math.min(100, (consumed / target) * 100);
}

export function DailySummaryCard({ totals, target }: Props) {
  const calConsumed = Math.round(totals.caloriesKcal ?? 0);
  const calTarget = target?.caloriesKcal ?? null;
  const calPct = pct(calConsumed, calTarget);
  const calRemaining =
    calTarget !== null ? Math.round(calTarget - calConsumed) : null;

  return (
    <div className="rounded-[16px] border-[0.5px] border-border-default bg-surface px-6 py-5">
      {/* Hero: calories */}
      <div className="flex items-end justify-between gap-4">
        <div>
          <div className="caps-mono text-[10px] tracking-[0.08em] text-tertiary">
            Calories
          </div>
          <div className="mt-1 flex items-baseline gap-2">
            <span className="font-mono text-[40px] font-medium leading-none tracking-[-0.02em] text-primary tabular-nums">
              {calConsumed}
            </span>
            {calTarget !== null && (
              <span className="font-mono text-[15px] text-tertiary tabular-nums">
                / {Math.round(calTarget)}
                <span className="ml-0.5 text-[11px]">kcal</span>
              </span>
            )}
          </div>
        </div>
        {calRemaining !== null && (
          <div className="text-right">
            <div className="font-mono text-[20px] font-medium leading-none tabular-nums text-primary">
              {Math.abs(calRemaining)}
            </div>
            <div className="caps-mono mt-1 text-[9px] tracking-[0.06em] text-tertiary">
              {calRemaining >= 0 ? "kcal left" : "kcal over"}
            </div>
          </div>
        )}
      </div>

      {/* Calories progress bar */}
      <div className="mt-3 h-1.5 w-full rounded-full bg-canvas-sunken">
        {calPct !== null && (
          <div
            className="h-full rounded-full bg-accent transition-all"
            style={{ width: `${calPct}%` }}
          />
        )}
      </div>
      {calTarget === null && (
        <div className="mt-2 caps-mono text-[9px] tracking-[0.04em] text-tertiary">
          No calorie target set
        </div>
      )}

      {/* Primary macros: protein / carbs / fat */}
      <div className="mt-5 grid grid-cols-3 gap-3">
        {PRIMARY.map((m) => (
          <MacroStat key={m.key} meta={m} totals={totals} target={target} />
        ))}
      </div>

      {/* Sugar + fibre, behind a native disclosure (no client JS needed) */}
      <details className="group mt-3">
        <summary className="caps-mono flex cursor-pointer list-none items-center gap-1 text-[9px] tracking-[0.08em] text-tertiary marker:hidden [&::-webkit-details-marker]:hidden">
          <span className="group-open:hidden">Sugar &amp; fiber</span>
          <span className="hidden group-open:inline">Hide sugar &amp; fiber</span>
          <svg
            className="h-3 w-3 transition-transform group-open:rotate-180"
            viewBox="0 0 12 12"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.5"
            aria-hidden="true"
          >
            <path d="M3 4.5 6 7.5 9 4.5" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
        </summary>
        <div className="mt-3 grid grid-cols-2 gap-3">
          {SECONDARY.map((m) => (
            <MacroStat key={m.key} meta={m} totals={totals} target={target} compact />
          ))}
        </div>
      </details>
    </div>
  );
}

function MacroStat({
  meta,
  totals,
  target,
  compact = false,
}: {
  meta: MacroMeta;
  totals: Macros;
  target: Macros | null;
  compact?: boolean;
}) {
  const consumed = Math.round(totals[meta.key] ?? 0);
  const tgt = target?.[meta.key] ?? null;
  const p = pct(consumed, tgt);
  const remaining = tgt !== null ? Math.max(0, Math.round(tgt - consumed)) : null;

  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex items-baseline justify-between">
        <span className="caps-mono text-[9px] tracking-[0.08em] text-tertiary">
          {meta.label}
        </span>
        <span
          className={`font-mono ${
            compact ? "text-[13px]" : "text-[18px]"
          } font-medium leading-none tabular-nums text-primary`}
        >
          {consumed}
          {tgt !== null && (
            <span className="ml-0.5 text-[10px] font-normal text-tertiary">
              /{Math.round(tgt)}
            </span>
          )}
          <span className="ml-0.5 text-[10px] font-normal text-tertiary">
            {meta.unit}
          </span>
        </span>
      </div>
      <div className="h-1 w-full rounded-full bg-canvas-sunken">
        {p !== null && (
          <div
            className={`h-full rounded-full ${meta.barClass} transition-all`}
            style={{ width: `${p}%` }}
          />
        )}
      </div>
      {!compact && (
        <div className="caps-mono text-[9px] tracking-[0.04em] text-tertiary">
          {remaining === null
            ? "No target"
            : remaining === 0
              ? "Met target"
              : `${remaining}${meta.unit} left`}
        </div>
      )}
    </div>
  );
}
