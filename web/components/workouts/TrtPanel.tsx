"use client";

import { useEffect, useState } from "react";
import type {
  TrtContext,
  TrtMarker,
  TrtMarkerStatus,
  TrtMarkerTrend,
  DangerFlag,
} from "@/lib/types/trt";

// TRT / monitoring-panel surface for the program-designer chat (IMPL-18 /
// ADR-0015). Fetches the user's relevant labs (value vs. reference range,
// trend, status) and any danger-flags on mount, and renders:
//   - a prominent danger banner when any flags are present (red DANGER, amber
//     WARNING) urging clinician contact, and
//   - a collapsible monitoring-panel of markers.
// The model's cited TRT guidance arrives as normal assistant text in the
// conversation (rendered by ChatMarkdown) — nothing special is parsed here.
//
// Only renders when onTrt is true OR there is at least one marker. A loader can
// be supplied (server action) so the page pre-fetches; otherwise it falls back
// to the app/api proxy route.

type Props = {
  // Optional server-action loader; falls back to the proxy route when absent.
  loadTrtContext?: () => Promise<TrtContext>;
  // Optional pre-fetched context (skips the on-mount fetch entirely).
  initialContext?: TrtContext | null;
};

const STATUS_STYLES: Record<TrtMarkerStatus, string> = {
  LOW: "text-accent",
  IN_RANGE: "text-primary",
  HIGH: "text-alert",
  WATCH: "text-warn",
  UNKNOWN: "text-tertiary",
};

const STATUS_LABEL: Record<TrtMarkerStatus, string> = {
  LOW: "Low",
  IN_RANGE: "In range",
  HIGH: "High",
  WATCH: "Watch",
  UNKNOWN: "—",
};

const TREND_ARROW: Record<TrtMarkerTrend, string> = {
  RISING: "↑",
  FALLING: "↓",
  STABLE: "→",
  UNKNOWN: "",
};

function formatValue(m: TrtMarker): string {
  if (m.value == null) return "—";
  const v = Number.isInteger(m.value) ? `${m.value}` : m.value.toFixed(1);
  return m.unit ? `${v} ${m.unit}` : v;
}

function formatRange(m: TrtMarker): string | null {
  if (m.refLow == null && m.refHigh == null) return null;
  const lo = m.refLow == null ? "" : `${m.refLow}`;
  const hi = m.refHigh == null ? "" : `${m.refHigh}`;
  return `${lo}–${hi}`;
}

function DangerBanner({ flags }: { flags: DangerFlag[] }) {
  if (flags.length === 0) return null;
  const hasDanger = flags.some((f) => f.severity === "DANGER");
  return (
    <div
      role="alert"
      className={`rounded-[10px] border-[0.5px] px-4 py-3 ${
        hasDanger
          ? "border-alert/50 bg-alert-bg"
          : "border-warn/50 bg-warn-bg"
      }`}
    >
      <p
        className={`caps-mono text-[9px] tracking-[0.06em] ${
          hasDanger ? "text-alert" : "text-warn"
        }`}
      >
        {hasDanger ? "Danger — contact your clinician" : "Warning"}
      </p>
      <ul className="mt-1.5 space-y-1">
        {flags.map((f, i) => (
          <li
            key={`${f.marker}-${i}`}
            className={`text-[12px] leading-[1.45] ${
              f.severity === "DANGER" ? "text-alert" : "text-warn"
            }`}
          >
            <span className="caps-mono mr-1.5 text-[9px] tracking-[0.06em]">
              {f.marker}
            </span>
            {f.message}
          </li>
        ))}
      </ul>
    </div>
  );
}

function MarkerRow({ marker }: { marker: TrtMarker }) {
  const range = formatRange(marker);
  const arrow = TREND_ARROW[marker.trend];
  return (
    <div className="flex items-center justify-between gap-3 border-b-[0.5px] border-border-subtle py-1.5 last:border-b-0">
      <div className="min-w-0">
        <span className="block truncate text-[12px] text-secondary">
          {marker.label}
        </span>
        {range ? (
          <span className="caps-mono text-[8px] tracking-[0.06em] text-tertiary">
            ref {range}
            {marker.unit ? ` ${marker.unit}` : ""}
          </span>
        ) : null}
      </div>
      <div className="flex shrink-0 items-center gap-2">
        {arrow ? (
          <span
            className="text-[11px] text-tertiary"
            aria-label={`trend ${marker.trend.toLowerCase()}`}
          >
            {arrow}
          </span>
        ) : null}
        <span
          className={`text-[13px] font-medium tabular-nums ${STATUS_STYLES[marker.status]}`}
        >
          {formatValue(marker)}
        </span>
        <span
          className={`caps-mono w-[58px] text-right text-[8px] tracking-[0.06em] ${STATUS_STYLES[marker.status]}`}
        >
          {STATUS_LABEL[marker.status]}
        </span>
      </div>
    </div>
  );
}

export function TrtPanel({ loadTrtContext, initialContext }: Props) {
  const [context, setContext] = useState<TrtContext | null>(
    initialContext ?? null,
  );
  const [loading, setLoading] = useState(!initialContext);
  const [open, setOpen] = useState(true);

  useEffect(() => {
    if (initialContext) return;
    let cancelled = false;
    setLoading(true);
    const fetcher =
      loadTrtContext ??
      (async () => {
        const res = await fetch("/api/workout-programs/chat/trt-context");
        if (!res.ok) throw new Error(`trt-context returned ${res.status}`);
        return (await res.json()) as TrtContext;
      });
    fetcher()
      .then((ctx) => {
        if (!cancelled) setContext(ctx);
      })
      .catch(() => {
        if (!cancelled) setContext(null);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [loadTrtContext, initialContext]);

  if (loading) return null;
  // Only render when on TRT or there are markers to show.
  if (!context || (!context.onTrt && context.markers.length === 0)) return null;

  const dangerFlags = context.dangerFlags ?? [];
  const markers = context.markers ?? [];

  return (
    <div className="space-y-3">
      <DangerBanner flags={dangerFlags} />

      {markers.length > 0 ? (
        <section className="rounded-[14px] border-[0.5px] border-border-default bg-surface">
          <button
            type="button"
            onClick={() => setOpen((v) => !v)}
            aria-expanded={open}
            className="flex w-full cursor-pointer items-center justify-between border-b-[0.5px] border-border-subtle px-5 py-3"
          >
            <span className="text-[14px] font-medium text-primary">
              TRT monitoring
            </span>
            <span className="caps-mono text-[9px] tracking-[0.06em] text-tertiary">
              {open ? "Hide ▲" : "Show ▼"}
            </span>
          </button>
          {open ? (
            <div className="px-5 py-2">
              {markers.map((m) => (
                <MarkerRow key={m.name} marker={m} />
              ))}
              <p className="mt-2 pb-1 text-[11px] leading-[1.4] text-tertiary">
                Reference ranges and guidance are decision-support only — act on
                them with your prescriber.
              </p>
            </div>
          ) : null}
        </section>
      ) : null}
    </div>
  );
}
