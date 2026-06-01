"use client";

import { useState, useTransition } from "react";
import Link from "next/link";
import type { TodaysDose, TimeWindow } from "@/lib/types/medication";
import { TIME_WINDOW_LABELS } from "@/lib/types/medication";

interface TodaysDosesCardProps {
  doses: TodaysDose[];
  logDose: (medicationId: string, window: TimeWindow) => Promise<void>;
  compact?: boolean;
}

export function TodaysDosesCard({ doses, logDose, compact = false }: TodaysDosesCardProps) {
  const [isPending, startTransition] = useTransition();
  const [pendingId, setPendingId] = useState<string | null>(null);

  const takenCount = doses.filter(d => d.taken).length;
  const totalCount = doses.length;

  function handleToggle(medicationId: string, window: TimeWindow) {
    const key = `${medicationId}:${window}`;
    setPendingId(key);
    startTransition(async () => {
      await logDose(medicationId, window);
      setPendingId(null);
    });
  }

  if (compact) {
    return (
      <div className="rounded-[10px] border-[0.5px] border-border-default bg-surface px-5 py-[18px]">
        <div className="flex items-center justify-between">
          <Link
            href="/me/meds"
            className="group inline-flex items-center gap-2.5 hover:text-accent-dim"
          >
            <span
              aria-hidden
              className="inline-block h-3.5 w-[3px] rounded-[2px] bg-accent"
            />
            <span className="text-[14px] font-medium tracking-[-0.01em] text-primary group-hover:text-accent-dim">
              Today&apos;s doses
            </span>
            <span
              aria-hidden
              className="font-mono text-[11px] text-tertiary opacity-0 transition-opacity group-hover:opacity-100"
            >
              →
            </span>
          </Link>
          {totalCount > 0 && (
            <span className="font-mono text-[11px] text-tertiary">
              {takenCount}/{totalCount}
            </span>
          )}
        </div>

        {doses.length === 0 ? (
          <p className="mt-3 text-[13px] text-secondary">
            No scheduled doses for today.
          </p>
        ) : (
          <div className="mt-3 space-y-1.5">
            {doses.map((dose) => {
              const key = `${dose.medicationId}:${dose.window}`;
              const isLoading = isPending && pendingId === key;

              return (
                <div
                  key={key}
                  className="flex items-center gap-3"
                >
                  <button
                    type="button"
                    onClick={() => handleToggle(dose.medicationId, dose.window)}
                    disabled={isLoading}
                    className={`flex h-5 w-5 flex-shrink-0 items-center justify-center rounded border transition-colors ${
                      dose.taken
                        ? "border-good bg-good text-white"
                        : "border-border-default bg-canvas hover:border-accent"
                    }`}
                    aria-label={dose.taken ? "Mark as not taken" : "Mark as taken"}
                  >
                    {isLoading ? (
                      <span className="h-3 w-3 animate-spin rounded-full border-2 border-current border-t-transparent" />
                    ) : dose.taken ? (
                      <svg className="h-3 w-3" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={3}>
                        <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
                      </svg>
                    ) : null}
                  </button>
                  <div className="flex-1 min-w-0">
                    <div className={`truncate text-[13px] ${dose.taken ? "text-secondary line-through" : "text-primary"}`}>
                      {dose.drugName}
                    </div>
                  </div>
                  <div className="flex-shrink-0 text-right">
                    <span className={`font-mono text-[11px] ${dose.taken ? "text-tertiary" : "text-secondary"}`}>
                      {dose.dose} {dose.unit}
                    </span>
                    <span className="ml-1.5 text-[10px] text-tertiary">
                      {TIME_WINDOW_LABELS[dose.window]}
                    </span>
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>
    );
  }

  // Full card (non-compact) - not used on dashboard but available
  return (
    <div className="rounded-xl border-[0.5px] border-border-default bg-surface p-5">
      <div className="flex items-center justify-between">
        <h2 className="text-[16px] font-medium text-primary">Today&apos;s doses</h2>
        <span className="font-mono text-[12px] text-secondary">
          {takenCount}/{totalCount} taken
        </span>
      </div>

      {doses.length === 0 ? (
        <p className="mt-4 text-center text-[13px] text-secondary">
          No scheduled doses for today.
        </p>
      ) : (
        <div className="mt-4 space-y-2">
          {doses.map((dose) => {
            const key = `${dose.medicationId}:${dose.window}`;
            const isLoading = isPending && pendingId === key;

            return (
              <div
                key={key}
                className="flex items-center gap-3 rounded-lg bg-canvas-sunken px-3 py-2"
              >
                <button
                  type="button"
                  onClick={() => handleToggle(dose.medicationId, dose.window)}
                  disabled={isLoading}
                  className={`flex h-6 w-6 flex-shrink-0 items-center justify-center rounded-full border-2 transition-colors ${
                    dose.taken
                      ? "border-good bg-good text-white"
                      : "border-border-default bg-surface hover:border-accent"
                  }`}
                >
                  {isLoading ? (
                    <span className="h-3 w-3 animate-spin rounded-full border-2 border-current border-t-transparent" />
                  ) : dose.taken ? (
                    <svg className="h-3.5 w-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={3}>
                      <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
                    </svg>
                  ) : null}
                </button>
                <div className="flex-1">
                  <div className={`text-[14px] font-medium ${dose.taken ? "text-secondary line-through" : "text-primary"}`}>
                    {dose.drugName}
                  </div>
                  <div className="text-[12px] text-tertiary">
                    {dose.dose} {dose.unit} · {TIME_WINDOW_LABELS[dose.window]}
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
