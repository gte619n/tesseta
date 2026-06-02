"use client";

import { useState } from "react";
import type {
  ScheduledWorkoutResponse,
  PrescriptionExercise,
} from "@/lib/types/workout-program";
import { ExerciseDetailSheet } from "./ExerciseDetailSheet";
import { BLOCK_TYPE_LABEL } from "@/lib/types/exercise";
import {
  formatDuration,
  formatLoggedSets,
  totalLoggedSets,
} from "@/lib/workout-format";

function formatWhen(session: ScheduledWorkoutResponse): string {
  const iso = session.completedAt ?? `${session.date}T00:00:00`;
  const d = new Date(iso);
  return d
    .toLocaleDateString("en-US", {
      weekday: "short",
      month: "short",
      day: "numeric",
      year: "numeric",
    })
    .toUpperCase();
}

function formatClock(completedAt: string | null): string | null {
  if (!completedAt) return null;
  return new Date(completedAt).toLocaleTimeString("en-US", {
    hour: "numeric",
    minute: "2-digit",
  });
}

export function WorkoutHistoryList({
  sessions,
}: {
  sessions: ScheduledWorkoutResponse[];
}) {
  const [sheetExercise, setSheetExercise] = useState<PrescriptionExercise | null>(
    null,
  );

  if (sessions.length === 0) {
    return (
      <p className="text-[13px] text-secondary">
        No completed workouts yet. Sessions you finish — or import — show up here.
      </p>
    );
  }

  return (
    <>
      <div className="space-y-2">
        {sessions.map((s) => (
          <HistoryRow
            key={`${s.phaseId}-${s.scheduledId}`}
            session={s}
            onOpenExercise={setSheetExercise}
          />
        ))}
      </div>
      <ExerciseDetailSheet
        exercise={sheetExercise}
        onClose={() => setSheetExercise(null)}
      />
    </>
  );
}

function HistoryRow({
  session,
  onOpenExercise,
}: {
  session: ScheduledWorkoutResponse;
  onOpenExercise: (e: PrescriptionExercise) => void;
}) {
  const [open, setOpen] = useState(false);
  const setCount = totalLoggedSets(session);
  const clock = formatClock(session.completedAt);

  return (
    <div className="rounded-[10px] border-[0.5px] border-border-default bg-canvas px-4 py-3">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        className="flex w-full items-center justify-between gap-3 text-left"
      >
        <div className="min-w-0">
          <span className="text-[14px] font-medium text-primary">
            {session.dayLabel}
          </span>
          <div className="caps-mono mt-0.5 flex flex-wrap items-center gap-x-2 gap-y-0.5 text-[10px] tracking-[0.06em] text-tertiary">
            <span>{formatWhen(session)}</span>
            {clock ? <span>· {clock}</span> : null}
            {session.durationSeconds != null ? (
              <span>· {formatDuration(session.durationSeconds)}</span>
            ) : null}
            {setCount > 0 ? (
              <span>
                · {setCount} set{setCount === 1 ? "" : "s"}
              </span>
            ) : null}
          </div>
        </div>
        <span className="caps-mono shrink-0 text-[10px] tracking-[0.06em] text-tertiary">
          {open ? "−" : "+"}
        </span>
      </button>

      {open ? (
        <div className="mt-3 space-y-2 border-t-[0.5px] border-border-subtle pt-3">
          {session.session.blocks.map((block) => (
            <div key={block.blockId}>
              <div className="flex items-center gap-2">
                <span className="caps-mono rounded-[3px] bg-canvas-muted px-1.5 py-px text-[9px] tracking-[0.06em] text-secondary">
                  {BLOCK_TYPE_LABEL[block.type]}
                </span>
                <span className="text-[12px] font-medium text-primary">
                  {block.title}
                </span>
              </div>
              <ul className="mt-1 space-y-0.5">
                {block.prescriptions.map((p, i) => {
                  const ex = p.exercise;
                  const name = ex?.name ?? p.notes ?? p.exerciseId;
                  const logged = formatLoggedSets(p);
                  return (
                    <li key={i}>
                      <button
                        type="button"
                        disabled={!ex}
                        onClick={() => ex && onOpenExercise(ex)}
                        className={`flex w-full items-start justify-between gap-3 rounded px-2 py-1 text-left ${
                          ex ? "cursor-pointer hover:bg-canvas-muted" : ""
                        }`}
                      >
                        <span className="min-w-0 text-[13px] text-primary">
                          {name}
                          {logged ? (
                            <span className="ml-2 text-[12px] text-tertiary">
                              {logged}
                            </span>
                          ) : null}
                        </span>
                        {ex ? (
                          <span className="shrink-0 text-[11px] text-tertiary">
                            View →
                          </span>
                        ) : null}
                      </button>
                    </li>
                  );
                })}
              </ul>
            </div>
          ))}
        </div>
      ) : null}
    </div>
  );
}
