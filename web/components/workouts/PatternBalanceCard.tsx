import Link from "next/link";
import type { PatternReview } from "@/lib/types/progression";

// This week's movement-pattern balance (IMPL-WEB-WORKOUT-01 D5), reusing the
// progression week-review: current vs. proposed working-set target per pattern,
// with the trend direction. Teases the full Progression tab (D7). Pure
// presentational.

const PATTERN_LABEL: Record<string, string> = {
  SQUAT: "Squat",
  HINGE: "Hinge",
  LUNGE: "Lunge",
  PUSH_HORIZONTAL: "Push (horiz.)",
  PUSH_VERTICAL: "Push (vert.)",
  PULL_HORIZONTAL: "Pull (horiz.)",
  PULL_VERTICAL: "Pull (vert.)",
  CARRY: "Carry",
  CORE: "Core",
};

function label(pattern: string): string {
  return PATTERN_LABEL[pattern] ?? pattern.replace(/_/g, " ").toLowerCase();
}

function trendGlyph(trend: string): { glyph: string; cls: string } {
  switch (trend) {
    case "RISING":
      return { glyph: "↑", cls: "text-good" };
    case "FALLING":
      return { glyph: "↓", cls: "text-warn" };
    case "FLAT":
      return { glyph: "→", cls: "text-tertiary" };
    default:
      return { glyph: "·", cls: "text-tertiary" };
  }
}

export function PatternBalanceCard({ review }: { review: PatternReview[] }) {
  return (
    <div className="rounded-[14px] border-[0.5px] border-border-default bg-surface px-6 py-5">
      <div className="flex items-center justify-between">
        <div className="caps-mono text-[9px] tracking-[0.08em] text-tertiary">
          Pattern balance
        </div>
        <Link
          href="/me/workouts/progression"
          className="font-mono text-[11px] text-tertiary hover:text-accent-dim"
        >
          Progression →
        </Link>
      </div>

      {review.length === 0 ? (
        <p className="mt-4 text-[13px] text-secondary">
          Not enough recent volume to analyze pattern balance yet.
        </p>
      ) : (
        <ul className="mt-3 space-y-2" data-testid="pattern-balance">
          {review.map((r) => {
            const t = trendGlyph(r.trend);
            return (
              <li
                key={r.pattern}
                className="flex items-center justify-between gap-3 text-[13px]"
              >
                <span className="text-primary">{label(r.pattern)}</span>
                <span className="flex items-center gap-2 font-mono text-[12px] tabular-nums text-tertiary">
                  <span aria-hidden className={t.cls}>
                    {t.glyph}
                  </span>
                  <span>
                    {r.currentTarget}
                    {r.proposedTarget !== r.currentTarget && (
                      <span className="text-accent-dim"> → {r.proposedTarget}</span>
                    )}
                    <span className="ml-1 text-tertiary">sets</span>
                  </span>
                  {r.deload && (
                    <span className="caps-mono rounded-full bg-warn/15 px-1.5 text-[8px] tracking-[0.06em] text-warn">
                      deload
                    </span>
                  )}
                </span>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
