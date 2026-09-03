"use client";

import { useEffect, useMemo, useState } from "react";
import { ModalBackdrop } from "@/components/ui/ModalBackdrop";
import { useToast } from "@/components/ui/Toast";
import { useConfirm } from "@/components/ui/ConfirmDialog";
import type {
  ScheduledWorkoutResponse,
  Prescription,
  CompleteSessionRequest,
  CustomizePrescriptionRequest,
  LoggedPrescriptionInput,
  LoggedSetInput,
} from "@/lib/types/workout-program";
import type { ExerciseResponse } from "@/lib/types/exercise";
import { BLOCK_TYPE_LABEL } from "@/lib/types/exercise";
import { formatPrescription, totalLoggedSets } from "@/lib/workout-format";

// "Log result / edit actuals" modal (ADR-0012 Decision 6, IMPL-17 D6). The web
// never logs live — this is the after-the-fact form against the idempotent
// completion upsert. One editable set list per prescription, prefilled from
// existing loggedSets (editing) or the prescription targets (first log), plus
// finish time + duration. No timer.

type Props = {
  /** Session being logged/edited; null renders nothing (modal closed). */
  session: ScheduledWorkoutResponse | null;
  onClose: () => void;
  /** Called after a successful save; the modal has already toasted. */
  onSaved: () => void;
  /** Server action: PUT the completion upsert for this session. */
  save: (input: CompleteSessionRequest) => Promise<void>;
  /** Server action: swap / rep-set edit for one prescription slot (#4). */
  customize: (input: CustomizePrescriptionRequest) => Promise<void>;
  /** Server action: ranked, gym-scoped swap suggestions for the picker. */
  suggestExercises: (
    locationId: string,
    similarTo: string | null,
    search: string | null,
  ) => Promise<ExerciseResponse[]>;
  /** Called after a successful customize so the underlying list revalidates. */
  onCustomized: () => void;
};

export function LogSessionModal({
  session,
  onClose,
  onSaved,
  save,
  customize,
  suggestExercises,
  onCustomized,
}: Props) {
  if (!session) return null;
  // Key by session so the form state resets whenever a different session opens.
  return (
    <LogSessionForm
      key={`${session.phaseId}-${session.scheduledId}`}
      session={session}
      onClose={onClose}
      onSaved={onSaved}
      save={save}
      customize={customize}
      suggestExercises={suggestExercises}
      onCustomized={onCustomized}
    />
  );
}

// A slot's applied swap / target edit, kept locally so the open modal reflects
// the change (the server snapshot behind `session` is stale until reopen).
type SlotOverride = {
  exerciseId: string;
  name: string;
  sets: number | null;
  repsMin: number | null;
  repsMax: number | null;
};

// One editable set as form state. Strings so partially-typed numbers don't
// fight the user; parsed to nullable numbers on save. `completedAt` is the
// per-set timestamp from a phone-logged session — carried through untouched
// so a web edit doesn't wipe it (new/edited rows just keep what they had).
type SetRow = {
  weight: string;
  reps: string;
  rpe: string;
  rest: string;
  completedAt: string | null;
};

const rowKey = (blockId: string, orderIndex: number) =>
  `${blockId}:${orderIndex}`;

function initialRows(p: Prescription): SetRow[] {
  const logged = p.loggedSets;
  if (logged && logged.length > 0) {
    return logged.map((s) => ({
      weight: s.weightLbs != null ? `${s.weightLbs}` : "",
      reps: s.reps != null ? `${s.reps}` : "",
      rpe: s.rpe != null ? `${s.rpe}` : "",
      rest: s.restSeconds != null ? `${s.restSeconds}` : "",
      completedAt: s.completedAt ?? null,
    }));
  }
  // First log: one row per prescribed set, prefilled from the targets so a
  // session that went to plan saves with minimal edits. Weight has no target.
  const targetReps = p.repsMax ?? p.repsMin;
  const targetRpe = p.intensity?.kind === "RPE" ? p.intensity.value : null;
  return Array.from({ length: p.sets ?? 1 }, () => ({
    weight: "",
    reps: targetReps != null ? `${targetReps}` : "",
    rpe: targetRpe != null ? `${targetRpe}` : "",
    rest: p.restSeconds != null ? `${p.restSeconds}` : "",
    completedAt: null,
  }));
}

// "now" (or an existing instant) as a datetime-local input value.
function toLocalInputValue(iso: string | null): string {
  const d = iso ? new Date(iso) : new Date();
  const pad = (n: number) => `${n}`.padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(
    d.getHours(),
  )}:${pad(d.getMinutes())}`;
}

function num(v: string): number | null {
  const t = v.trim();
  if (!t) return null;
  const n = Number(t);
  return Number.isFinite(n) ? n : null;
}

function int(v: string): number | null {
  const n = num(v);
  return n == null ? null : Math.round(n);
}

function LogSessionForm({
  session,
  onClose,
  onSaved,
  save,
  customize,
  suggestExercises,
  onCustomized,
}: Omit<Props, "session"> & { session: ScheduledWorkoutResponse }) {
  const toast = useToast();
  const confirm = useConfirm();
  const [isSaving, setIsSaving] = useState(false);
  // Locally-applied swaps / target edits, keyed by (blockId, orderIndex), so a
  // just-applied change shows immediately without waiting for a reopen.
  const [overrides, setOverrides] = useState<Record<string, SlotOverride>>({});

  const [rows, setRows] = useState<Record<string, SetRow[]>>(() => {
    const init: Record<string, SetRow[]> = {};
    for (const block of session.session.blocks) {
      for (const p of block.prescriptions) {
        init[rowKey(block.blockId, p.orderIndex)] = initialRows(p);
      }
    }
    return init;
  });
  const [completedAtLocal, setCompletedAtLocal] = useState(() =>
    toLocalInputValue(session.completedAt),
  );
  const [durationMin, setDurationMin] = useState(() =>
    session.durationSeconds != null
      ? `${Math.round(session.durationSeconds / 60)}`
      : "",
  );

  function updateRow(key: string, idx: number, patch: Partial<SetRow>) {
    setRows((prev) => ({
      ...prev,
      [key]: (prev[key] ?? []).map((r, i) => (i === idx ? { ...r, ...patch } : r)),
    }));
  }

  function addRow(key: string) {
    setRows((prev) => {
      const list = prev[key] ?? [];
      const last = list[list.length - 1];
      // Duplicate the previous set's numbers — straight sets are the norm.
      const next: SetRow = last
        ? { ...last, completedAt: null }
        : { weight: "", reps: "", rpe: "", rest: "", completedAt: null };
      return { ...prev, [key]: [...list, next] };
    });
  }

  function removeRow(key: string, idx: number) {
    setRows((prev) => ({
      ...prev,
      [key]: (prev[key] ?? []).filter((_, i) => i !== idx),
    }));
  }

  // Apply a swap / target edit for one slot via the customize server action,
  // then record it locally so the row updates in place (and revalidate the
  // list underneath). Returns whether it succeeded so the panel can collapse.
  async function applyCustomize(
    block: { blockId: string },
    p: Prescription,
    change: {
      exerciseId: string | null;
      name: string | null;
      sets: number | null;
      repsMin: number | null;
      repsMax: number | null;
      applyToProgram: boolean;
    },
  ): Promise<boolean> {
    try {
      await customize({
        blockId: block.blockId,
        orderIndex: p.orderIndex,
        applyToProgram: change.applyToProgram,
        exerciseId: change.exerciseId,
        sets: change.sets,
        repsMin: change.repsMin,
        repsMax: change.repsMax,
        // Day reference lets the server materialize an ad-hoc session first.
        phaseId: session.phaseId,
        dayId: session.dayId,
        date: session.date,
      });
      const key = rowKey(block.blockId, p.orderIndex);
      const prev = overrides[key];
      setOverrides((o) => ({
        ...o,
        [key]: {
          exerciseId: change.exerciseId ?? prev?.exerciseId ?? p.exerciseId,
          name: change.name ?? prev?.name ?? p.exercise?.name ?? p.exerciseId,
          sets: change.sets ?? prev?.sets ?? p.sets,
          repsMin: change.repsMin ?? prev?.repsMin ?? p.repsMin,
          repsMax: change.repsMax ?? prev?.repsMax ?? p.repsMax,
        },
      }));
      toast.success(
        change.applyToProgram ? "Updated across the program" : "Updated this workout",
      );
      onCustomized();
      return true;
    } catch (e) {
      toast.error("Couldn't apply the change", {
        description: e instanceof Error ? e.message : "Try again.",
      });
      return false;
    }
  }

  // Full-replace semantics: every prescription is sent, with whatever rows
  // carry at least one value. An emptied list clears that exercise's actuals.
  function buildLogged(): LoggedPrescriptionInput[] {
    const out: LoggedPrescriptionInput[] = [];
    for (const block of session.session.blocks) {
      for (const p of block.prescriptions) {
        const list = rows[rowKey(block.blockId, p.orderIndex)] ?? [];
        const sets: LoggedSetInput[] = list
          .filter(
            (r) => r.weight.trim() || r.reps.trim() || r.rpe.trim() || r.rest.trim(),
          )
          .map((r) => ({
            weightLbs: num(r.weight),
            reps: int(r.reps),
            rpe: num(r.rpe),
            restSeconds: int(r.rest),
            completedAt: r.completedAt,
          }));
        out.push({ blockId: block.blockId, orderIndex: p.orderIndex, sets });
      }
    }
    return out;
  }

  async function handleComplete() {
    const completedAt = completedAtLocal ? new Date(completedAtLocal) : null;
    if (!completedAt || Number.isNaN(completedAt.getTime())) {
      toast.error("Finish time is required");
      return;
    }
    const minutes = num(durationMin);
    if (minutes == null || minutes <= 0) {
      toast.error("Duration is required", {
        description: "How long the session took, in minutes.",
      });
      return;
    }
    setIsSaving(true);
    try {
      await save({
        status: "COMPLETED",
        completedAt: completedAt.toISOString(),
        durationSeconds: Math.round(minutes * 60),
        logged: buildLogged(),
      });
      toast.success(
        session.status === "COMPLETED" ? "Session updated" : "Session logged",
      );
      onSaved();
    } catch (e) {
      toast.error("Couldn't save session", {
        description: e instanceof Error ? e.message : "Try again.",
      });
    } finally {
      setIsSaving(false);
    }
  }

  async function handleSkip() {
    // Skipping clears actuals (IMPL-17 D4) — destructive when any exist.
    if (session.status === "COMPLETED" || totalLoggedSets(session) > 0) {
      const ok = await confirm({
        title: "Mark session skipped?",
        description:
          "Skipping clears the logged sets and completion for this session.",
        confirmLabel: "Mark skipped",
        tone: "danger",
      });
      if (!ok) return;
    }
    setIsSaving(true);
    try {
      await save({
        status: "SKIPPED",
        completedAt: null,
        durationSeconds: null,
        logged: [],
      });
      toast.success("Session marked skipped");
      onSaved();
    } catch (e) {
      toast.error("Couldn't update session", {
        description: e instanceof Error ? e.message : "Try again.",
      });
    } finally {
      setIsSaving(false);
    }
  }

  const sessionDate = new Date(`${session.date}T00:00:00`).toLocaleDateString(
    "en-US",
    { weekday: "short", month: "short", day: "numeric", year: "numeric" },
  );

  let firstInput = true;

  return (
    <ModalBackdrop
      onClose={onClose}
      contentClassName="w-[640px] max-w-[94vw] max-h-[90vh] overflow-y-auto rounded-lg border border-border-default bg-surface p-6 shadow-[0_24px_64px_rgba(0,0,0,0.16)]"
    >
      <h2 className="text-[18px] font-semibold text-primary">
        {session.status === "PLANNED" ? "Log result" : "Edit result"}
      </h2>
      <p className="caps-mono mt-1 text-[10px] tracking-[0.06em] text-tertiary">
        {session.dayLabel} · {sessionDate} · {session.locationName}
      </p>

      <div className="mt-4 space-y-4">
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

            <div className="mt-2 space-y-3">
              {block.prescriptions.map((p) => {
                const key = rowKey(block.blockId, p.orderIndex);
                const list = rows[key] ?? [];
                const autoFocusHere = firstInput && list.length > 0;
                if (autoFocusHere) firstInput = false;
                const ov = overrides[key];
                return (
                  <div
                    key={key}
                    className="rounded-[10px] border-[0.5px] border-border-default bg-canvas px-3 py-2.5"
                  >
                    <div className="flex flex-wrap items-baseline gap-x-2">
                      <span className="text-[13px] font-medium text-primary">
                        {ov?.name ?? p.exercise?.name ?? p.exerciseId}
                      </span>
                      <span className="text-[11px] text-tertiary">
                        {ov ? overrideSummary(ov) : formatPrescription(p)}
                      </span>
                    </div>

                    {list.length > 0 ? (
                      <div className="caps-mono mt-2 grid grid-cols-[20px_1fr_1fr_1fr_1fr_20px] items-center gap-x-2 text-[9px] tracking-[0.06em] text-tertiary">
                        <span />
                        <span>Lb</span>
                        <span>Reps</span>
                        <span>RPE</span>
                        <span>Rest s</span>
                        <span />
                      </div>
                    ) : (
                      <p className="mt-2 text-[12px] text-tertiary">
                        No sets — this exercise won&apos;t be logged.
                      </p>
                    )}
                    <div className="mt-1 space-y-1.5">
                      {list.map((row, i) => (
                        <div
                          key={i}
                          className="grid grid-cols-[20px_1fr_1fr_1fr_1fr_20px] items-center gap-x-2"
                        >
                          <span className="caps-mono text-[10px] text-tertiary">
                            {i + 1}
                          </span>
                          <SetInput
                            value={row.weight}
                            onChange={(v) => updateRow(key, i, { weight: v })}
                            step="2.5"
                            autoFocus={autoFocusHere && i === 0}
                          />
                          <SetInput
                            value={row.reps}
                            onChange={(v) => updateRow(key, i, { reps: v })}
                            step="1"
                          />
                          <SetInput
                            value={row.rpe}
                            onChange={(v) => updateRow(key, i, { rpe: v })}
                            step="0.5"
                          />
                          <SetInput
                            value={row.rest}
                            onChange={(v) => updateRow(key, i, { rest: v })}
                            step="15"
                          />
                          <button
                            type="button"
                            onClick={() => removeRow(key, i)}
                            aria-label="Remove set"
                            className="cursor-pointer text-[13px] leading-none text-tertiary hover:text-alert"
                          >
                            ×
                          </button>
                        </div>
                      ))}
                    </div>
                    <button
                      type="button"
                      onClick={() => addRow(key)}
                      className="caps-mono mt-2 cursor-pointer text-[10px] tracking-[0.06em] text-secondary hover:text-primary"
                    >
                      + Add set
                    </button>

                    <PrescriptionAdjuster
                      locationId={session.locationId}
                      currentExerciseId={ov?.exerciseId ?? p.exerciseId}
                      sets={ov?.sets ?? p.sets}
                      repsMin={ov?.repsMin ?? p.repsMin}
                      repsMax={ov?.repsMax ?? p.repsMax}
                      suggestExercises={suggestExercises}
                      onApply={(change) => applyCustomize(block, p, change)}
                    />
                  </div>
                );
              })}
            </div>
          </div>
        ))}
      </div>

      <div className="mt-5 grid grid-cols-2 gap-3 border-t-[0.5px] border-border-subtle pt-4">
        <div>
          <label className="mb-1 block text-xs font-medium text-secondary">
            Finished at
          </label>
          <input
            type="datetime-local"
            value={completedAtLocal}
            onChange={(e) => setCompletedAtLocal(e.target.value)}
            className="w-full rounded-md border border-border-default bg-canvas px-3 py-2 text-sm text-primary focus:outline-none focus:ring-2 focus:ring-accent"
          />
        </div>
        <div>
          <label className="mb-1 block text-xs font-medium text-secondary">
            Duration (minutes)
          </label>
          <input
            type="number"
            min="1"
            inputMode="numeric"
            value={durationMin}
            onChange={(e) => setDurationMin(e.target.value)}
            placeholder="e.g. 55"
            className="w-full rounded-md border border-border-default bg-canvas px-3 py-2 text-sm text-primary focus:outline-none focus:ring-2 focus:ring-accent"
          />
        </div>
      </div>

      <div className="mt-6 flex items-center justify-between gap-2">
        <button
          type="button"
          onClick={handleSkip}
          disabled={isSaving}
          className="cursor-pointer rounded-md border border-border-default bg-canvas px-4 py-2 text-sm font-medium text-alert hover:bg-surface disabled:cursor-not-allowed disabled:opacity-50"
        >
          Mark skipped
        </button>
        <div className="flex gap-2">
          <button
            type="button"
            onClick={onClose}
            disabled={isSaving}
            className="cursor-pointer rounded-md border border-border-default bg-canvas px-4 py-2 text-sm font-medium text-primary hover:bg-surface disabled:cursor-not-allowed disabled:opacity-50"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={handleComplete}
            disabled={isSaving}
            className="cursor-pointer rounded-md bg-accent px-4 py-2 text-sm font-medium text-inverse hover:bg-accent/90 disabled:cursor-not-allowed disabled:opacity-50"
          >
            {isSaving ? "Saving…" : "Save completed"}
          </button>
        </div>
      </div>
    </ModalBackdrop>
  );
}

function SetInput({
  value,
  onChange,
  step,
  autoFocus,
}: {
  value: string;
  onChange: (v: string) => void;
  step: string;
  autoFocus?: boolean;
}) {
  return (
    <input
      type="number"
      min="0"
      step={step}
      inputMode="decimal"
      value={value}
      autoFocus={autoFocus}
      onChange={(e) => onChange(e.target.value)}
      className="w-full rounded-md border border-border-default bg-surface px-2 py-1 text-[13px] text-primary focus:outline-none focus:ring-2 focus:ring-accent"
    />
  );
}

// "3 × 5–8" style summary for a locally-applied override (the source
// prescription's intensity/rest are unchanged by a swap/target edit).
function overrideSummary(ov: SlotOverride): string {
  const reps =
    ov.repsMin != null && ov.repsMax != null && ov.repsMin !== ov.repsMax
      ? `${ov.repsMin}–${ov.repsMax}`
      : ov.repsMax != null || ov.repsMin != null
        ? `${ov.repsMax ?? ov.repsMin}`
        : "";
  if (ov.sets != null && reps) return `${ov.sets} × ${reps}`;
  if (ov.sets != null) return `${ov.sets} sets`;
  return reps;
}

type AdjustChange = {
  exerciseId: string | null;
  name: string | null;
  sets: number | null;
  repsMin: number | null;
  repsMax: number | null;
  applyToProgram: boolean;
};

// Per-prescription "swap / adjust" panel: pick a muscle-matched replacement
// and/or edit sets & rep range, applied to just this workout or the whole
// program (#4). Collapsed by default so the logging grid stays uncluttered.
function PrescriptionAdjuster({
  locationId,
  currentExerciseId,
  sets,
  repsMin,
  repsMax,
  suggestExercises,
  onApply,
}: {
  locationId: string;
  currentExerciseId: string;
  sets: number | null;
  repsMin: number | null;
  repsMax: number | null;
  suggestExercises: (
    locationId: string,
    similarTo: string | null,
    search: string | null,
  ) => Promise<ExerciseResponse[]>;
  onApply: (change: AdjustChange) => Promise<boolean>;
}) {
  const [open, setOpen] = useState(false);
  const [showPicker, setShowPicker] = useState(false);
  const [picked, setPicked] = useState<{ id: string; name: string } | null>(null);
  const [setsStr, setSetsStr] = useState(sets != null ? `${sets}` : "");
  const [repsMinStr, setRepsMinStr] = useState(repsMin != null ? `${repsMin}` : "");
  const [repsMaxStr, setRepsMaxStr] = useState(repsMax != null ? `${repsMax}` : "");
  const [applyToProgram, setApplyToProgram] = useState(false);
  const [applying, setApplying] = useState(false);

  const targetsChanged =
    setsStr !== (sets != null ? `${sets}` : "") ||
    repsMinStr !== (repsMin != null ? `${repsMin}` : "") ||
    repsMaxStr !== (repsMax != null ? `${repsMax}` : "");
  const hasChange = picked != null || targetsChanged;

  async function apply() {
    setApplying(true);
    const ok = await onApply({
      exerciseId: picked?.id ?? null,
      name: picked?.name ?? null,
      sets: setsStr.trim() ? int(setsStr) : null,
      repsMin: repsMinStr.trim() ? int(repsMinStr) : null,
      repsMax: repsMaxStr.trim() ? int(repsMaxStr) : null,
      applyToProgram,
    });
    setApplying(false);
    if (ok) {
      setOpen(false);
      setShowPicker(false);
      setPicked(null);
    }
  }

  if (!open) {
    return (
      <button
        type="button"
        onClick={() => setOpen(true)}
        className="caps-mono mt-2 ml-3 cursor-pointer text-[10px] tracking-[0.06em] text-accent hover:opacity-80"
      >
        Swap / adjust
      </button>
    );
  }

  return (
    <div className="mt-2 rounded-[8px] border-[0.5px] border-border-subtle bg-surface px-3 py-2.5">
      <div className="flex items-center justify-between gap-2">
        <span className="text-[12px] font-medium text-primary">
          {picked ? `Swapping to ${picked.name}` : "Swap or adjust"}
        </span>
        <button
          type="button"
          onClick={() => setShowPicker((v) => !v)}
          className="caps-mono cursor-pointer text-[10px] tracking-[0.06em] text-accent hover:opacity-80"
        >
          {showPicker ? "Hide options" : "Swap exercise"}
        </button>
      </div>

      {showPicker ? (
        <SwapPicker
          locationId={locationId}
          currentExerciseId={currentExerciseId}
          suggestExercises={suggestExercises}
          onPick={(ex) => {
            setPicked({ id: ex.exerciseId, name: ex.name });
            setShowPicker(false);
          }}
        />
      ) : null}

      <div className="mt-2 grid grid-cols-3 gap-2">
        <label className="caps-mono text-[9px] tracking-[0.06em] text-tertiary">
          Sets
          <SetInput value={setsStr} onChange={setSetsStr} step="1" />
        </label>
        <label className="caps-mono text-[9px] tracking-[0.06em] text-tertiary">
          Reps min
          <SetInput value={repsMinStr} onChange={setRepsMinStr} step="1" />
        </label>
        <label className="caps-mono text-[9px] tracking-[0.06em] text-tertiary">
          Reps max
          <SetInput value={repsMaxStr} onChange={setRepsMaxStr} step="1" />
        </label>
      </div>

      <label className="mt-2 flex cursor-pointer items-center gap-2 text-[12px] text-secondary">
        <input
          type="checkbox"
          checked={applyToProgram}
          onChange={(e) => setApplyToProgram(e.target.checked)}
          className="h-3.5 w-3.5 cursor-pointer accent-accent"
        />
        Apply to the whole program (this day, future weeks)
      </label>

      <div className="mt-2.5 flex justify-end gap-2">
        <button
          type="button"
          onClick={() => {
            setOpen(false);
            setShowPicker(false);
            setPicked(null);
          }}
          disabled={applying}
          className="cursor-pointer rounded-md border border-border-default bg-canvas px-3 py-1 text-[12px] text-primary hover:bg-surface disabled:opacity-50"
        >
          Cancel
        </button>
        <button
          type="button"
          onClick={apply}
          disabled={applying || !hasChange}
          className="cursor-pointer rounded-md bg-accent px-3 py-1 text-[12px] font-medium text-inverse hover:bg-accent/90 disabled:cursor-not-allowed disabled:opacity-50"
        >
          {applying ? "Applying…" : "Apply"}
        </button>
      </div>
    </div>
  );
}

// The swap picker: loads the gym's ranked, muscle-matched suggestions once, then
// filters that list client-side by name/alias (the list holds the full gym
// catalog, so no per-keystroke server round-trips).
function SwapPicker({
  locationId,
  currentExerciseId,
  suggestExercises,
  onPick,
}: {
  locationId: string;
  currentExerciseId: string;
  suggestExercises: (
    locationId: string,
    similarTo: string | null,
    search: string | null,
  ) => Promise<ExerciseResponse[]>;
  onPick: (ex: ExerciseResponse) => void;
}) {
  const [all, setAll] = useState<ExerciseResponse[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [search, setSearch] = useState("");

  useEffect(() => {
    let alive = true;
    suggestExercises(locationId, currentExerciseId, null)
      .then((list) => {
        if (alive) setAll(list);
      })
      .catch((e) => {
        if (alive) setError(e instanceof Error ? e.message : "Couldn't load alternatives");
      });
    return () => {
      alive = false;
    };
  }, [locationId, currentExerciseId, suggestExercises]);

  const filtered = useMemo(() => {
    if (!all) return [];
    const q = search.trim().toLowerCase();
    if (!q) return all;
    return all.filter(
      (e) =>
        e.name.toLowerCase().includes(q) ||
        e.aliases.some((a) => a.toLowerCase().includes(q)),
    );
  }, [all, search]);

  return (
    <div className="mt-2">
      <input
        type="text"
        value={search}
        onChange={(e) => setSearch(e.target.value)}
        placeholder="Search exercises…"
        className="w-full rounded-md border border-border-default bg-canvas px-2.5 py-1.5 text-[13px] text-primary focus:outline-none focus:ring-2 focus:ring-accent"
      />
      {error ? (
        <p className="mt-2 text-[12px] text-alert">{error}</p>
      ) : all == null ? (
        <p className="mt-2 text-[12px] text-tertiary">Loading alternatives…</p>
      ) : filtered.length === 0 ? (
        <p className="mt-2 text-[12px] text-tertiary">No matching exercises.</p>
      ) : (
        <ul className="mt-2 max-h-[220px] space-y-1 overflow-y-auto">
          {filtered.map((ex) => (
            <li key={ex.exerciseId}>
              <button
                type="button"
                onClick={() => onPick(ex)}
                className="w-full rounded-md border-[0.5px] border-border-default bg-canvas px-2.5 py-1.5 text-left hover:bg-canvas-muted"
              >
                <span className="text-[13px] text-primary">{ex.name}</span>
                {ex.primaryMuscles.length > 0 ? (
                  <span className="ml-2 text-[11px] text-tertiary">
                    {ex.primaryMuscles.join(", ")}
                  </span>
                ) : null}
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
