"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import type {
  ScheduledWorkoutResponse,
  PrescriptionExercise,
  CompleteSessionRequest,
} from "@/lib/types/workout-program";
import { ExerciseDetailSheet } from "./ExerciseDetailSheet";
import { LogSessionModal } from "./LogSessionModal";
import { BLOCK_TYPE_LABEL } from "@/lib/types/exercise";
import { formatPrescription } from "@/lib/workout-format";

function formatDayDate(iso: string): string {
  return new Date(`${iso}T00:00:00`).toLocaleDateString("en-US", {
    weekday: "short",
    month: "short",
    day: "numeric",
  });
}

export function ProgramThisWeek({
  sessions,
  logSession,
}: {
  sessions: ScheduledWorkoutResponse[];
  // Server action: completion upsert for one of this program's sessions
  // (ADR-0012 D6 — log today's result / edit actuals, no live logger).
  logSession: (
    scheduledId: string,
    input: CompleteSessionRequest,
  ) => Promise<void>;
}) {
  const router = useRouter();
  const [sheetExercise, setSheetExercise] = useState<PrescriptionExercise | null>(null);
  const [logTarget, setLogTarget] = useState<ScheduledWorkoutResponse | null>(null);

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
          <SessionRow
            key={s.scheduledId}
            session={s}
            onOpenExercise={setSheetExercise}
            onLog={setLogTarget}
          />
        ))}
      </div>
      <ExerciseDetailSheet exercise={sheetExercise} onClose={() => setSheetExercise(null)} />
      <LogSessionModal
        session={logTarget}
        onClose={() => setLogTarget(null)}
        onSaved={() => {
          setLogTarget(null);
          router.refresh();
        }}
        save={(input) => logSession(logTarget!.scheduledId, input)}
      />
    </>
  );
}

function SessionRow({
  session,
  onOpenExercise,
  onLog,
}: {
  session: ScheduledWorkoutResponse;
  onOpenExercise: (e: PrescriptionExercise) => void;
  onLog: (s: ScheduledWorkoutResponse) => void;
}) {
  const [open, setOpen] = useState(false);
  return (
    <div
      className={`rounded-[10px] border-[0.5px] px-4 py-3 ${
        session.isDeload ? "border-warn/30 bg-warn-bg/30" : "border-border-default bg-canvas"
      }`}
    >
      <div className="flex items-center gap-2">
        <button
          type="button"
          onClick={() => setOpen((v) => !v)}
          className="flex min-w-0 flex-1 items-center justify-between gap-3 text-left"
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
        <button
          type="button"
          onClick={() => onLog(session)}
          className="caps-mono shrink-0 cursor-pointer rounded-md border-[0.5px] border-border-default bg-canvas px-2.5 py-1 text-[10px] tracking-[0.06em] text-secondary hover:text-primary"
        >
          {session.status === "PLANNED" ? "Log result" : "Edit result"}
        </button>
      </div>

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
