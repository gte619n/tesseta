"use client";

import { useState } from "react";
import type {
  ScheduledWorkoutResponse,
  PrescriptionExercise,
} from "@/lib/types/workout-program";
import { ExerciseDetailSheet } from "./ExerciseDetailSheet";
import { BLOCK_TYPE_LABEL } from "@/lib/types/exercise";
import { formatPrescription } from "@/lib/workout-format";

function formatDayDate(iso: string): string {
  return new Date(`${iso}T00:00:00`).toLocaleDateString("en-US", {
    weekday: "short",
    month: "short",
    day: "numeric",
  });
}

export function ProgramThisWeek({ sessions }: { sessions: ScheduledWorkoutResponse[] }) {
  const [sheetExercise, setSheetExercise] = useState<PrescriptionExercise | null>(null);

  if (sessions.length === 0) {
    return (
      <p className="text-[13px] text-secondary">
        No scheduled sessions this week. Activate the program to materialize its calendar.
      </p>
    );
  }

  const sorted = [...sessions].sort((a, b) => a.date.localeCompare(b.date));

  return (
    <>
      <div className="space-y-2">
        {sorted.map((s) => (
          <SessionRow key={s.scheduledId} session={s} onOpenExercise={setSheetExercise} />
        ))}
      </div>
      <ExerciseDetailSheet exercise={sheetExercise} onClose={() => setSheetExercise(null)} />
    </>
  );
}

function SessionRow({
  session,
  onOpenExercise,
}: {
  session: ScheduledWorkoutResponse;
  onOpenExercise: (e: PrescriptionExercise) => void;
}) {
  const [open, setOpen] = useState(false);
  return (
    <div
      className={`rounded-[10px] border-[0.5px] px-4 py-3 ${
        session.isDeload ? "border-warn/30 bg-warn-bg/30" : "border-border-default bg-canvas"
      }`}
    >
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        className="flex w-full items-center justify-between gap-3 text-left"
      >
        <div className="min-w-0">
          <span className="text-[14px] font-medium text-primary">{session.dayLabel}</span>
          <span className="caps-mono ml-2 text-[10px] tracking-[0.06em] text-tertiary">
            {formatDayDate(session.date)} · {session.locationName}
          </span>
        </div>
        <div className="flex shrink-0 items-center gap-2">
          {session.isDeload ? (
            <span className="caps-mono rounded-[3px] bg-warn-bg px-1.5 py-px text-[9px] tracking-[0.06em] text-warn">
              Deload
            </span>
          ) : null}
          <StatusPill status={session.status} />
          <span className="caps-mono text-[10px] tracking-[0.06em] text-tertiary">
            {open ? "−" : "+"}
          </span>
        </div>
      </button>

      {open ? (
        <div className="mt-3 space-y-2 border-t-[0.5px] border-border-subtle pt-3">
          {session.session.blocks.map((block) => (
            <div key={block.blockId}>
              <div className="flex items-center gap-2">
                <span className="caps-mono rounded-[3px] bg-canvas-muted px-1.5 py-px text-[9px] tracking-[0.06em] text-secondary">
                  {BLOCK_TYPE_LABEL[block.type]}
                </span>
                <span className="text-[12px] font-medium text-primary">{block.title}</span>
              </div>
              <ul className="mt-1 space-y-0.5">
                {block.prescriptions.map((p, i) => {
                  const ex = p.exercise;
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
                        <span className="text-[13px] text-primary">
                          {ex?.name ?? p.exerciseId}
                          <span className="ml-2 text-[12px] text-tertiary">
                            {formatPrescription(p)}
                          </span>
                        </span>
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

function StatusPill({ status }: { status: ScheduledWorkoutResponse["status"] }) {
  const map = {
    PLANNED: { label: "Planned", cls: "bg-canvas-muted text-tertiary" },
    COMPLETED: { label: "Done", cls: "bg-accent-bg text-accent-dim" },
    SKIPPED: { label: "Skipped", cls: "bg-alert-bg text-alert" },
  } as const;
  const m = map[status];
  return (
    <span className={`caps-mono rounded-[3px] px-1.5 py-px text-[9px] tracking-[0.06em] ${m.cls}`}>
      {m.label}
    </span>
  );
}
