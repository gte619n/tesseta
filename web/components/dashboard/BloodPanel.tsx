import { bloodPanel } from "@/lib/fixtures/dashboard";
import { SectionTitle } from "./SectionTitle";

export function BloodPanel({ compact = false }: { compact?: boolean }) {
  const showRangeLabels = !compact;
  return (
    <div
      className={`rounded-[10px] border-[0.5px] border-border-default bg-surface ${
        compact ? "px-[15px] py-[13px]" : "px-[18px] py-4"
      }`}
    >
      <div className={`mb-${compact ? "[11px]" : "[14px]"} flex items-center justify-between`}>
        <SectionTitle compact={compact}>Blood panel</SectionTitle>
        <span className="font-mono text-[9px] tracking-[0.06em] text-tertiary tabular">
          {bloodPanel.date}
        </span>
      </div>
      <div className="space-y-3">
        {bloodPanel.markers.map((m, i) => (
          <div
            key={m.name}
            className={i === bloodPanel.markers.length - 1 ? "mb-0" : ""}
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
                className="absolute left-0 h-full bg-accent-bg"
                style={{ width: `${m.goodFillPct}%` }}
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
