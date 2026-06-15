"use client";

import { useEffect, useMemo, useState, useTransition } from "react";
import type {
  WorkoutProgramChatSchedule,
  IntensityKind,
  WeekDay,
  Intensity,
  NutritionGuidance,
} from "@/lib/types/workout-program";
import { WEEK_DAY_LABEL, WEEK_DAYS } from "@/lib/types/workout-program";
import type { BlockType, ExerciseResponse } from "@/lib/types/exercise";
import { BLOCK_TYPES, BLOCK_TYPE_LABEL } from "@/lib/types/exercise";
import {
  newBlock,
  newDay,
  newPhase,
  newPrescription,
  type BlockDraft,
  type DayDraft,
  type PhaseDraft,
  type PrescriptionDraft,
  type ProgramProposalDraft,
} from "@/lib/workout-program-chat";
import { useToast } from "@/components/ui/Toast";

// A gym option (locationId + display name) constrained to the thread schedule.
export type GymOption = { locationId: string; name: string };

// Loads executable exercises for a gym (server action passed from the page).
export type LoadExercisesAction = (
  locationId: string,
) => Promise<ExerciseResponse[]>;

type Props = {
  initialValue: ProgramProposalDraft;
  // The thread's fixed schedule — gym pickers are constrained to these gyms.
  schedule: WorkoutProgramChatSchedule | null;
  // Display names + the constrained gym set for day pickers.
  gyms: GymOption[];
  loadExercises: LoadExercisesAction;
  // Commit the edited proposal. Resolves on success; throws on validation
  // failure so this card can keep showing the (re-flagged) issues.
  onSave: (draft: ProgramProposalDraft) => Promise<void>;
  onDiscard?: () => void;
  heading?: string;
  saveLabel?: string;
  // IMPL-18b: editing an already-active program in place. Relabels the commit
  // action and notes that completed sessions are preserved.
  editMode?: boolean;
};

// ── Small primitives ─────────────────────────────────────────────────

function FieldLabel({ children }: { children: React.ReactNode }) {
  return (
    <span className="caps-mono mb-1 block text-[9px] tracking-[0.06em] text-tertiary">
      {children}
    </span>
  );
}

function TextInput({
  value,
  onChange,
  placeholder,
  className = "",
}: {
  value: string;
  onChange: (v: string) => void;
  placeholder?: string;
  className?: string;
}) {
  return (
    <input
      value={value}
      onChange={(e) => onChange(e.target.value)}
      placeholder={placeholder}
      className={`w-full rounded-md border-[0.5px] border-border-default bg-surface px-2.5 py-1.5 text-[13px] text-primary outline-none focus:border-accent ${className}`}
    />
  );
}

function NumberInput({
  value,
  onChange,
  placeholder,
  className = "",
}: {
  value: number | null;
  onChange: (v: number | null) => void;
  placeholder?: string;
  className?: string;
}) {
  return (
    <input
      type="number"
      value={value ?? ""}
      onChange={(e) =>
        onChange(e.target.value === "" ? null : Number(e.target.value))
      }
      placeholder={placeholder}
      className={`w-full rounded-md border-[0.5px] border-border-default bg-surface px-2 py-1 text-[12px] text-primary outline-none focus:border-accent ${className}`}
    />
  );
}

function MoveButtons({
  onUp,
  onDown,
  onRemove,
  canUp,
  canDown,
  removeLabel,
}: {
  onUp: () => void;
  onDown: () => void;
  onRemove: () => void;
  canUp: boolean;
  canDown: boolean;
  removeLabel: string;
}) {
  return (
    <div className="flex shrink-0 items-center gap-1">
      <button
        type="button"
        onClick={onUp}
        disabled={!canUp}
        aria-label="Move up"
        className="cursor-pointer rounded px-1.5 py-0.5 text-[11px] text-tertiary hover:bg-canvas-muted hover:text-primary disabled:cursor-default disabled:opacity-30"
      >
        ↑
      </button>
      <button
        type="button"
        onClick={onDown}
        disabled={!canDown}
        aria-label="Move down"
        className="cursor-pointer rounded px-1.5 py-0.5 text-[11px] text-tertiary hover:bg-canvas-muted hover:text-primary disabled:cursor-default disabled:opacity-30"
      >
        ↓
      </button>
      <button
        type="button"
        onClick={onRemove}
        aria-label={removeLabel}
        className="cursor-pointer rounded px-1.5 py-0.5 text-[11px] text-tertiary hover:bg-alert-bg hover:text-alert"
      >
        ✕
      </button>
    </div>
  );
}

// Immutable list helpers.
function moveItem<T>(arr: T[], i: number, dir: -1 | 1): T[] {
  const j = i + dir;
  if (j < 0 || j >= arr.length) return arr;
  const next = arr.slice();
  const a = next[i];
  const b = next[j];
  if (a === undefined || b === undefined) return arr;
  next[i] = b;
  next[j] = a;
  return next;
}

const INTENSITY_KINDS: IntensityKind[] = ["NONE", "RPE", "PERCENT_1RM"];
const INTENSITY_LABEL: Record<IntensityKind, string> = {
  NONE: "None",
  RPE: "RPE",
  PERCENT_1RM: "% 1RM",
};

// Compact human-readable load summary for a prescription's collapsed row.
// Prefers the concrete prescribed weight (IMPL-18 S5); falls back to the
// intensity (RPE / %1RM) when no weight is grounded; null when nothing to show.
function formatLoad(
  targetWeightLbs: number | null,
  intensity: Intensity,
): string | null {
  if (targetWeightLbs != null) {
    const w = Number.isInteger(targetWeightLbs)
      ? `${targetWeightLbs}`
      : targetWeightLbs.toFixed(1);
    return `${w} lb`;
  }
  if (intensity.kind === "RPE" && intensity.value != null) {
    return `RPE ${intensity.value}`;
  }
  if (intensity.kind === "PERCENT_1RM" && intensity.value != null) {
    return `${intensity.value}% 1RM`;
  }
  return null;
}

// Compact rep/set summary, e.g. "3×8–10" or "4×5" or "3 sets".
function formatSetsReps(
  sets: number | null,
  repsMin: number | null,
  repsMax: number | null,
): string | null {
  const reps =
    repsMin != null && repsMax != null
      ? repsMin === repsMax
        ? `${repsMin}`
        : `${repsMin}–${repsMax}`
      : repsMin != null
        ? `${repsMin}`
        : repsMax != null
          ? `${repsMax}`
          : null;
  if (sets != null && reps) return `${sets}×${reps}`;
  if (sets != null) return `${sets} set${sets === 1 ? "" : "s"}`;
  if (reps) return `${reps} reps`;
  return null;
}

// A quiet, display-only nutrition strip rendered under a phase header. Uses the
// phase guidance, falling back to the program-level guidance (IMPL-18 R4).
function NutritionStrip({
  guidance,
}: {
  guidance: NutritionGuidance | null;
}) {
  if (!guidance) return null;
  const macros: { label: string; value: number | null }[] = [
    { label: "kcal", value: guidance.kcal },
    { label: "P", value: guidance.proteinG },
    { label: "C", value: guidance.carbsG },
    { label: "F", value: guidance.fatG },
  ];
  const shown = macros.filter((m) => m.value != null);
  if (shown.length === 0 && !guidance.note) return null;
  return (
    <div className="mt-2 rounded-md bg-canvas-muted px-3 py-2">
      <div className="flex flex-wrap items-baseline gap-x-4 gap-y-1">
        <span className="caps-mono text-[8px] tracking-[0.06em] text-tertiary">
          Nutrition
        </span>
        {shown.map((m) => (
          <span key={m.label} className="flex items-baseline gap-1">
            <span className="caps-mono text-[8px] tracking-[0.06em] text-tertiary">
              {m.label}
            </span>
            <span className="text-[12px] tabular-nums text-secondary">
              {m.value}
              {m.label === "kcal" ? "" : "g"}
            </span>
          </span>
        ))}
      </div>
      {guidance.note ? (
        <p className="mt-1 text-[11px] leading-[1.4] text-tertiary">
          {guidance.note}
        </p>
      ) : null}
    </div>
  );
}

export function WorkoutProgramProposalCard({
  initialValue,
  schedule,
  gyms,
  loadExercises,
  onSave,
  onDiscard,
  heading = "Proposed program",
  saveLabel,
  editMode = false,
}: Props) {
  const resolvedSaveLabel =
    saveLabel ?? (editMode ? "Update program" : "Save program");
  const toast = useToast();
  const [draft, setDraft] = useState<ProgramProposalDraft>(initialValue);
  const [pending, startTransition] = useTransition();

  // Re-flag with new issues / fresh proposal when the host swaps initialValue.
  useEffect(() => {
    setDraft(initialValue);
  }, [initialValue]);

  const issues = draft.issues;
  const warnings = draft.warnings;

  // ── Phase ops ──────────────────────────────────────────────────────
  function patchPhase(pi: number, patch: Partial<PhaseDraft>) {
    setDraft((d) => ({
      ...d,
      phases: d.phases.map((p, i) => (i === pi ? { ...p, ...patch } : p)),
    }));
  }
  function addPhase() {
    setDraft((d) => ({ ...d, phases: [...d.phases, newPhase()] }));
  }
  function removePhase(pi: number) {
    setDraft((d) => ({ ...d, phases: d.phases.filter((_, i) => i !== pi) }));
  }
  function movePhase(pi: number, dir: -1 | 1) {
    setDraft((d) => ({ ...d, phases: moveItem(d.phases, pi, dir) }));
  }

  // ── Day ops ────────────────────────────────────────────────────────
  function patchDay(pi: number, di: number, patch: Partial<DayDraft>) {
    setDraft((d) => ({
      ...d,
      phases: d.phases.map((p, i) =>
        i === pi
          ? {
              ...p,
              days: p.days.map((day, j) =>
                j === di ? { ...day, ...patch } : day,
              ),
            }
          : p,
      ),
    }));
  }
  function addDay(pi: number) {
    const firstGym = gyms[0];
    const days = (schedule?.trainingDays ?? WEEK_DAYS) as WeekDay[];
    const dow = days[0] ?? "MON";
    setDraft((d) => ({
      ...d,
      phases: d.phases.map((p, i) =>
        i === pi
          ? {
              ...p,
              days: [
                ...p.days,
                newDay(
                  dow,
                  firstGym?.locationId ?? "",
                  firstGym?.name ?? "",
                ),
              ],
            }
          : p,
      ),
    }));
  }
  function removeDay(pi: number, di: number) {
    setDraft((d) => ({
      ...d,
      phases: d.phases.map((p, i) =>
        i === pi ? { ...p, days: p.days.filter((_, j) => j !== di) } : p,
      ),
    }));
  }
  function moveDay(pi: number, di: number, dir: -1 | 1) {
    setDraft((d) => ({
      ...d,
      phases: d.phases.map((p, i) =>
        i === pi ? { ...p, days: moveItem(p.days, di, dir) } : p,
      ),
    }));
  }

  // ── Block ops ──────────────────────────────────────────────────────
  function patchBlock(
    pi: number,
    di: number,
    bi: number,
    patch: Partial<BlockDraft>,
  ) {
    setDraft((d) => ({
      ...d,
      phases: d.phases.map((p, i) =>
        i === pi
          ? {
              ...p,
              days: p.days.map((day, j) =>
                j === di
                  ? {
                      ...day,
                      blocks: day.blocks.map((b, k) =>
                        k === bi ? { ...b, ...patch } : b,
                      ),
                    }
                  : day,
              ),
            }
          : p,
      ),
    }));
  }
  function addBlock(pi: number, di: number) {
    setDraft((d) => ({
      ...d,
      phases: d.phases.map((p, i) =>
        i === pi
          ? {
              ...p,
              days: p.days.map((day, j) =>
                j === di ? { ...day, blocks: [...day.blocks, newBlock()] } : day,
              ),
            }
          : p,
      ),
    }));
  }
  function removeBlock(pi: number, di: number, bi: number) {
    setDraft((d) => ({
      ...d,
      phases: d.phases.map((p, i) =>
        i === pi
          ? {
              ...p,
              days: p.days.map((day, j) =>
                j === di
                  ? { ...day, blocks: day.blocks.filter((_, k) => k !== bi) }
                  : day,
              ),
            }
          : p,
      ),
    }));
  }
  function moveBlock(pi: number, di: number, bi: number, dir: -1 | 1) {
    setDraft((d) => ({
      ...d,
      phases: d.phases.map((p, i) =>
        i === pi
          ? {
              ...p,
              days: p.days.map((day, j) =>
                j === di
                  ? { ...day, blocks: moveItem(day.blocks, bi, dir) }
                  : day,
              ),
            }
          : p,
      ),
    }));
  }

  // ── Prescription ops ───────────────────────────────────────────────
  function patchRx(
    pi: number,
    di: number,
    bi: number,
    ri: number,
    patch: Partial<PrescriptionDraft>,
  ) {
    setDraft((d) => ({
      ...d,
      phases: d.phases.map((p, i) =>
        i === pi
          ? {
              ...p,
              days: p.days.map((day, j) =>
                j === di
                  ? {
                      ...day,
                      blocks: day.blocks.map((b, k) =>
                        k === bi
                          ? {
                              ...b,
                              prescriptions: b.prescriptions.map((rx, l) =>
                                l === ri ? { ...rx, ...patch } : rx,
                              ),
                            }
                          : b,
                      ),
                    }
                  : day,
              ),
            }
          : p,
      ),
    }));
  }
  function addRx(pi: number, di: number, bi: number) {
    setDraft((d) => ({
      ...d,
      phases: d.phases.map((p, i) =>
        i === pi
          ? {
              ...p,
              days: p.days.map((day, j) =>
                j === di
                  ? {
                      ...day,
                      blocks: day.blocks.map((b, k) =>
                        k === bi
                          ? {
                              ...b,
                              prescriptions: [
                                ...b.prescriptions,
                                newPrescription(),
                              ],
                            }
                          : b,
                      ),
                    }
                  : day,
              ),
            }
          : p,
      ),
    }));
  }
  function removeRx(pi: number, di: number, bi: number, ri: number) {
    setDraft((d) => ({
      ...d,
      phases: d.phases.map((p, i) =>
        i === pi
          ? {
              ...p,
              days: p.days.map((day, j) =>
                j === di
                  ? {
                      ...day,
                      blocks: day.blocks.map((b, k) =>
                        k === bi
                          ? {
                              ...b,
                              prescriptions: b.prescriptions.filter(
                                (_, l) => l !== ri,
                              ),
                            }
                          : b,
                      ),
                    }
                  : day,
              ),
            }
          : p,
      ),
    }));
  }
  function moveRx(pi: number, di: number, bi: number, ri: number, dir: -1 | 1) {
    setDraft((d) => ({
      ...d,
      phases: d.phases.map((p, i) =>
        i === pi
          ? {
              ...p,
              days: p.days.map((day, j) =>
                j === di
                  ? {
                      ...day,
                      blocks: day.blocks.map((b, k) =>
                        k === bi
                          ? {
                              ...b,
                              prescriptions: moveItem(
                                b.prescriptions,
                                ri,
                                dir,
                              ),
                            }
                          : b,
                      ),
                    }
                  : day,
              ),
            }
          : p,
      ),
    }));
  }

  function handleSave() {
    if (!(draft.title ?? "").trim()) {
      toast.error("Can't save yet", { description: "The program needs a title." });
      return;
    }
    startTransition(async () => {
      try {
        await onSave(draft);
      } catch {
        // Host surfaces the toast + re-flags issues via initialValue.
      }
    });
  }

  return (
    <div className="rounded-[14px] border-[0.5px] border-border-default bg-surface">
      <div className="flex items-center justify-between border-b-[0.5px] border-border-subtle px-5 py-3">
        <h2 className="m-0 text-[14px] font-medium text-primary">{heading}</h2>
        <span className="caps-mono text-[9px] tracking-[0.06em] text-tertiary">
          {draft.phases.length} phase{draft.phases.length === 1 ? "" : "s"}
        </span>
      </div>

      <div className="space-y-4 px-5 py-5">
        {issues.length > 0 ? (
          <div className="rounded-md bg-alert-bg px-3 py-2">
            <p className="caps-mono text-[9px] tracking-[0.06em] text-alert">
              {issues.length} issue{issues.length === 1 ? "" : "s"} to resolve
            </p>
            <ul className="mt-1 list-disc space-y-0.5 pl-4 font-mono text-[11px] text-alert">
              {issues.map((issue, i) => (
                <li key={i}>{issue}</li>
              ))}
            </ul>
          </div>
        ) : null}

        {warnings.length > 0 ? (
          <div className="rounded-md bg-warn-bg px-3 py-2">
            <p className="caps-mono text-[9px] tracking-[0.06em] text-warn">
              {warnings.length} advisory{warnings.length === 1 ? "" : " notes"} — you can save anyway
            </p>
            <ul className="mt-1 list-disc space-y-0.5 pl-4 font-mono text-[11px] text-warn">
              {warnings.map((w, i) => (
                <li key={i}>{w}</li>
              ))}
            </ul>
          </div>
        ) : null}

        <label className="block">
          <FieldLabel>Title</FieldLabel>
          <TextInput
            value={draft.title}
            onChange={(v) => setDraft((d) => ({ ...d, title: v }))}
          />
        </label>
        <label className="block">
          <FieldLabel>Description</FieldLabel>
          <textarea
            value={draft.description}
            onChange={(e) =>
              setDraft((d) => ({ ...d, description: e.target.value }))
            }
            rows={2}
            className="w-full rounded-md border-[0.5px] border-border-default bg-surface px-2.5 py-1.5 text-[13px] text-primary outline-none focus:border-accent"
          />
        </label>

        {/* Phases → days → blocks → prescriptions */}
        <div className="space-y-3">
          {draft.phases.map((phase, pi) => (
            <PhaseEditor
              key={phase.key}
              phase={phase}
              programNutrition={draft.nutritionGuidance}
              gyms={gyms}
              schedule={schedule}
              loadExercises={loadExercises}
              canUp={pi > 0}
              canDown={pi < draft.phases.length - 1}
              onPatch={(patch) => patchPhase(pi, patch)}
              onRemove={() => removePhase(pi)}
              onMove={(dir) => movePhase(pi, dir)}
              onAddDay={() => addDay(pi)}
              onPatchDay={(di, patch) => patchDay(pi, di, patch)}
              onRemoveDay={(di) => removeDay(pi, di)}
              onMoveDay={(di, dir) => moveDay(pi, di, dir)}
              onAddBlock={(di) => addBlock(pi, di)}
              onPatchBlock={(di, bi, patch) => patchBlock(pi, di, bi, patch)}
              onRemoveBlock={(di, bi) => removeBlock(pi, di, bi)}
              onMoveBlock={(di, bi, dir) => moveBlock(pi, di, bi, dir)}
              onAddRx={(di, bi) => addRx(pi, di, bi)}
              onPatchRx={(di, bi, ri, patch) => patchRx(pi, di, bi, ri, patch)}
              onRemoveRx={(di, bi, ri) => removeRx(pi, di, bi, ri)}
              onMoveRx={(di, bi, ri, dir) => moveRx(pi, di, bi, ri, dir)}
            />
          ))}
          <button
            type="button"
            onClick={addPhase}
            className="caps-mono w-full cursor-pointer rounded-md border-[0.5px] border-dashed border-border-default bg-canvas px-3 py-2 text-[10px] tracking-[0.06em] text-secondary hover:border-accent hover:text-primary"
          >
            + Add phase
          </button>
        </div>
      </div>

      <div className="flex items-center justify-between gap-2 border-t-[0.5px] border-border-subtle px-5 py-3">
        <p className="m-0 text-[11px] leading-[1.4] text-tertiary">
          {editMode
            ? "Updates apply from today forward — already-completed sessions are preserved."
            : ""}
        </p>
        <div className="flex shrink-0 items-center gap-2">
          <button
            type="button"
            onClick={onDiscard}
            disabled={pending}
            className="cursor-pointer rounded-md border-[0.5px] border-border-default bg-canvas px-3 py-1.5 text-[12px] font-medium text-primary disabled:opacity-60"
          >
            Discard
          </button>
          <button
            type="button"
            onClick={handleSave}
            disabled={pending}
            className="cursor-pointer rounded-md bg-accent px-3 py-1.5 text-[12px] font-medium text-inverse disabled:opacity-60"
          >
            {pending ? "Saving…" : resolvedSaveLabel}
          </button>
        </div>
      </div>
    </div>
  );
}

// ── Phase editor ─────────────────────────────────────────────────────

function PhaseEditor({
  phase,
  programNutrition,
  gyms,
  schedule,
  loadExercises,
  canUp,
  canDown,
  onPatch,
  onRemove,
  onMove,
  onAddDay,
  onPatchDay,
  onRemoveDay,
  onMoveDay,
  onAddBlock,
  onPatchBlock,
  onRemoveBlock,
  onMoveBlock,
  onAddRx,
  onPatchRx,
  onRemoveRx,
  onMoveRx,
}: {
  phase: PhaseDraft;
  // Program-level fallback used when the phase has no nutrition guidance.
  programNutrition: NutritionGuidance | null;
  gyms: GymOption[];
  schedule: WorkoutProgramChatSchedule | null;
  loadExercises: LoadExercisesAction;
  canUp: boolean;
  canDown: boolean;
  onPatch: (patch: Partial<PhaseDraft>) => void;
  onRemove: () => void;
  onMove: (dir: -1 | 1) => void;
  onAddDay: () => void;
  onPatchDay: (di: number, patch: Partial<DayDraft>) => void;
  onRemoveDay: (di: number) => void;
  onMoveDay: (di: number, dir: -1 | 1) => void;
  onAddBlock: (di: number) => void;
  onPatchBlock: (di: number, bi: number, patch: Partial<BlockDraft>) => void;
  onRemoveBlock: (di: number, bi: number) => void;
  onMoveBlock: (di: number, bi: number, dir: -1 | 1) => void;
  onAddRx: (di: number, bi: number) => void;
  onPatchRx: (
    di: number,
    bi: number,
    ri: number,
    patch: Partial<PrescriptionDraft>,
  ) => void;
  onRemoveRx: (di: number, bi: number, ri: number) => void;
  onMoveRx: (di: number, bi: number, ri: number, dir: -1 | 1) => void;
}) {
  return (
    <div className="rounded-[10px] border-[0.5px] border-border-default bg-canvas px-4 py-3">
      <div className="flex items-start gap-2">
        <div className="min-w-0 flex-1 space-y-2">
          <TextInput
            value={phase.title}
            onChange={(v) => onPatch({ title: v })}
            placeholder="Phase title"
          />
          <TextInput
            value={phase.focus}
            onChange={(v) => onPatch({ focus: v })}
            placeholder="Focus (e.g. accumulation)"
          />
          <div className="flex items-center gap-3">
            <label className="flex items-center gap-1.5">
              <span className="caps-mono text-[9px] tracking-[0.06em] text-tertiary">
                Weeks
              </span>
              <div className="w-16">
                <NumberInput
                  value={phase.weeks}
                  onChange={(v) => onPatch({ weeks: v ?? 0 })}
                />
              </div>
            </label>
            <label className="flex items-center gap-1.5">
              <span className="caps-mono text-[9px] tracking-[0.06em] text-tertiary">
                Deload wk
              </span>
              <div className="w-16">
                <NumberInput
                  value={phase.deloadWeekIndex}
                  onChange={(v) => onPatch({ deloadWeekIndex: v })}
                  placeholder="—"
                />
              </div>
            </label>
          </div>
        </div>
        <MoveButtons
          onUp={() => onMove(-1)}
          onDown={() => onMove(1)}
          onRemove={onRemove}
          canUp={canUp}
          canDown={canDown}
          removeLabel="Remove phase"
        />
      </div>

      <NutritionStrip guidance={phase.nutritionGuidance ?? programNutrition} />

      <div className="mt-3 space-y-3">
        {phase.days.map((day, di) => (
          <DayEditor
            key={day.key}
            day={day}
            gyms={gyms}
            schedule={schedule}
            loadExercises={loadExercises}
            canUp={di > 0}
            canDown={di < phase.days.length - 1}
            onPatch={(patch) => onPatchDay(di, patch)}
            onRemove={() => onRemoveDay(di)}
            onMove={(dir) => onMoveDay(di, dir)}
            onAddBlock={() => onAddBlock(di)}
            onPatchBlock={(bi, patch) => onPatchBlock(di, bi, patch)}
            onRemoveBlock={(bi) => onRemoveBlock(di, bi)}
            onMoveBlock={(bi, dir) => onMoveBlock(di, bi, dir)}
            onAddRx={(bi) => onAddRx(di, bi)}
            onPatchRx={(bi, ri, patch) => onPatchRx(di, bi, ri, patch)}
            onRemoveRx={(bi, ri) => onRemoveRx(di, bi, ri)}
            onMoveRx={(bi, ri, dir) => onMoveRx(di, bi, ri, dir)}
          />
        ))}
        <button
          type="button"
          onClick={onAddDay}
          className="caps-mono w-full cursor-pointer rounded-md border-[0.5px] border-dashed border-border-subtle bg-surface px-3 py-1.5 text-[9px] tracking-[0.06em] text-tertiary hover:border-accent hover:text-primary"
        >
          + Add workout day
        </button>
      </div>
    </div>
  );
}

// ── Day editor ───────────────────────────────────────────────────────

function DayEditor({
  day,
  gyms,
  schedule,
  loadExercises,
  canUp,
  canDown,
  onPatch,
  onRemove,
  onMove,
  onAddBlock,
  onPatchBlock,
  onRemoveBlock,
  onMoveBlock,
  onAddRx,
  onPatchRx,
  onRemoveRx,
  onMoveRx,
}: {
  day: DayDraft;
  gyms: GymOption[];
  schedule: WorkoutProgramChatSchedule | null;
  loadExercises: LoadExercisesAction;
  canUp: boolean;
  canDown: boolean;
  onPatch: (patch: Partial<DayDraft>) => void;
  onRemove: () => void;
  onMove: (dir: -1 | 1) => void;
  onAddBlock: () => void;
  onPatchBlock: (bi: number, patch: Partial<BlockDraft>) => void;
  onRemoveBlock: (bi: number) => void;
  onMoveBlock: (bi: number, dir: -1 | 1) => void;
  onAddRx: (bi: number) => void;
  onPatchRx: (
    bi: number,
    ri: number,
    patch: Partial<PrescriptionDraft>,
  ) => void;
  onRemoveRx: (bi: number, ri: number) => void;
  onMoveRx: (bi: number, ri: number, dir: -1 | 1) => void;
}) {
  // Days are restricted to the schedule's training days when one exists.
  const dayOptions = (schedule?.trainingDays?.length
    ? schedule.trainingDays
    : WEEK_DAYS) as WeekDay[];

  function changeGym(locationId: string) {
    const g = gyms.find((x) => x.locationId === locationId);
    // Changing the gym re-validates the day: prescriptions for that gym may no
    // longer be executable, so clear the old inline flags optimistically and
    // let the next commit re-flag.
    onPatch({ locationId, locationName: g?.name ?? "" });
  }

  return (
    <div className="overflow-hidden rounded-lg border border-border-default bg-surface">
      {/* Day header — a tinted bar with a weekday chip so each day reads as its
          own bounded section, distinct from the exercise blocks nested below. */}
      <div className="flex items-start gap-2 border-b-[0.5px] border-border-subtle bg-canvas-muted px-3 py-2.5">
        <div className="min-w-0 flex-1 space-y-1.5">
          <div className="flex items-center gap-2">
            <span className="caps-mono shrink-0 rounded bg-accent-bg px-1.5 py-1 text-[9px] font-medium tracking-[0.06em] text-accent-dim">
              {WEEK_DAY_LABEL[day.dayOfWeek]}
            </span>
            <div className="min-w-0 flex-1">
              <TextInput
                value={day.label}
                onChange={(v) => onPatch({ label: v })}
                placeholder="Day label (e.g. Upper A)"
              />
            </div>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <select
              value={day.dayOfWeek}
              onChange={(e) => onPatch({ dayOfWeek: e.target.value as WeekDay })}
              className="cursor-pointer rounded-md border-[0.5px] border-border-default bg-surface px-2 py-1 text-[12px] text-primary outline-none focus:border-accent"
            >
              {dayOptions.map((dow) => (
                <option key={dow} value={dow}>
                  {WEEK_DAY_LABEL[dow]}
                </option>
              ))}
            </select>
            <select
              value={day.locationId}
              onChange={(e) => changeGym(e.target.value)}
              className="min-w-0 max-w-[180px] cursor-pointer truncate rounded-md border-[0.5px] border-border-default bg-surface px-2 py-1 text-[12px] text-primary outline-none focus:border-accent"
            >
              {gyms.length === 0 ? (
                <option value="">No gyms</option>
              ) : null}
              {gyms.map((g) => (
                <option key={g.locationId} value={g.locationId}>
                  {g.name}
                </option>
              ))}
            </select>
          </div>
        </div>
        <MoveButtons
          onUp={() => onMove(-1)}
          onDown={() => onMove(1)}
          onRemove={onRemove}
          canUp={canUp}
          canDown={canDown}
          removeLabel="Remove day"
        />
      </div>

      {/* Day body — exercise blocks, clearly nested inside the day card. */}
      <div className="space-y-2.5 px-3 py-3">
        {day.blocks.map((block, bi) => (
          <BlockEditor
            key={block.key}
            block={block}
            locationId={day.locationId}
            loadExercises={loadExercises}
            canUp={bi > 0}
            canDown={bi < day.blocks.length - 1}
            onPatch={(patch) => onPatchBlock(bi, patch)}
            onRemove={() => onRemoveBlock(bi)}
            onMove={(dir) => onMoveBlock(bi, dir)}
            onAddRx={() => onAddRx(bi)}
            onPatchRx={(ri, patch) => onPatchRx(bi, ri, patch)}
            onRemoveRx={(ri) => onRemoveRx(bi, ri)}
            onMoveRx={(ri, dir) => onMoveRx(bi, ri, dir)}
          />
        ))}
        <button
          type="button"
          onClick={onAddBlock}
          className="caps-mono w-full cursor-pointer rounded border-[0.5px] border-dashed border-border-subtle px-2 py-1 text-[9px] tracking-[0.06em] text-tertiary hover:border-accent hover:text-primary"
        >
          + Add block
        </button>
      </div>
    </div>
  );
}

// ── Block editor ─────────────────────────────────────────────────────

function BlockEditor({
  block,
  locationId,
  loadExercises,
  canUp,
  canDown,
  onPatch,
  onRemove,
  onMove,
  onAddRx,
  onPatchRx,
  onRemoveRx,
  onMoveRx,
}: {
  block: BlockDraft;
  locationId: string;
  loadExercises: LoadExercisesAction;
  canUp: boolean;
  canDown: boolean;
  onPatch: (patch: Partial<BlockDraft>) => void;
  onRemove: () => void;
  onMove: (dir: -1 | 1) => void;
  onAddRx: () => void;
  onPatchRx: (ri: number, patch: Partial<PrescriptionDraft>) => void;
  onRemoveRx: (ri: number) => void;
  onMoveRx: (ri: number, dir: -1 | 1) => void;
}) {
  return (
    <div className="border-l-2 border-border-default pl-3">
      <div className="flex items-center gap-2">
        <select
          value={block.type}
          onChange={(e) => onPatch({ type: e.target.value as BlockType })}
          className="caps-mono cursor-pointer rounded-[3px] border-[0.5px] border-border-default bg-surface px-1.5 py-0.5 text-[9px] tracking-[0.06em] text-secondary outline-none focus:border-accent"
        >
          {BLOCK_TYPES.map((t) => (
            <option key={t} value={t}>
              {BLOCK_TYPE_LABEL[t]}
            </option>
          ))}
        </select>
        <input
          value={block.title}
          onChange={(e) => onPatch({ title: e.target.value })}
          placeholder="Block title"
          className="min-w-0 flex-1 rounded border-[0.5px] border-transparent bg-transparent px-1 py-0.5 text-[12px] font-medium text-primary outline-none focus:border-accent"
        />
        <MoveButtons
          onUp={() => onMove(-1)}
          onDown={() => onMove(1)}
          onRemove={onRemove}
          canUp={canUp}
          canDown={canDown}
          removeLabel="Remove block"
        />
      </div>

      <div className="mt-1.5 space-y-1.5">
        {block.prescriptions.map((rx, ri) => (
          <PrescriptionEditor
            key={rx.key}
            rx={rx}
            locationId={locationId}
            loadExercises={loadExercises}
            canUp={ri > 0}
            canDown={ri < block.prescriptions.length - 1}
            onPatch={(patch) => onPatchRx(ri, patch)}
            onRemove={() => onRemoveRx(ri)}
            onMove={(dir) => onMoveRx(ri, dir)}
          />
        ))}
        <button
          type="button"
          onClick={onAddRx}
          className="caps-mono cursor-pointer rounded px-1.5 py-0.5 text-[9px] tracking-[0.06em] text-tertiary hover:bg-canvas-muted hover:text-primary"
        >
          + Add exercise
        </button>
      </div>
    </div>
  );
}

// ── Prescription editor ──────────────────────────────────────────────

function PrescriptionEditor({
  rx,
  locationId,
  loadExercises,
  canUp,
  canDown,
  onPatch,
  onRemove,
  onMove,
}: {
  rx: PrescriptionDraft;
  locationId: string;
  loadExercises: LoadExercisesAction;
  canUp: boolean;
  canDown: boolean;
  onPatch: (patch: Partial<PrescriptionDraft>) => void;
  onRemove: () => void;
  onMove: (dir: -1 | 1) => void;
}) {
  const [expanded, setExpanded] = useState(false);
  const [whyOpen, setWhyOpen] = useState(false);
  const flagged = !!rx.validationError;

  const load = formatLoad(rx.targetWeightLbs, rx.intensity);
  const setsReps = formatSetsReps(rx.sets, rx.repsMin, rx.repsMax);

  return (
    <div
      className={`rounded px-2 py-1.5 ${
        flagged
          ? "border-[0.5px] border-alert/50 bg-alert-bg"
          : "border-[0.5px] border-border-subtle bg-surface"
      }`}
    >
      <div className="flex items-start gap-2">
        <div className="min-w-0 flex-1">
          <ExercisePicker
            locationId={locationId}
            exerciseId={rx.exerciseId}
            exerciseName={rx.exercise?.name ?? null}
            loadExercises={loadExercises}
            onSelect={(ex) =>
              onPatch({
                exerciseId: ex.exerciseId,
                exercise: {
                  exerciseId: ex.exerciseId,
                  name: ex.name,
                  primaryMuscles: ex.primaryMuscles,
                  formCues: ex.formCues,
                  demoFrames: ex.demoFrames,
                },
                // Clear stale validation flag once the user re-picks.
                validationError: null,
              })
            }
          />
          {flagged ? (
            <p className="mt-0.5 font-mono text-[10px] text-alert">
              {rx.validationError}
            </p>
          ) : null}
          {load || setsReps ? (
            <div className="mt-1 flex flex-wrap items-center gap-2">
              {load ? (
                <span className="text-[12px] font-medium tabular-nums text-primary">
                  {load}
                </span>
              ) : null}
              {setsReps ? (
                <span className="caps-mono text-[9px] tracking-[0.06em] text-tertiary">
                  {setsReps}
                </span>
              ) : null}
              {rx.loadBasis ? (
                <button
                  type="button"
                  onClick={() => setWhyOpen((v) => !v)}
                  aria-expanded={whyOpen}
                  className="caps-mono cursor-pointer rounded px-1 py-0.5 text-[8px] tracking-[0.06em] text-tertiary hover:bg-canvas-muted hover:text-secondary"
                >
                  {whyOpen ? "Why ▲" : "Why ▼"}
                </button>
              ) : null}
            </div>
          ) : null}
          {whyOpen && rx.loadBasis ? (
            <p className="mt-1 rounded bg-canvas-muted px-2 py-1 font-mono text-[10px] leading-[1.4] text-secondary">
              {rx.loadBasis}
            </p>
          ) : null}
        </div>
        <div className="flex shrink-0 items-center gap-1">
          <button
            type="button"
            onClick={() => setExpanded((v) => !v)}
            className="caps-mono cursor-pointer rounded px-1.5 py-0.5 text-[9px] tracking-[0.06em] text-tertiary hover:bg-canvas-muted hover:text-primary"
          >
            {expanded ? "Less" : "Edit"}
          </button>
          <MoveButtons
            onUp={() => onMove(-1)}
            onDown={() => onMove(1)}
            onRemove={onRemove}
            canUp={canUp}
            canDown={canDown}
            removeLabel="Remove exercise"
          />
        </div>
      </div>

      {expanded ? (
        <div className="mt-2 grid grid-cols-2 gap-2 sm:grid-cols-3">
          <label className="block">
            <FieldLabel>Sets</FieldLabel>
            <NumberInput value={rx.sets} onChange={(v) => onPatch({ sets: v })} />
          </label>
          <label className="block">
            <FieldLabel>Reps min</FieldLabel>
            <NumberInput
              value={rx.repsMin}
              onChange={(v) => onPatch({ repsMin: v })}
            />
          </label>
          <label className="block">
            <FieldLabel>Reps max</FieldLabel>
            <NumberInput
              value={rx.repsMax}
              onChange={(v) => onPatch({ repsMax: v })}
            />
          </label>
          <label className="block">
            <FieldLabel>Duration (s)</FieldLabel>
            <NumberInput
              value={rx.durationSeconds}
              onChange={(v) => onPatch({ durationSeconds: v })}
            />
          </label>
          <label className="block">
            <FieldLabel>Weight (lb)</FieldLabel>
            <NumberInput
              value={rx.targetWeightLbs}
              onChange={(v) => onPatch({ targetWeightLbs: v })}
              placeholder="—"
            />
          </label>
          <label className="block">
            <FieldLabel>Rest (s)</FieldLabel>
            <NumberInput
              value={rx.restSeconds}
              onChange={(v) => onPatch({ restSeconds: v })}
            />
          </label>
          <label className="block">
            <FieldLabel>Tempo</FieldLabel>
            <TextInput
              value={rx.tempo ?? ""}
              onChange={(v) => onPatch({ tempo: v || null })}
              placeholder="3-1-1"
            />
          </label>
          <div className="col-span-2 flex items-end gap-2 sm:col-span-1">
            <label className="block flex-1">
              <FieldLabel>Intensity</FieldLabel>
              <select
                value={rx.intensity.kind}
                onChange={(e) =>
                  onPatch({
                    intensity: {
                      kind: e.target.value as IntensityKind,
                      value:
                        e.target.value === "NONE" ? null : rx.intensity.value,
                    },
                  })
                }
                className="w-full cursor-pointer rounded-md border-[0.5px] border-border-default bg-surface px-2 py-1 text-[12px] text-primary outline-none focus:border-accent"
              >
                {INTENSITY_KINDS.map((k) => (
                  <option key={k} value={k}>
                    {INTENSITY_LABEL[k]}
                  </option>
                ))}
              </select>
            </label>
            {rx.intensity.kind !== "NONE" ? (
              <div className="w-16">
                <NumberInput
                  value={rx.intensity.value}
                  onChange={(v) =>
                    onPatch({ intensity: { ...rx.intensity, value: v } })
                  }
                />
              </div>
            ) : null}
          </div>
          <label className="block">
            <FieldLabel>Deload × sets</FieldLabel>
            <NumberInput
              value={rx.deloadModifier?.setsMultiplier ?? null}
              onChange={(v) =>
                onPatch({
                  deloadModifier: {
                    setsMultiplier: v,
                    intensityDelta: rx.deloadModifier?.intensityDelta ?? null,
                  },
                })
              }
            />
          </label>
          <label className="block">
            <FieldLabel>Deload Δ intensity</FieldLabel>
            <NumberInput
              value={rx.deloadModifier?.intensityDelta ?? null}
              onChange={(v) =>
                onPatch({
                  deloadModifier: {
                    setsMultiplier: rx.deloadModifier?.setsMultiplier ?? null,
                    intensityDelta: v,
                  },
                })
              }
            />
          </label>
          <label className="col-span-2 block sm:col-span-3">
            <FieldLabel>Notes</FieldLabel>
            <TextInput
              value={rx.notes ?? ""}
              onChange={(v) => onPatch({ notes: v || null })}
            />
          </label>
        </div>
      ) : null}
    </div>
  );
}

// ── Exercise picker (scoped to a gym) ────────────────────────────────

function ExercisePicker({
  locationId,
  exerciseId,
  exerciseName,
  loadExercises,
  onSelect,
}: {
  locationId: string;
  exerciseId: string;
  exerciseName: string | null;
  loadExercises: LoadExercisesAction;
  onSelect: (ex: ExerciseResponse) => void;
}) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [options, setOptions] = useState<ExerciseResponse[] | null>(null);
  const [loading, setLoading] = useState(false);

  // Lazy-load the gym's executable exercises when the picker opens (or when the
  // gym changes while open). Cached per render of this picker instance.
  useEffect(() => {
    if (!open) return;
    if (!locationId) {
      setOptions([]);
      return;
    }
    let cancelled = false;
    setLoading(true);
    loadExercises(locationId)
      .then((list) => {
        if (!cancelled) setOptions(list);
      })
      .catch(() => {
        if (!cancelled) setOptions([]);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [open, locationId, loadExercises]);

  const filtered = useMemo(() => {
    if (!options) return [];
    const q = query.trim().toLowerCase();
    if (!q) return options.slice(0, 50);
    return options
      .filter(
        (e) =>
          e.name.toLowerCase().includes(q) ||
          e.aliases.some((a) => a.toLowerCase().includes(q)),
      )
      .slice(0, 50);
  }, [options, query]);

  const label = exerciseName ?? (exerciseId ? exerciseId : "Choose exercise…");

  return (
    <div className="relative">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        className={`flex w-full items-center justify-between gap-2 rounded border-[0.5px] px-2 py-1 text-left text-[13px] ${
          exerciseId
            ? "border-border-default bg-surface text-primary"
            : "border-dashed border-border-default bg-canvas text-tertiary"
        } cursor-pointer hover:border-accent`}
      >
        <span className="min-w-0 truncate">{label}</span>
        <span className="caps-mono shrink-0 text-[9px] text-tertiary">
          {open ? "▲" : "▼"}
        </span>
      </button>

      {open ? (
        <div className="absolute z-20 mt-1 max-h-64 w-full overflow-hidden rounded-md border-[0.5px] border-border-default bg-surface shadow-lg">
          <div className="border-b-[0.5px] border-border-subtle p-1.5">
            <input
              autoFocus
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Search exercises at this gym…"
              className="w-full rounded border-[0.5px] border-border-default bg-canvas px-2 py-1 text-[12px] text-primary outline-none focus:border-accent"
            />
          </div>
          <div className="max-h-48 overflow-y-auto">
            {loading ? (
              <p className="px-2.5 py-2 font-mono text-[11px] text-tertiary">
                Loading…
              </p>
            ) : filtered.length === 0 ? (
              <p className="px-2.5 py-2 font-mono text-[11px] text-tertiary">
                {locationId
                  ? "No executable exercises match."
                  : "Pick a gym first."}
              </p>
            ) : (
              filtered.map((ex) => (
                <button
                  key={ex.exerciseId}
                  type="button"
                  onClick={() => {
                    onSelect(ex);
                    setOpen(false);
                    setQuery("");
                  }}
                  className={`block w-full cursor-pointer px-2.5 py-1.5 text-left text-[12px] hover:bg-canvas-muted ${
                    ex.exerciseId === exerciseId
                      ? "text-accent-dim"
                      : "text-primary"
                  }`}
                >
                  {ex.name}
                </button>
              ))
            )}
          </div>
        </div>
      ) : null}
    </div>
  );
}
