"use client";

import { useState } from "react";
import type {
  WorkoutProgramDeepResponse,
  Phase,
  WorkoutDay,
  Block,
  Prescription,
  PrescriptionExercise,
} from "@/lib/types/workout-program";
import { WEEK_DAY_LABEL } from "@/lib/types/workout-program";
import { BLOCK_TYPE_LABEL } from "@/lib/types/exercise";
import {
  formatPrescription,
  formatDeloadModifier,
  formatLoggedSets,
} from "@/lib/workout-format";
import { ExerciseDetailSheet } from "./ExerciseDetailSheet";

function formatDate(iso: string): string {
  return new Date(`${iso}T00:00:00`)
    .toLocaleDateString("en-US", { month: "short", day: "numeric", year: "numeric" })
    .toUpperCase();
}

export function ProgramRoadmap({ program }: { program: WorkoutProgramDeepResponse }) {
  // The exercise detail sheet is shared across all prescription rows.
  const [sheetExercise, setSheetExercise] = useState<PrescriptionExercise | null>(null);

  return (
    <>
      <ol className="relative m-0 list-none space-y-0 p-0">
        {program.phases.map((phase, i) => (
          <PhaseRow
            key={phase.phaseId}
            phase={phase}
            isLast={i === program.phases.length - 1}
            onOpenExercise={setSheetExercise}
          />
        ))}
      </ol>
      <ExerciseDetailSheet exercise={sheetExercise} onClose={() => setSheetExercise(null)} />
    </>
  );
}

function PhaseRow({
  phase,
  isLast,
  onOpenExercise,
}: {
  phase: Phase;
  isLast: boolean;
  onOpenExercise: (e: PrescriptionExercise) => void;
}) {
  const locked = phase.status === "LOCKED";
  const active = phase.status === "ACTIVE";
  const completed = phase.status === "COMPLETED";
  const [expanded, setExpanded] = useState(!locked);

  const spineColor = completed || active ? "bg-accent" : "bg-border-default";
  const nodeClasses = completed
    ? "bg-accent border-accent"
    : active
      ? "bg-surface border-accent"
      : "bg-canvas-muted border-border-default";

  return (
    <li className="relative flex gap-4 pb-6">
      <div className="relative flex w-5 shrink-0 flex-col items-center">
        <span
          className={`z-10 mt-1 block rounded-full border-2 ${nodeClasses} ${
            active ? "h-4 w-4" : "h-3 w-3"
          }`}
          aria-hidden
        />
        {!isLast ? (
          <span className={`absolute top-5 bottom-[-24px] w-px ${spineColor}`} aria-hidden />
        ) : null}
      </div>

      <div
        className={`flex-1 rounded-[14px] border-[0.5px] border-border-default bg-surface px-5 py-4 ${
          locked ? "opacity-60" : ""
        }`}
      >
        <div className="flex flex-wrap items-start justify-between gap-2">
          <div className="min-w-0">
            <h3 className="m-0 text-[15px] font-medium tracking-[-0.01em] text-primary">
              {phase.title}
            </h3>
            {phase.focus ? (
              <p className="mt-0.5 text-[13px] text-secondary">{phase.focus}</p>
            ) : null}
            <div className="caps-mono mt-1 flex flex-wrap items-center gap-2 text-[10px] tracking-[0.06em] text-tertiary">
              <span>
                {phase.weeks} week{phase.weeks === 1 ? "" : "s"}
              </span>
              {phase.deloadWeekIndex != null ? (
                <span className="rounded-[3px] bg-warn-bg px-1.5 py-px text-warn">
                  Deload wk {phase.deloadWeekIndex}
                </span>
              ) : null}
              <span>
                {formatDate(phase.targetStartDate)} – {formatDate(phase.targetEndDate)}
              </span>
            </div>
          </div>
          <div className="flex items-center gap-2">
            <PhaseStatusPill status={phase.status} />
            <button
              type="button"
              onClick={() => setExpanded((v) => !v)}
              className="caps-mono cursor-pointer rounded-[3px] border-[0.5px] border-border-default px-1.5 py-px text-[9px] tracking-[0.06em] text-tertiary hover:text-secondary"
            >
              {expanded ? "Hide" : "Show"}
            </button>
          </div>
        </div>

        {expanded ? (
          <div className="mt-3 space-y-2 border-t-[0.5px] border-border-subtle pt-3">
            {phase.days.length === 0 ? (
              <p className="text-[12px] text-tertiary">No workout days yet.</p>
            ) : (
              phase.days.map((day) => (
                <DayRow key={day.dayId} day={day} onOpenExercise={onOpenExercise} />
              ))
            )}
          </div>
        ) : null}
      </div>
    </li>
  );
}

function PhaseStatusPill({ status }: { status: Phase["status"] }) {
  const map = {
    COMPLETED: { label: "Completed", cls: "bg-accent-bg text-accent-dim" },
    ACTIVE: { label: "Active", cls: "bg-good-bg text-accent-dim" },
    LOCKED: { label: "Locked", cls: "bg-canvas-muted text-tertiary" },
  } as const;
  const m = map[status];
  return (
    <span className={`caps-mono rounded-[3px] px-1.5 py-px text-[9px] tracking-[0.06em] ${m.cls}`}>
      {m.label}
    </span>
  );
}

function DayRow({
  day,
  onOpenExercise,
}: {
  day: WorkoutDay;
  onOpenExercise: (e: PrescriptionExercise) => void;
}) {
  const [open, setOpen] = useState(false);
  return (
    <div className="rounded-[10px] border-[0.5px] border-border-default bg-canvas px-4 py-3">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        className="flex w-full items-center justify-between gap-3 text-left"
      >
        <div className="min-w-0">
          <span className="text-[14px] font-medium text-primary">{day.label}</span>
          <span className="caps-mono ml-2 text-[10px] tracking-[0.06em] text-tertiary">
            {WEEK_DAY_LABEL[day.dayOfWeek]}
            {day.locationName ? ` · ${day.locationName}` : ""}
          </span>
        </div>
        <span className="caps-mono shrink-0 text-[10px] tracking-[0.06em] text-tertiary">
          {open ? "−" : "+"}
        </span>
      </button>

      {open ? (
        <div className="mt-3 space-y-3 border-t-[0.5px] border-border-subtle pt-3">
          {day.blocks.length === 0 ? (
            <p className="text-[12px] text-tertiary">No blocks.</p>
          ) : (
            day.blocks.map((block) => (
              <BlockRow key={block.blockId} block={block} onOpenExercise={onOpenExercise} />
            ))
          )}
        </div>
      ) : null}
    </div>
  );
}

function BlockRow({
  block,
  onOpenExercise,
}: {
  block: Block;
  onOpenExercise: (e: PrescriptionExercise) => void;
}) {
  return (
    <div>
      <div className="flex items-center gap-2">
        <span className="caps-mono rounded-[3px] bg-canvas-muted px-1.5 py-px text-[9px] tracking-[0.06em] text-secondary">
          {BLOCK_TYPE_LABEL[block.type]}
        </span>
        <span className="text-[12px] font-medium text-primary">{block.title}</span>
      </div>
      <ul className="mt-1.5 space-y-1">
        {block.prescriptions.map((p, i) => (
          <PrescriptionRow key={i} prescription={p} onOpenExercise={onOpenExercise} />
        ))}
      </ul>
    </div>
  );
}

function PrescriptionRow({
  prescription,
  onOpenExercise,
}: {
  prescription: Prescription;
  onOpenExercise: (e: PrescriptionExercise) => void;
}) {
  const ex = prescription.exercise;
  const name = ex?.name ?? prescription.exerciseId;
  // Performed sessions carry logged weights; prefer them over the bare set count.
  const dosage = formatLoggedSets(prescription) ?? formatPrescription(prescription);
  const deload = formatDeloadModifier(prescription);
  const flagged = !!prescription.validationError;

  return (
    <li>
      <button
        type="button"
        disabled={!ex}
        onClick={() => ex && onOpenExercise(ex)}
        className={`flex w-full items-start justify-between gap-3 rounded-md px-2 py-1.5 text-left ${
          flagged
            ? "border-[0.5px] border-alert/50 bg-alert-bg"
            : ex
              ? "cursor-pointer hover:bg-canvas-muted"
              : ""
        }`}
      >
        <div className="min-w-0 flex-1">
          <span className="text-[13px] text-primary">{name}</span>
          {dosage ? (
            <span className="ml-2 text-[12px] text-tertiary">{dosage}</span>
          ) : null}
          {prescription.notes && prescription.notes !== name ? (
            <p className="mt-0.5 text-[11px] italic text-tertiary">{prescription.notes}</p>
          ) : null}
          {deload ? (
            <p className="caps-mono mt-0.5 text-[9px] tracking-[0.06em] text-warn">{deload}</p>
          ) : null}
          {flagged ? (
            <p className="mt-0.5 font-mono text-[10px] text-alert">
              {prescription.validationError}
            </p>
          ) : null}
        </div>
        {ex ? <span className="shrink-0 text-[11px] text-tertiary">View →</span> : null}
      </button>
    </li>
  );
}
