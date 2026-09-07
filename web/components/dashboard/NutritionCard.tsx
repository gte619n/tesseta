import Link from "next/link";
import type { Macros } from "@/lib/types/nutrition";
import type { NutritionSummary } from "@/lib/nutrition-dashboard";
import { formatWholeNumber } from "@/lib/format-number";

// Compact dashboard card: today's calories against target with a P/C/F macro
// breakdown. A trimmed sibling of the Nutrition page's DailySummaryCard, sized
// for the dashboard's two-column grid. Server component — no interactivity.

const MACROS: { key: keyof Macros; label: string; barClass: string }[] = [
  { key: "proteinGrams", label: "Protein", barClass: "bg-accent" },
  { key: "carbsGrams", label: "Carbs", barClass: "bg-good" },
  { key: "fatGrams", label: "Fat", barClass: "bg-warn" },
];

function pct(consumed: number, target: number | null): number | null {
  if (!target || target <= 0) return null;
  return Math.min(100, (consumed / target) * 100);
}

export function NutritionCard({ summary }: { summary: NutritionSummary | null }) {
  const totals = summary?.totals ?? null;
  const target = summary?.target ?? null;
  const cal = Math.round(totals?.caloriesKcal ?? 0);
  const calTarget = target?.caloriesKcal ?? null;
  const calPct = pct(cal, calTarget);
  const remaining = calTarget !== null ? Math.round(calTarget - cal) : null;

  return (
    <div className="rounded-[10px] border-[0.5px] border-border-default bg-surface px-5 py-[18px]">
      <div className="flex items-center justify-between">
        <Link
          href="/me/nutrition"
          className="group inline-flex items-center gap-2.5 hover:text-accent-dim"
        >
          <span
            aria-hidden
            className="inline-block h-3.5 w-[3px] rounded-[2px] bg-accent"
          />
          <span className="text-[14px] font-medium tracking-[-0.01em] text-primary group-hover:text-accent-dim">
            Nutrition
          </span>
          <span
            aria-hidden
            className="font-mono text-[11px] text-tertiary opacity-0 transition-opacity group-hover:opacity-100"
          >
            →
          </span>
        </Link>
        {remaining !== null && (
          <span className="font-mono text-[11px] text-tertiary tabular-nums">
            {remaining >= 0
              ? `${formatWholeNumber(remaining)} kcal left`
              : `${formatWholeNumber(Math.abs(remaining))} kcal over`}
          </span>
        )}
      </div>

      {summary === null ? (
        <p className="mt-3 text-[13px] text-secondary">
          Couldn&apos;t load today&apos;s nutrition.
        </p>
      ) : (
        <>
          <div className="mt-3 flex items-baseline gap-2">
            <span className="font-mono text-[28px] font-medium leading-none tracking-[-0.02em] text-primary tabular-nums">
              {formatWholeNumber(cal)}
            </span>
            <span className="font-mono text-[12px] text-tertiary tabular-nums">
              {calTarget !== null
                ? `/ ${formatWholeNumber(calTarget)} kcal`
                : "kcal"}
            </span>
          </div>

          <div className="mt-2.5 h-1.5 w-full rounded-full bg-canvas-sunken">
            {calPct !== null && (
              <div
                className="h-full rounded-full bg-accent transition-all"
                style={{ width: `${calPct}%` }}
              />
            )}
          </div>

          <div className="mt-4 grid grid-cols-3 gap-3">
            {MACROS.map((m) => {
              const consumed = Math.round(totals?.[m.key] ?? 0);
              const tgt = target?.[m.key] ?? null;
              const p = pct(consumed, tgt);
              return (
                <div key={m.key} className="flex flex-col gap-1.5">
                  <div className="flex items-baseline justify-between">
                    <span className="caps-mono text-[9px] tracking-[0.08em] text-tertiary">
                      {m.label}
                    </span>
                    <span className="font-mono text-[13px] font-medium leading-none tabular-nums text-primary">
                      {formatWholeNumber(consumed)}
                      {tgt !== null && (
                        <span className="ml-0.5 text-[10px] font-normal text-tertiary">
                          /{formatWholeNumber(tgt)}
                        </span>
                      )}
                      <span className="ml-0.5 text-[10px] font-normal text-tertiary">
                        g
                      </span>
                    </span>
                  </div>
                  <div className="h-1 w-full rounded-full bg-canvas-sunken">
                    {p !== null && (
                      <div
                        className={`h-full rounded-full ${m.barClass} transition-all`}
                        style={{ width: `${p}%` }}
                      />
                    )}
                  </div>
                </div>
              );
            })}
          </div>
        </>
      )}
    </div>
  );
}
