import Link from "next/link";
import type { Metadata } from "next";
import { getHistory, getTarget } from "@/lib/nutrition-api";
import type { DailyRollup, Macros } from "@/lib/types/nutrition";

export const metadata: Metadata = { title: "Nutrition History" };
export const dynamic = "force-dynamic";

function todayStr(): string {
  return new Date().toISOString().split("T")[0] ?? "";
}

function nDaysAgoStr(n: number): string {
  const d = new Date();
  d.setUTCDate(d.getUTCDate() - n);
  return d.toISOString().split("T")[0] ?? "";
}

function parseRange(raw: string | string[] | undefined): 7 | 14 | 30 {
  if (raw === "14") return 14;
  if (raw === "30") return 30;
  return 7;
}

function formatDate(iso: string): string {
  const d = new Date(iso + "T00:00:00Z");
  return d.toLocaleDateString("en-US", {
    month: "short",
    day: "numeric",
    timeZone: "UTC",
  });
}

// Compute percentage of target met for a given nutrient
function pct(value: number, target: number | null): number | null {
  if (target === null || target <= 0) return null;
  return Math.min(100, (value / target) * 100);
}

// Days on target: all non-null targets must be within 110% of goal
function onTarget(row: DailyRollup, target: Macros | null): boolean {
  if (!target) return false;
  const checks: Array<[number, number | null]> = [
    [row.caloriesKcal, target.caloriesKcal],
    [row.proteinGrams, target.proteinGrams],
    [row.carbsGrams, target.carbsGrams],
    [row.fatGrams, target.fatGrams],
  ];
  return checks.every(([val, tgt]) => {
    if (tgt === null) return true; // no target → skip
    return val >= tgt * 0.9 && val <= tgt * 1.1;
  });
}

export default async function NutritionHistoryPage(props: {
  searchParams: Promise<{ range?: string }>;
}) {
  const { range: rawRange } = await props.searchParams;
  const days = parseRange(rawRange);
  const to = todayStr();
  const from = nDaysAgoStr(days - 1);

  const [rollups, target] = await Promise.all([
    getHistory(from, to).catch(() => [] as DailyRollup[]),
    getTarget(),
  ]);

  // Sort oldest → newest
  const sorted = [...rollups].sort((a, b) => a.date.localeCompare(b.date));

  const daysOnTarget = sorted.filter((r) => onTarget(r, target)).length;
  const totalDays = sorted.length;

  return (
    <main className="min-h-screen bg-canvas p-8">
      <div className="mx-auto max-w-[920px] space-y-6">
        <Link
          href="/me/nutrition"
          className="inline-flex items-center gap-1.5 font-mono text-[11px] uppercase tracking-[0.04em] text-tertiary hover:text-secondary"
        >
          ← Nutrition
        </Link>

        <header className="flex flex-wrap items-start justify-between gap-4">
          <div>
            <h1 className="m-0 text-[22px] font-medium tracking-[-0.015em] text-primary">
              History
            </h1>
            <p className="mt-1 text-[13px] text-secondary">
              Daily macro rollups — {formatDate(from)} to {formatDate(to)}.
            </p>
          </div>

          {/* Range selector */}
          <nav className="flex items-center gap-1 rounded-md border-[0.5px] border-border-default bg-surface p-1">
            {([7, 14, 30] as const).map((n) => (
              <Link
                key={n}
                href={`/me/nutrition/history?range=${n}`}
                className={`caps-mono rounded px-3 py-1.5 text-[10px] tracking-[0.06em] transition-colors ${
                  days === n
                    ? "bg-accent-bg text-accent-dim"
                    : "text-tertiary hover:text-secondary"
                }`}
              >
                {n}d
              </Link>
            ))}
          </nav>
        </header>

        {/* Summary cards */}
        {target && totalDays > 0 && (
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
            <SummaryCard
              label="Days on target"
              value={`${daysOnTarget}/${totalDays}`}
              tone={daysOnTarget / totalDays >= 0.7 ? "good" : "warn"}
            />
            <SummaryCard
              label="Avg calories"
              value={`${Math.round(
                sorted.reduce((s, r) => s + r.caloriesKcal, 0) / Math.max(1, totalDays),
              )}`}
              unit="kcal"
            />
            <SummaryCard
              label="Avg protein"
              value={`${Math.round(
                sorted.reduce((s, r) => s + r.proteinGrams, 0) / Math.max(1, totalDays),
              )}`}
              unit="g"
            />
            <SummaryCard
              label="Avg carbs"
              value={`${Math.round(
                sorted.reduce((s, r) => s + r.carbsGrams, 0) / Math.max(1, totalDays),
              )}`}
              unit="g"
            />
          </div>
        )}

        {sorted.length === 0 ? (
          <div className="rounded-[14px] border-[0.5px] border-border-default bg-surface px-7 py-12 text-center">
            <h2 className="m-0 text-[15px] font-medium text-primary">
              No data yet
            </h2>
            <p className="mx-auto mt-2 max-w-[480px] text-[13px] leading-[1.5] text-secondary">
              Start logging foods on the{" "}
              <Link
                href="/me/nutrition"
                className="font-medium text-accent-dim underline-offset-2 hover:underline"
              >
                Nutrition
              </Link>{" "}
              page to see your history here.
            </p>
          </div>
        ) : (
          <div className="rounded-[14px] border-[0.5px] border-border-default bg-surface">
            <div className="border-b-[0.5px] border-border-subtle px-5 py-3">
              <h2 className="m-0 text-[14px] font-medium text-primary">
                Daily breakdown
              </h2>
            </div>
            <div className="overflow-x-auto">
              <table className="w-full min-w-[640px]">
                <thead>
                  <tr className="border-b-[0.5px] border-border-subtle">
                    {[
                      "Date",
                      "Calories",
                      "Protein",
                      "Carbs",
                      "Fat",
                      "Fiber",
                      "Sugar",
                      "On target",
                    ].map((h) => (
                      <th
                        key={h}
                        className="caps-mono px-5 py-2 text-left text-[9px] tracking-[0.06em] text-tertiary"
                      >
                        {h}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody className="divide-y divide-border-subtle">
                  {sorted.map((row) => {
                    const met = onTarget(row, target);
                    return (
                      <tr key={row.date} className="hover:bg-canvas-sunken/30">
                        <td className="px-5 py-3">
                          <Link
                            href={`/me/nutrition?date=${row.date}`}
                            className="text-[12px] font-medium text-accent-dim hover:underline underline-offset-2"
                          >
                            {formatDate(row.date)}
                          </Link>
                        </td>
                        <NutrientCell
                          value={row.caloriesKcal}
                          target={target?.caloriesKcal ?? null}
                          unit="kcal"
                        />
                        <NutrientCell
                          value={row.proteinGrams}
                          target={target?.proteinGrams ?? null}
                          unit="g"
                        />
                        <NutrientCell
                          value={row.carbsGrams}
                          target={target?.carbsGrams ?? null}
                          unit="g"
                        />
                        <NutrientCell
                          value={row.fatGrams}
                          target={target?.fatGrams ?? null}
                          unit="g"
                        />
                        <NutrientCell
                          value={row.fiberGrams}
                          target={target?.fiberGrams ?? null}
                          unit="g"
                        />
                        <NutrientCell
                          value={row.sugarGrams}
                          target={target?.sugarGrams ?? null}
                          unit="g"
                        />
                        <td className="px-5 py-3">
                          {target ? (
                            <span
                              className={`caps-mono rounded-[4px] px-2 py-0.5 text-[9px] tracking-[0.06em] ${
                                met
                                  ? "bg-good/20 text-good"
                                  : "bg-canvas-sunken text-tertiary"
                              }`}
                            >
                              {met ? "On target" : "—"}
                            </span>
                          ) : (
                            <span className="text-[12px] text-tertiary">—</span>
                          )}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          </div>
        )}
      </div>
    </main>
  );
}

function NutrientCell({
  value,
  target,
  unit,
}: {
  value: number;
  target: number | null;
  unit: string;
}) {
  const p = pct(value, target);
  const tone =
    p === null
      ? "neutral"
      : p >= 90 && p <= 110
        ? "good"
        : p < 70 || p > 130
          ? "alert"
          : "warn";

  return (
    <td className="px-5 py-3">
      <div
        className={`font-mono text-[13px] tabular-nums ${
          tone === "good"
            ? "text-good"
            : tone === "alert"
              ? "text-alert"
              : tone === "warn"
                ? "text-warn"
                : "text-primary"
        }`}
      >
        {Math.round(value)}
        <span className="ml-0.5 text-[10px] text-tertiary">{unit}</span>
      </div>
      {target !== null && (
        <div className="caps-mono mt-0.5 text-[9px] text-tertiary">
          / {Math.round(target)}
        </div>
      )}
    </td>
  );
}

function SummaryCard({
  label,
  value,
  unit,
  tone,
}: {
  label: string;
  value: string;
  unit?: string;
  tone?: "good" | "warn";
}) {
  return (
    <div className="rounded-[12px] border-[0.5px] border-border-default bg-surface px-4 py-3">
      <div className="caps-mono text-[9px] tracking-[0.08em] text-tertiary">
        {label}
      </div>
      <div
        className={`mt-1 font-mono text-[22px] font-medium tabular-nums ${
          tone === "good"
            ? "text-good"
            : tone === "warn"
              ? "text-warn"
              : "text-primary"
        }`}
      >
        {value}
        {unit && (
          <span className="ml-0.5 text-[11px] font-normal text-tertiary">
            {unit}
          </span>
        )}
      </div>
    </div>
  );
}
