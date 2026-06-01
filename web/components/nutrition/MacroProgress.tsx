"use client";

import type { Macros } from "@/lib/types/nutrition";

type MacroKey = keyof Omit<Macros, never>;

type MacroMeta = {
  key: MacroKey;
  label: string;
  unit: string;
  color: string;
};

const MACRO_META: MacroMeta[] = [
  { key: "caloriesKcal", label: "Calories", unit: "kcal", color: "bg-accent" },
  { key: "proteinGrams", label: "Protein", unit: "g", color: "bg-[var(--color-accent)]" },
  { key: "carbsGrams", label: "Carbs", unit: "g", color: "bg-[var(--color-good)]" },
  { key: "fatGrams", label: "Fat", unit: "g", color: "bg-[var(--color-warn)]" },
  { key: "fiberGrams", label: "Fiber", unit: "g", color: "bg-[var(--color-tertiary)]" },
  { key: "sugarGrams", label: "Sugar", unit: "g", color: "bg-[var(--color-muted)]" },
];

type Props = {
  totals: Macros;
  target: Macros | null;
};

export function MacroProgress({ totals, target }: Props) {
  return (
    <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
      {MACRO_META.map(({ key, label, unit, color }) => {
        const consumed = totals[key] ?? 0;
        const tgt = target?.[key] ?? null;
        const pct = tgt && tgt > 0 ? Math.min(100, (consumed / tgt) * 100) : null;
        const remaining = tgt !== null ? Math.max(0, tgt - consumed) : null;

        return (
          <div
            key={key}
            className="flex flex-col gap-1.5 rounded-[12px] border-[0.5px] border-border-default bg-surface px-4 py-3"
          >
            <div className="caps-mono text-[9px] tracking-[0.08em] text-tertiary">
              {label}
            </div>
            <div className="font-mono text-[22px] font-medium leading-none tracking-[-0.02em] text-primary tabular-nums">
              {Math.round(consumed)}
              <span className="ml-0.5 text-[11px] font-normal text-tertiary">
                {unit}
              </span>
            </div>

            {/* Progress bar */}
            <div className="h-1 w-full rounded-full bg-canvas-sunken">
              {pct !== null && (
                <div
                  className={`h-full rounded-full ${color} transition-all`}
                  style={{ width: `${pct}%` }}
                />
              )}
            </div>

            {/* Target + remaining */}
            {tgt !== null ? (
              <div className="caps-mono text-[9px] tracking-[0.04em] text-tertiary">
                {remaining === 0 ? (
                  <span className="text-good">Met target</span>
                ) : (
                  <>
                    {Math.round(remaining ?? 0)} {unit} left · {Math.round(tgt)} target
                  </>
                )}
              </div>
            ) : (
              <div className="caps-mono text-[9px] tracking-[0.04em] text-tertiary">
                No target set
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}
