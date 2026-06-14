"use client";

import { useState } from "react";
import { ModalBackdrop } from "@/components/ui/ModalBackdrop";
import { useToast } from "@/components/ui/Toast";
import { useConfirm } from "@/components/ui/ConfirmDialog";
import type {
  ScheduledWorkoutResponse,
  Prescription,
  CompleteSessionRequest,
  LoggedPrescriptionInput,
  LoggedSetInput,
} from "@/lib/types/workout-program";
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
};

export function LogSessionModal({ session, onClose, onSaved, save }: Props) {
  if (!session) return null;
  // Key by session so the form state resets whenever a different session opens.
  return (
    <LogSessionForm
      key={`${session.phaseId}-${session.scheduledId}`}
      session={session}
      onClose={onClose}
      onSaved={onSaved}
      save={save}
    />
  );
}

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
}: Omit<Props, "session"> & { session: ScheduledWorkoutResponse }) {
  const toast = useToast();
  const confirm = useConfirm();
  const [isSaving, setIsSaving] = useState(false);

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
                return (
                  <div
                    key={key}
                    className="rounded-[10px] border-[0.5px] border-border-default bg-canvas px-3 py-2.5"
                  >
                    <div className="flex flex-wrap items-baseline gap-x-2">
                      <span className="text-[13px] font-medium text-primary">
                        {p.exercise?.name ?? p.exerciseId}
                      </span>
                      <span className="text-[11px] text-tertiary">
                        {formatPrescription(p)}
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
