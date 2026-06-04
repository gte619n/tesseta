import type { Vital } from "@/lib/dashboard-vitals";
import { Sparkline } from "./Sparkline";

export function StatCard({ stat }: { stat: Vital }) {
  return (
    <div className="rounded-[9px] border-[0.5px] border-border-default bg-surface px-3.5 pb-[11px] pt-[13px]">
      <div className="mb-1.5 flex items-center justify-between">
        <span className="caps-mono text-[10px] text-tertiary">{stat.label}</span>
        <i className={`ti ti-${stat.icon} text-[12px] text-quaternary`} aria-hidden />
      </div>
      <div className="font-mono text-[22px] font-medium leading-[1.1] tracking-[-0.02em] text-primary tabular">
        {stat.value}
        {stat.unit ? (
          <span className="ml-[3px] text-[11px] font-normal text-tertiary">
            {stat.unit}
          </span>
        ) : null}
      </div>
      <div className="mt-1.5 flex items-center justify-between">
        {stat.delta ? (
          <Delta
            direction={stat.delta.direction}
            value={stat.delta.value}
            window={stat.delta.window}
            tone={stat.delta.tone}
          />
        ) : stat.pill ? (
          <Pill tone={stat.pill.tone}>{stat.pill.label}</Pill>
        ) : null}
        <Sparkline points={stat.sparkline} />
      </div>
    </div>
  );
}

function Delta({
  direction,
  value,
  window,
  tone,
}: {
  direction: "up" | "down";
  value: string;
  window: string;
  tone: "good" | "alert";
}) {
  const arrow = direction === "down" ? "↓" : "↑";
  return (
    <span
      className={`font-mono text-[10px] font-medium tabular ${
        tone === "good" ? "text-good" : "text-alert"
      }`}
    >
      {arrow} {value} {window}
    </span>
  );
}

export function Pill({
  tone,
  children,
}: {
  tone: "good" | "warn" | "alert";
  children: React.ReactNode;
}) {
  const styles =
    tone === "good"
      ? "bg-good-bg text-accent-dim"
      : tone === "warn"
        ? "bg-warn-bg text-warn"
        : "bg-alert-bg text-alert";
  return (
    <span
      className={`caps-mono inline-block rounded-[3px] px-1.5 py-px text-[9px] tracking-[0.06em] ${styles}`}
    >
      {children}
    </span>
  );
}
