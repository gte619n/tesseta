"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import type {
  E1rmHistory,
  LiftRef,
  TrackedExercise,
} from "@/lib/types/workout-stats";
import { projectSeries, toLinePath, toAreaPath } from "@/lib/chart";
import { formatNumber } from "@/lib/format-number";
import { formatDateUpper } from "@/lib/format-date";

// Estimated-1RM trend per lift (IMPL-WEB-WORKOUT-01 D5/D6/D15). Defaults to the
// top lift; a picker charts any tracked exercise, fetched lazily through the
// route handler. The historical curve is Epley-estimated (labeled as such); the
// endpoint dot is the engine's authoritative Kalman belief with its ± sigma band.

const WIDTH = 520;
const HEIGHT = 190;
const PAD_X = 36;
const PAD_BOTTOM = 26;

export function StrengthTrendChart({
  lifts,
  defaultLifts,
  initialHistory,
}: {
  lifts: TrackedExercise[];
  defaultLifts: LiftRef[];
  initialHistory: E1rmHistory | null;
}) {
  const firstId =
    initialHistory?.exerciseId ?? defaultLifts[0]?.exerciseId ?? lifts[0]?.exerciseId ?? "";
  const [selected, setSelected] = useState<string>(firstId);
  const [history, setHistory] = useState<E1rmHistory | null>(initialHistory);
  const [loading, setLoading] = useState(false);
  const [hover, setHover] = useState<number | null>(null);

  // Picker options: prefer the tracked list (has lastPerformed); fall back to
  // the default lifts so there's always something selectable.
  const options: LiftRef[] = useMemo(() => {
    if (lifts.length > 0) {
      return lifts.map((l) => ({ exerciseId: l.exerciseId, exerciseName: l.exerciseName }));
    }
    return defaultLifts;
  }, [lifts, defaultLifts]);

  // Load the selected lift's history whenever it changes and we don't already
  // hold it — including on mount for the pre-selected lift (so the chart shows
  // data without the user having to open the picker; covers the case where
  // there's no server-provided default and thus no initialHistory).
  useEffect(() => {
    if (!selected) return;
    if (history?.exerciseId === selected) return;
    let cancelled = false;
    setLoading(true);
    fetch(`/api/workout-stats/e1rm-history?exerciseId=${encodeURIComponent(selected)}`)
      .then((res) => (res.ok ? res.json() : null))
      .then((data: E1rmHistory | null) => {
        if (cancelled) return;
        setHistory(
          data ?? { exerciseId: selected, exerciseName: selected, points: [], currentBelief: null },
        );
      })
      .catch(() => {
        if (!cancelled) {
          setHistory({ exerciseId: selected, exerciseName: selected, points: [], currentBelief: null });
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selected]);

  const points = history?.points ?? [];
  const values = points.map((p) => p.e1rmLbs);
  const hasData = values.length > 0;

  const geom = useMemo(() => {
    const all = [...values];
    if (history?.currentBelief) all.push(history.currentBelief.e1rmLbs);
    const min = Math.min(...all);
    const max = Math.max(...all);
    const pad = (max - min) * 0.1 || max * 0.05 || 1;
    return {
      width: WIDTH,
      height: HEIGHT,
      yMin: min - pad,
      yMax: max + pad,
      padX: PAD_X,
      padBottom: PAD_BOTTOM,
    };
  }, [values, history]);

  const projected = hasData ? projectSeries(values, geom) : [];
  const linePath = toLinePath(projected);
  const areaPath = toAreaPath(projected, HEIGHT - PAD_BOTTOM);
  const latest = points[points.length - 1] ?? null;

  return (
    <div className="rounded-[14px] border-[0.5px] border-border-default bg-surface px-6 py-5">
      <div className="flex items-center justify-between gap-3">
        <div className="caps-mono text-[9px] tracking-[0.08em] text-tertiary">
          Strength trend
        </div>
        {options.length > 0 && (
          <LiftPicker options={options} selected={selected} onSelect={setSelected} />
        )}
      </div>

      {!hasData ? (
        <p className="mt-4 text-[13px] text-secondary">
          {loading ? "Loading…" : "No estimated-1RM history for this lift yet."}
        </p>
      ) : (
        <>
          <div className="mt-2 flex items-baseline gap-2">
            <span
              className="font-mono text-[24px] font-medium leading-none text-primary tabular-nums"
              data-testid="strength-latest"
            >
              {formatNumber(
                history?.currentBelief?.e1rmLbs ?? latest?.e1rmLbs ?? 0,
              )}
            </span>
            <span className="text-[12px] text-tertiary">
              lb e1RM{history?.currentBelief ? "" : " (est.)"}
            </span>
          </div>

          <div className="relative mt-2">
            <svg
              viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
              className="w-full"
              role="img"
              aria-label={`Estimated 1RM trend for ${history?.exerciseName ?? "lift"}`}
            >
              <path d={areaPath} className="fill-accent/10" />
              <path
                d={linePath}
                className="fill-none stroke-accent"
                strokeWidth={1.5}
              />
              {projected.map((pt, i) => (
                <circle
                  key={i}
                  cx={pt.x}
                  cy={pt.y}
                  r={hover === i ? 3.5 : 2}
                  data-testid="strength-point"
                  data-e1rm={formatNumber(points[i]!.e1rmLbs)}
                  className={
                    points[i]!.lowConfidence ? "fill-tertiary" : "fill-accent"
                  }
                  onMouseEnter={() => setHover(i)}
                  onMouseLeave={() => setHover((h) => (h === i ? null : h))}
                />
              ))}
            </svg>

            {hover != null && points[hover] && (
              <div
                className="pointer-events-none absolute left-1/2 top-0 -translate-x-1/2 rounded-[8px] border border-border-default bg-canvas px-3 py-2 text-[11px] shadow-lg"
                data-testid="strength-tooltip"
              >
                <div className="font-mono tabular-nums text-tertiary">
                  {formatDateUpper(points[hover]!.date)}
                </div>
                <div className="mt-0.5 font-medium text-primary tabular-nums">
                  {formatNumber(points[hover]!.e1rmLbs)} lb
                  {points[hover]!.lowConfidence && (
                    <span className="ml-1 text-tertiary">(est.)</span>
                  )}
                </div>
              </div>
            )}
          </div>

          <p className="mt-2 text-[11px] text-tertiary">
            Curve is an estimate from your logged sets; the endpoint is the
            engine&apos;s current belief.
          </p>
        </>
      )}
    </div>
  );
}

// A compact, searchable, Tesseta-styled lift picker (replaces the native
// <select>). Filters the option list by a case-insensitive name substring;
// closes on outside-click or Escape. The trigger keeps data-testid="lift-picker"
// and each option is a role="option" button so the whole thing is keyboard- and
// test-drivable.
function LiftPicker({
  options,
  selected,
  onSelect,
}: {
  options: LiftRef[];
  selected: string;
  onSelect: (exerciseId: string) => void;
}) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const ref = useRef<HTMLDivElement>(null);

  const selectedName =
    options.find((o) => o.exerciseId === selected)?.exerciseName ?? "Choose a lift";
  const q = query.trim().toLowerCase();
  const filtered = q
    ? options.filter((o) => o.exerciseName.toLowerCase().includes(q))
    : options;

  useEffect(() => {
    if (!open) return;
    const onDoc = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") setOpen(false);
    };
    document.addEventListener("mousedown", onDoc);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onDoc);
      document.removeEventListener("keydown", onKey);
    };
  }, [open]);

  return (
    <div ref={ref} className="relative">
      <button
        type="button"
        data-testid="lift-picker"
        aria-haspopup="listbox"
        aria-expanded={open}
        onClick={() => {
          setOpen((o) => !o);
          setQuery("");
        }}
        className="flex max-w-[200px] items-center gap-1.5 rounded-[8px] border border-border-default bg-canvas px-2.5 py-1 text-[12px] text-primary hover:border-accent/60 focus:outline-none focus:ring-2 focus:ring-accent"
      >
        <span className="truncate">{selectedName}</span>
        <svg
          width="12"
          height="12"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="2"
          strokeLinecap="round"
          strokeLinejoin="round"
          className="shrink-0 text-tertiary"
          aria-hidden
        >
          <polyline points="6 9 12 15 18 9" />
        </svg>
      </button>

      {open && (
        <div className="absolute right-0 z-30 mt-1 w-[248px] overflow-hidden rounded-[10px] border border-border-default bg-surface shadow-[0_16px_48px_rgba(0,0,0,0.16)]">
          <div className="border-b-[0.5px] border-border-subtle p-2">
            <input
              autoFocus
              type="text"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Search lifts…"
              data-testid="lift-search"
              className="w-full rounded-md border border-border-default bg-canvas px-2.5 py-1.5 text-[12px] text-primary focus:outline-none focus:ring-2 focus:ring-accent"
            />
          </div>
          <ul role="listbox" className="max-h-[260px] overflow-y-auto py-1">
            {filtered.length === 0 ? (
              <li className="px-3 py-2 text-[12px] text-tertiary">No matching lifts</li>
            ) : (
              filtered.map((o) => {
                const active = o.exerciseId === selected;
                return (
                  <li key={o.exerciseId}>
                    <button
                      type="button"
                      role="option"
                      aria-selected={active}
                      data-testid="lift-option"
                      onClick={() => {
                        onSelect(o.exerciseId);
                        setOpen(false);
                        setQuery("");
                      }}
                      className={
                        "flex w-full items-center justify-between gap-2 px-3 py-1.5 text-left text-[12px] hover:bg-canvas " +
                        (active ? "text-accent-dim" : "text-primary")
                      }
                    >
                      <span className="truncate">{o.exerciseName}</span>
                      {active && (
                        <svg
                          width="12"
                          height="12"
                          viewBox="0 0 24 24"
                          fill="none"
                          stroke="currentColor"
                          strokeWidth="2.5"
                          strokeLinecap="round"
                          strokeLinejoin="round"
                          className="shrink-0"
                          aria-hidden
                        >
                          <polyline points="20 6 9 17 4 12" />
                        </svg>
                      )}
                    </button>
                  </li>
                );
              })
            )}
          </ul>
        </div>
      )}
    </div>
  );
}
