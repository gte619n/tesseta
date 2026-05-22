import Link from "next/link";
import { SectionTitle } from "./SectionTitle";

export type BloodPanelMarker = {
  name: string;
  value: string;
  unit: string;
  tone: "good" | "warn" | "alert";
  goodFillPct: number;
  goodLeftPct: number;
  tickPct: number;
  labels: { min: string; threshold: string; max: string };
};

export type BloodPanelData = {
  date: string | null;
  markers: BloodPanelMarker[];
};

export function BloodPanel({
  data,
  compact = false,
}: {
  data: BloodPanelData | null;
  compact?: boolean;
}) {
  const showRangeLabels = !compact;

  if (!data || data.markers.length === 0) {
    return (
      <div
        className={`rounded-[10px] border-[0.5px] border-border-default bg-surface ${
          compact ? "px-[15px] py-[13px]" : "px-[18px] py-4"
        }`}
      >
        <div className={`mb-${compact ? "[11px]" : "[14px]"} flex items-center justify-between`}>
          <SectionTitle compact={compact}>Blood panel</SectionTitle>
        </div>
        <p className="text-[12px] text-secondary">
          No readings yet.{" "}
          <Link
            href="/me/blood"
            className="font-medium text-accent-dim underline-offset-2 hover:underline"
          >
            Add one
          </Link>
          .
        </p>
      </div>
    );
  }

  return (
    <div
      className={`rounded-[10px] border-[0.5px] border-border-default bg-surface ${
        compact ? "px-[15px] py-[13px]" : "px-[18px] py-4"
      }`}
    >
      <div className={`mb-${compact ? "[11px]" : "[14px]"} flex items-center justify-between`}>
        <Link
          href="/me/blood"
          className="group inline-flex items-center gap-2.5 hover:text-accent-dim"
        >
          <span
            aria-hidden
            className={`inline-block w-[3px] rounded-[2px] bg-accent ${
              compact ? "h-[11px]" : "h-3.5"
            }`}
          />
          <span
            className={`font-medium tracking-[-0.01em] text-primary group-hover:text-accent-dim ${
              compact ? "text-[12px]" : "text-[14px]"
            }`}
          >
            Blood panel
          </span>
        </Link>
        {data.date && (
          <span className="font-mono text-[9px] tracking-[0.06em] text-tertiary tabular">
            {data.date}
          </span>
        )}
      </div>
      <div className="space-y-3">
        {data.markers.map((m, i) => (
          <div
            key={m.name}
            className={i === data.markers.length - 1 ? "mb-0" : ""}
          >
            <div className="mb-[5px] flex items-baseline justify-between">
              <span
                className={`font-medium text-primary ${
                  compact ? "text-[11px]" : "text-[12px]"
                }`}
              >
                {m.name}
              </span>
              <span
                className={`font-mono font-medium tabular ${
                  compact ? "text-[12px]" : "text-[13px]"
                } ${m.tone === "warn" ? "text-warn" : m.tone === "alert" ? "text-alert" : "text-good"}`}
              >
                {m.value}
                <span
                  className={`ml-[3px] font-normal text-tertiary ${
                    compact ? "text-[9px]" : "text-[10px]"
                  }`}
                >
                  {m.unit}
                </span>
              </span>
            </div>
            <div
              className={`relative bg-canvas ${compact ? "h-[3px]" : "h-1"}`}
            >
              <div
                className="absolute h-full bg-accent-bg"
                style={{ left: `${m.goodLeftPct}%`, width: `${m.goodFillPct}%` }}
              />
              <div
                className={`absolute w-0.5 bg-primary ${compact ? "h-[3px]" : "h-1"}`}
                style={{ left: `${m.tickPct}%` }}
              />
            </div>
            {showRangeLabels ? (
              <div className="mt-[3px] flex justify-between font-mono text-[9px] text-quaternary tabular">
                <span>{m.labels.min}</span>
                <span className="text-good">{m.labels.threshold}</span>
                <span>{m.labels.max}</span>
              </div>
            ) : null}
          </div>
        ))}
      </div>
    </div>
  );
}
