import Link from "next/link";
import { SectionTitle } from "./SectionTitle";

function Sparkline({
  data,
  refLow,
  refHigh,
  tone,
  compact,
}: {
  data: { date: string; value: number }[];
  refLow: number | null;
  refHigh: number | null;
  tone: "good" | "warn" | "alert";
  compact?: boolean;
}) {
  if (data.length < 2) return null;

  const width = compact ? 48 : 56;
  const height = compact ? 20 : 24;
  const padding = 2;

  const values = data.map((d) => d.value);
  const minVal = Math.min(...values);
  const maxVal = Math.max(...values);
  const range = maxVal - minVal || 1;

  const points = data.map((d, i) => {
    const x = padding + (i / (data.length - 1)) * (width - 2 * padding);
    const y = height - padding - ((d.value - minVal) / range) * (height - 2 * padding);
    return { x, y };
  });

  const pathD = points.map((p, i) => `${i === 0 ? "M" : "L"} ${p.x} ${p.y}`).join(" ");

  const strokeColor =
    tone === "good"
      ? "var(--color-good)"
      : tone === "warn"
        ? "var(--color-warn)"
        : "var(--color-alert)";

  return (
    <svg width={width} height={height} className="overflow-visible">
      {/* Reference range band */}
      {refLow !== null && refHigh !== null && (
        <rect
          x={padding}
          y={height - padding - ((Math.min(refHigh, maxVal) - minVal) / range) * (height - 2 * padding)}
          width={width - 2 * padding}
          height={
            (((Math.min(refHigh, maxVal) - Math.max(refLow, minVal)) / range) *
              (height - 2 * padding)) || 1
          }
          fill="var(--color-good)"
          opacity={0.15}
          rx={1}
        />
      )}
      {/* Trend line */}
      <path d={pathD} fill="none" stroke={strokeColor} strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round" opacity={0.7} />
      {/* Latest point dot */}
      <circle
        cx={points[points.length - 1]?.x ?? 0}
        cy={points[points.length - 1]?.y ?? 0}
        r={2}
        fill={strokeColor}
      />
    </svg>
  );
}

export type BloodPanelMarker = {
  name: string;
  value: string;
  unit: string;
  tone: "good" | "warn" | "alert";
  goodFillPct: number;
  goodLeftPct: number;
  tickPct: number;
  labels: { min: string; threshold: string; max: string };
  sparkline?: { date: string; value: number }[];
  refLow?: number | null;
  refHigh?: number | null;
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
        compact ? "px-4 py-3.5" : "px-[18px] py-4"
      }`}
    >
      <div className={`mb-${compact ? "3" : "[14px]"} flex items-center justify-between`}>
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
      <div className={compact ? "space-y-3.5" : "space-y-3"}>
        {data.markers.map((m, i) => (
          <div
            key={m.name}
            className={i === data.markers.length - 1 ? "mb-0" : ""}
          >
            <div className="mb-[5px] flex items-center justify-between">
              <span
                className={`font-medium text-primary ${
                  compact ? "text-[11px]" : "text-[12px]"
                }`}
              >
                {m.name}
              </span>
              <div className="flex items-center gap-2">
                {m.sparkline && m.sparkline.length > 1 && (
                  <Sparkline
                    data={m.sparkline}
                    refLow={m.refLow ?? null}
                    refHigh={m.refHigh ?? null}
                    tone={m.tone}
                    compact={compact}
                  />
                )}
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
