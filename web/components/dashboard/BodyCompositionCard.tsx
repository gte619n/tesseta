import Link from "next/link";
import { WeightChart } from "@/components/dashboard/WeightChart";
import { WeightValue } from "@/components/dashboard/WeightValue";
import { BodyCompositionPrimaryDelta } from "@/components/dashboard/BodyCompositionPrimaryDelta";
import type { BodyCompositionView } from "@/lib/body-composition-dashboard";

export function BodyCompositionCard({
  view,
}: {
  view: BodyCompositionView | null;
}) {
  if (!view) {
    return (
      <div className="mb-3 rounded-[10px] border-[0.5px] border-border-default bg-surface px-5 py-[18px]">
        <BodyCompositionTitle />
        <p className="mt-3 text-[13px] leading-[1.55] text-secondary">
          No body-comp data yet.{" "}
          <Link
            className="font-medium text-accent-dim underline-offset-2 hover:underline"
            href="/me/body-composition"
          >
            Connect Google Health
          </Link>{" "}
          to start syncing weight and body fat from your scale.
        </p>
      </div>
    );
  }

  return (
    <div className="mb-3 rounded-[10px] border-[0.5px] border-border-default bg-surface px-5 py-[18px]">
      <div className="mb-3.5 flex items-start justify-between">
        <div>
          <BodyCompositionTitle />
          <div className="mt-3 flex items-baseline gap-[18px]">
            <div>
              <div className="font-mono text-[36px] font-medium leading-none tracking-[-0.03em] text-primary tabular">
                <WeightValue
                  lb={view.primaryWeightLb}
                  unitClassName="ml-1 text-[13px] font-normal text-tertiary"
                />
              </div>
              {view.primaryDeltaLb !== null ? (
                <BodyCompositionPrimaryDelta
                  deltaLb={view.primaryDeltaLb}
                  suffix="vs 90d avg"
                />
              ) : null}
            </div>
            <div className="h-[42px] w-px bg-border-default" aria-hidden />
            <div>
              <div className="font-mono text-[18px] font-medium leading-none tracking-[-0.01em] text-primary tabular">
                {view.bodyFatPercent !== null
                  ? view.bodyFatPercent.toFixed(1)
                  : "—"}
                <span className="ml-[3px] text-[10px] font-normal text-tertiary">
                  % fat
                </span>
              </div>
            </div>
            <div>
              <div className="font-mono text-[18px] font-medium leading-none tracking-[-0.01em] text-primary tabular">
                {view.leanMassLb !== null ? (
                  <WeightValue
                    lb={view.leanMassLb}
                    unitClassName="ml-[3px] text-[10px] font-normal text-tertiary"
                  />
                ) : (
                  <>
                    —
                    <span className="ml-[3px] text-[10px] font-normal text-tertiary">
                      lb
                    </span>
                  </>
                )}
                <span className="ml-[3px] text-[10px] font-normal text-tertiary">
                  lean
                </span>
              </div>
            </div>
          </div>
        </div>
        <div
          className="caps-mono shrink-0 text-[9px] tracking-[0.06em] text-tertiary"
          title={new Date(view.lastUpdated).toLocaleString()}
        >
          Updated {formatUpdated(view.lastUpdated)}
        </div>
      </div>
      <WeightChart
        variant="desktop"
        series={view.series}
        yMin={view.yMin}
        yMax={view.yMax}
        xLabels={view.xLabels}
      />
      <div className="mt-2.5 flex gap-3.5 border-t-[0.5px] border-border-subtle pt-2.5">
        <div className="caps-mono flex items-center gap-1.5 text-[10px] tracking-[0.04em] text-secondary">
          <span
            aria-hidden
            className="inline-block h-[2px] w-2.5 rounded-[1px] bg-accent"
          />
          DAILY
        </div>
        <div className="caps-mono flex items-center gap-1.5 text-[10px] tracking-[0.04em] text-secondary">
          <span
            aria-hidden
            className="inline-block h-0 w-2.5 border-t border-dashed border-primary opacity-40"
          />
          7-DAY AVG
        </div>
      </div>
    </div>
  );
}

// Links the body-composition section title to /me/body-composition.
// Mirrors the SectionTitle look but adds a hover affordance + arrow.
function BodyCompositionTitle() {
  return (
    <Link
      href="/me/body-composition"
      className="group inline-flex items-center gap-2.5 hover:text-accent-dim"
    >
      <span
        aria-hidden
        className="inline-block h-3.5 w-[3px] rounded-[2px] bg-accent"
      />
      <span className="text-[14px] font-medium tracking-[-0.01em] text-primary group-hover:text-accent-dim">
        Body composition
      </span>
      <span
        aria-hidden
        className="font-mono text-[11px] text-tertiary opacity-0 transition-opacity group-hover:opacity-100"
      >
        →
      </span>
    </Link>
  );
}

// "Last updated" label for the body-comp card: relative for recent weigh-ins
// ("today" / "yesterday" / "Nd ago"), else an absolute short date. Computed on
// the server (the page is force-dynamic), so it's the request-time delta.
function formatUpdated(iso: string): string {
  const then = new Date(iso).getTime();
  const days = Math.floor((Date.now() - then) / (24 * 60 * 60 * 1000));
  if (days <= 0) return "today";
  if (days === 1) return "yesterday";
  if (days < 7) return `${days}d ago`;
  return new Date(iso)
    .toLocaleDateString("en-US", { month: "short", day: "numeric" })
    .toUpperCase();
}
