"use client";

import { useMemo, useState, useTransition } from "react";
import type {
  Comparator,
  GoalDomain,
  StepKind,
} from "@/lib/types/goals";
import {
  COMPARATOR_SYMBOL,
  DOMAINS,
  DOMAIN_LABEL,
  METRIC_REGISTRY,
  STEP_KINDS,
  STEP_KIND_LABEL,
} from "@/lib/types/goals";
import { useToast } from "@/components/ui/Toast";

// ── Self-contained editable proposal model ──────────────────────────
//
// GoalProposalCard is the single editable Goal/Phase/Step structure used
// both as the standalone manual editor (opened blank) and — later —
// inline in the Goals chat as the `propose_goal_structure` tool result.
// It is presentation-agnostic about its host: it owns its own editable
// state, accepts an optional `initialValue`, and reports the final shape
// through `onSave`. Each field may carry an optional `validationError`
// string (set by the backend proposal validator) which renders inline.

export type MetricBindingDraft = {
  metricKey: string;
  comparator: Comparator;
  targetValue: number | "";
  windowDays?: number | "" | null;
  countFrom?: string | null;
};

export type StepDraft = {
  // Stable local key for list rendering; not sent to the backend.
  key: string;
  title: string;
  kind: StepKind;
  metric?: MetricBindingDraft | null;
  validationError?: string | null;
  metricError?: string | null;
};

export type PhaseDraft = {
  key: string;
  title: string;
  description: string;
  targetStartDate: string;
  targetEndDate: string;
  steps: StepDraft[];
  validationError?: string | null;
};

export type GoalProposalDraft = {
  title: string;
  description: string;
  domain: GoalDomain;
  targetDate: string;
  startDate?: string;
  phases: PhaseDraft[];
  // Goal-level inline validation note (e.g. "target date in the past").
  validationError?: string | null;
};

export type GoalProposalCardProps = {
  // Blank when omitted (manual-create entry point).
  initialValue?: GoalProposalDraft;
  // Receives the final edited draft. The host decides where it goes:
  // the manual editor posts to the CRUD endpoints; the chat host posts
  // to the commit endpoint.
  onSave: (draft: GoalProposalDraft) => Promise<void>;
  // Optional discard hook (e.g. close a modal / collapse a chat bubble).
  onDiscard?: () => void;
  // Heading shown above the editor; defaults suit the manual flow.
  heading?: string;
  saveLabel?: string;
};

const COMPARATORS: Comparator[] = ["LT", "LTE", "GT", "GTE", "EQ"];

let keySeq = 0;
function nextKey(prefix: string): string {
  keySeq += 1;
  return `${prefix}-${keySeq}-${Math.random().toString(36).slice(2, 7)}`;
}

function today(): string {
  return new Date().toISOString().slice(0, 10);
}

function blankDraft(): GoalProposalDraft {
  return {
    title: "",
    description: "",
    domain: "CARDIOVASCULAR",
    targetDate: "",
    startDate: today(),
    phases: [],
  };
}

function blankPhase(): PhaseDraft {
  return {
    key: nextKey("phase"),
    title: "",
    description: "",
    targetStartDate: "",
    targetEndDate: "",
    steps: [],
  };
}

function blankStep(): StepDraft {
  return { key: nextKey("step"), title: "", kind: "MANUAL", metric: null };
}

// COUNT steps only allow GTE/GT/EQ comparators (backend validator rejects LT/LTE).
// Other kinds default to LT.
function defaultMetric(kind?: StepKind): MetricBindingDraft {
  const first = METRIC_REGISTRY[0]!;
  return {
    metricKey: first.key,
    comparator: kind === "COUNT" ? "GTE" : "LT",
    targetValue: "",
    windowDays: null,
    countFrom: null,
  };
}

function move<T>(arr: T[], from: number, to: number): T[] {
  if (to < 0 || to >= arr.length) return arr;
  const copy = arr.slice();
  const [item] = copy.splice(from, 1);
  copy.splice(to, 0, item!);
  return copy;
}

export function GoalProposalCard({
  initialValue,
  onSave,
  onDiscard,
  heading = "New goal",
  saveLabel = "Save goal",
}: GoalProposalCardProps) {
  const toast = useToast();
  const seed = useMemo(() => initialValue ?? blankDraft(), [initialValue]);
  const [draft, setDraft] = useState<GoalProposalDraft>(seed);
  const [pending, startTransition] = useTransition();

  function patch(p: Partial<GoalProposalDraft>) {
    setDraft((d) => ({ ...d, ...p }));
  }

  function patchPhase(idx: number, p: Partial<PhaseDraft>) {
    setDraft((d) => ({
      ...d,
      phases: d.phases.map((ph, i) => (i === idx ? { ...ph, ...p } : ph)),
    }));
  }

  function patchStep(pIdx: number, sIdx: number, p: Partial<StepDraft>) {
    setDraft((d) => ({
      ...d,
      phases: d.phases.map((ph, i) =>
        i === pIdx
          ? {
              ...ph,
              steps: ph.steps.map((st, j) => (j === sIdx ? { ...st, ...p } : st)),
            }
          : ph,
      ),
    }));
  }

  function addPhase() {
    setDraft((d) => ({ ...d, phases: [...d.phases, blankPhase()] }));
  }

  function removePhase(idx: number) {
    setDraft((d) => ({ ...d, phases: d.phases.filter((_, i) => i !== idx) }));
  }

  function reorderPhase(idx: number, dir: -1 | 1) {
    setDraft((d) => ({ ...d, phases: move(d.phases, idx, idx + dir) }));
  }

  function addStep(pIdx: number) {
    setDraft((d) => ({
      ...d,
      phases: d.phases.map((ph, i) =>
        i === pIdx ? { ...ph, steps: [...ph.steps, blankStep()] } : ph,
      ),
    }));
  }

  function removeStep(pIdx: number, sIdx: number) {
    setDraft((d) => ({
      ...d,
      phases: d.phases.map((ph, i) =>
        i === pIdx
          ? { ...ph, steps: ph.steps.filter((_, j) => j !== sIdx) }
          : ph,
      ),
    }));
  }

  function reorderStep(pIdx: number, sIdx: number, dir: -1 | 1) {
    setDraft((d) => ({
      ...d,
      phases: d.phases.map((ph, i) =>
        i === pIdx ? { ...ph, steps: move(ph.steps, sIdx, sIdx + dir) } : ph,
      ),
    }));
  }

  function onStepKindChange(pIdx: number, sIdx: number, kind: StepKind) {
    const needsMetric = kind !== "MANUAL";
    const existing = draft.phases[pIdx]?.steps[sIdx]?.metric;
    // When switching to COUNT, ensure the comparator is legal (GTE/GT/EQ).
    // When switching away from COUNT to a threshold/sustained kind, reset to LT.
    const COUNT_LEGAL: Set<Comparator> = new Set(["GTE", "GT", "EQ"]);
    const existingWithFixedComparator: MetricBindingDraft | null | undefined =
      existing
        ? kind === "COUNT" && !COUNT_LEGAL.has(existing.comparator)
          ? { ...existing, comparator: "GTE" }
          : kind !== "COUNT" && COUNT_LEGAL.has(existing.comparator) && existing.comparator !== "EQ"
          ? { ...existing, comparator: "LT" }
          : existing
        : null;
    patchStep(pIdx, sIdx, {
      kind,
      metric: needsMetric ? existingWithFixedComparator ?? defaultMetric(kind) : null,
    });
  }

  function clientValidate(d: GoalProposalDraft): string | null {
    if (!d.title.trim()) return "Goal needs a title.";
    if (!d.targetDate) return "Goal needs a target date.";
    if (d.phases.length === 0) return "Add at least one phase.";
    for (const ph of d.phases) {
      if (!ph.title.trim()) return "Every phase needs a title.";
      if (!ph.targetStartDate || !ph.targetEndDate)
        return `Phase "${ph.title || "untitled"}" needs start and end dates.`;
      for (const st of ph.steps) {
        if (!st.title.trim()) return "Every step needs a title.";
        if (st.kind !== "MANUAL") {
          const m = st.metric;
          if (!m || m.metricKey === "" || m.targetValue === "")
            return `Step "${st.title}" needs a metric and target value.`;
          if (st.kind === "SUSTAINED" && (m.windowDays === "" || m.windowDays == null))
            return `Sustained step "${st.title}" needs a window in days.`;
        }
      }
    }
    return null;
  }

  function handleSave() {
    const err = clientValidate(draft);
    if (err) {
      toast.error("Can't save yet", { description: err });
      return;
    }
    startTransition(async () => {
      try {
        await onSave(draft);
      } catch {
        toast.error("Couldn't save goal", {
          description: "The goal wasn't created. Try again.",
        });
      }
    });
  }

  function handleDiscard() {
    setDraft(initialValue ?? blankDraft());
    onDiscard?.();
  }

  return (
    <div className="rounded-[14px] border-[0.5px] border-border-default bg-surface">
      <div className="flex items-center justify-between border-b-[0.5px] border-border-subtle px-5 py-3">
        <h2 className="m-0 text-[14px] font-medium text-primary">{heading}</h2>
        <span className="caps-mono text-[9px] tracking-[0.06em] text-tertiary">
          {draft.phases.length} phase{draft.phases.length === 1 ? "" : "s"}
        </span>
      </div>

      <div className="space-y-5 px-5 py-5">
        {/* Goal fields */}
        <div className="space-y-3">
          {draft.validationError ? (
            <InlineError>{draft.validationError}</InlineError>
          ) : null}
          <Field label="Title">
            <input
              value={draft.title}
              onChange={(e) => patch({ title: e.target.value })}
              placeholder="Lower cardiovascular risk markers into optimal range"
              className={`${inputCls} w-full`}
            />
          </Field>
          <Field label="Description">
            <textarea
              value={draft.description}
              onChange={(e) => patch({ description: e.target.value })}
              rows={2}
              className={`${inputCls} w-full`}
            />
          </Field>
          <div className="grid grid-cols-2 gap-3">
            <Field label="Domain">
              <select
                value={draft.domain}
                onChange={(e) => patch({ domain: e.target.value as GoalDomain })}
                className={`${inputCls} w-full`}
              >
                {DOMAINS.map((d) => (
                  <option key={d} value={d}>
                    {DOMAIN_LABEL[d]}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="Target date">
              <input
                type="date"
                value={draft.targetDate}
                onChange={(e) => patch({ targetDate: e.target.value })}
                className={`${inputCls} w-full`}
              />
            </Field>
          </div>
        </div>

        {/* Phases */}
        <div className="space-y-3">
          {draft.phases.map((phase, pIdx) => (
            <PhaseEditor
              key={phase.key}
              phase={phase}
              index={pIdx}
              total={draft.phases.length}
              onChange={(p) => patchPhase(pIdx, p)}
              onRemove={() => removePhase(pIdx)}
              onReorder={(dir) => reorderPhase(pIdx, dir)}
              onAddStep={() => addStep(pIdx)}
              onStepChange={(sIdx, p) => patchStep(pIdx, sIdx, p)}
              onStepKind={(sIdx, kind) => onStepKindChange(pIdx, sIdx, kind)}
              onStepRemove={(sIdx) => removeStep(pIdx, sIdx)}
              onStepReorder={(sIdx, dir) => reorderStep(pIdx, sIdx, dir)}
            />
          ))}

          <button
            type="button"
            onClick={addPhase}
            className="caps-mono w-full cursor-pointer rounded-md border-[0.5px] border-dashed border-border-default px-3 py-2 text-[10px] tracking-[0.06em] text-tertiary hover:border-accent hover:text-secondary"
          >
            + Add phase
          </button>
        </div>
      </div>

      <div className="flex items-center justify-end gap-2 border-t-[0.5px] border-border-subtle px-5 py-3">
        <button
          type="button"
          onClick={handleDiscard}
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
          {pending ? "Saving…" : saveLabel}
        </button>
      </div>
    </div>
  );
}

function PhaseEditor({
  phase,
  index,
  total,
  onChange,
  onRemove,
  onReorder,
  onAddStep,
  onStepChange,
  onStepKind,
  onStepRemove,
  onStepReorder,
}: {
  phase: PhaseDraft;
  index: number;
  total: number;
  onChange: (p: Partial<PhaseDraft>) => void;
  onRemove: () => void;
  onReorder: (dir: -1 | 1) => void;
  onAddStep: () => void;
  onStepChange: (sIdx: number, p: Partial<StepDraft>) => void;
  onStepKind: (sIdx: number, kind: StepKind) => void;
  onStepRemove: (sIdx: number) => void;
  onStepReorder: (sIdx: number, dir: -1 | 1) => void;
}) {
  return (
    <div className="rounded-[10px] border-[0.5px] border-border-default bg-canvas px-4 py-3">
      <div className="flex items-center justify-between">
        <span className="caps-mono text-[9px] tracking-[0.06em] text-tertiary">
          Phase {index + 1}
        </span>
        <div className="flex items-center gap-1">
          <ReorderButtons
            onUp={() => onReorder(-1)}
            onDown={() => onReorder(1)}
            canUp={index > 0}
            canDown={index < total - 1}
          />
          <button
            type="button"
            onClick={onRemove}
            aria-label="Remove phase"
            className="cursor-pointer rounded px-1.5 py-0.5 text-[12px] text-tertiary hover:text-alert"
          >
            ×
          </button>
        </div>
      </div>

      {phase.validationError ? (
        <InlineError>{phase.validationError}</InlineError>
      ) : null}

      <div className="mt-2 space-y-2">
        <input
          value={phase.title}
          onChange={(e) => onChange({ title: e.target.value })}
          placeholder="Phase title"
          className={`${inputCls} w-full`}
        />
        <input
          value={phase.description}
          onChange={(e) => onChange({ description: e.target.value })}
          placeholder="Description (optional)"
          className={`${inputCls} w-full`}
        />
        <div className="grid grid-cols-2 gap-2">
          <Field label="Start">
            <input
              type="date"
              value={phase.targetStartDate}
              onChange={(e) => onChange({ targetStartDate: e.target.value })}
              className={`${inputCls} w-full`}
            />
          </Field>
          <Field label="End">
            <input
              type="date"
              value={phase.targetEndDate}
              onChange={(e) => onChange({ targetEndDate: e.target.value })}
              className={`${inputCls} w-full`}
            />
          </Field>
        </div>
      </div>

      <div className="mt-3 space-y-1.5 border-t-[0.5px] border-border-subtle pt-2">
        {phase.steps.map((step, sIdx) => (
          <StepEditor
            key={step.key}
            step={step}
            index={sIdx}
            total={phase.steps.length}
            onChange={(p) => onStepChange(sIdx, p)}
            onKind={(kind) => onStepKind(sIdx, kind)}
            onRemove={() => onStepRemove(sIdx)}
            onReorder={(dir) => onStepReorder(sIdx, dir)}
          />
        ))}
        <button
          type="button"
          onClick={onAddStep}
          className="caps-mono w-full cursor-pointer rounded border-[0.5px] border-dashed border-border-default px-2 py-1.5 text-[9px] tracking-[0.06em] text-tertiary hover:border-accent hover:text-secondary"
        >
          + Add step
        </button>
      </div>
    </div>
  );
}

function StepEditor({
  step,
  index,
  total,
  onChange,
  onKind,
  onRemove,
  onReorder,
}: {
  step: StepDraft;
  index: number;
  total: number;
  onChange: (p: Partial<StepDraft>) => void;
  onKind: (kind: StepKind) => void;
  onRemove: () => void;
  onReorder: (dir: -1 | 1) => void;
}) {
  const metric = step.metric;
  return (
    <div className="rounded-[8px] border-[0.5px] border-border-subtle bg-surface px-3 py-2">
      {/* Column labels */}
      <div className="mb-1 flex items-end gap-2">
        <span className="caps-mono flex-1 min-w-0 text-[9px] tracking-[0.06em] text-tertiary">
          Step name
        </span>
        <span className="caps-mono w-28 shrink-0 text-[9px] tracking-[0.06em] text-tertiary">
          Type
        </span>
        {/* spacer for reorder+remove buttons */}
        <span className="w-8 shrink-0" />
      </div>
      <div className="flex items-center gap-2">
        <input
          value={step.title}
          onChange={(e) => onChange({ title: e.target.value })}
          placeholder="e.g. Bring LDL under 100"
          className={`${inputCls} flex-1 min-w-0`}
        />
        <select
          value={step.kind}
          onChange={(e) => onKind(e.target.value as StepKind)}
          className={`${inputCls} w-28 shrink-0`}
        >
          {STEP_KINDS.map((k) => (
            <option key={k} value={k}>
              {STEP_KIND_LABEL[k]}
            </option>
          ))}
        </select>
        <ReorderButtons
          onUp={() => onReorder(-1)}
          onDown={() => onReorder(1)}
          canUp={index > 0}
          canDown={index < total - 1}
        />
        <button
          type="button"
          onClick={onRemove}
          aria-label="Remove step"
          className="cursor-pointer rounded px-1 text-[12px] text-tertiary hover:text-alert"
        >
          ×
        </button>
      </div>

      {step.validationError ? <InlineError>{step.validationError}</InlineError> : null}

      {step.kind !== "MANUAL" && metric ? (
        <div className="mt-2 flex flex-wrap items-end gap-2 rounded bg-canvas px-2 py-2">
          <Field label="Metric">
            <select
              value={metric.metricKey}
              onChange={(e) =>
                onChange({ metric: { ...metric, metricKey: e.target.value } })
              }
              className={`${inputCls} w-40`}
            >
              {METRIC_REGISTRY.map((m) => (
                <option key={m.key} value={m.key}>
                  {m.label}
                </option>
              ))}
            </select>
          </Field>
          <Field label="Cmp">
            <select
              value={metric.comparator}
              onChange={(e) =>
                onChange({
                  metric: { ...metric, comparator: e.target.value as Comparator },
                })
              }
              className={`${inputCls} w-16`}
            >
              {COMPARATORS.map((c) => (
                <option key={c} value={c}>
                  {COMPARATOR_SYMBOL[c]}
                </option>
              ))}
            </select>
          </Field>
          <Field label="Target">
            <input
              type="number"
              value={metric.targetValue === "" ? "" : metric.targetValue}
              onChange={(e) =>
                onChange({
                  metric: {
                    ...metric,
                    targetValue: e.target.value === "" ? "" : Number(e.target.value),
                  },
                })
              }
              className={`${inputCls} w-20`}
            />
          </Field>
          {step.kind === "SUSTAINED" ? (
            <Field label="Window (days)">
              <input
                type="number"
                value={metric.windowDays == null || metric.windowDays === "" ? "" : metric.windowDays}
                onChange={(e) =>
                  onChange({
                    metric: {
                      ...metric,
                      windowDays: e.target.value === "" ? "" : Number(e.target.value),
                    },
                  })
                }
                className={`${inputCls} w-20`}
              />
            </Field>
          ) : null}
        </div>
      ) : null}

      {step.metricError ? <InlineError>{step.metricError}</InlineError> : null}
    </div>
  );
}

function ReorderButtons({
  onUp,
  onDown,
  canUp,
  canDown,
}: {
  onUp: () => void;
  onDown: () => void;
  canUp: boolean;
  canDown: boolean;
}) {
  return (
    <span className="flex flex-col leading-none">
      <button
        type="button"
        onClick={onUp}
        disabled={!canUp}
        aria-label="Move up"
        className="cursor-pointer px-1 text-[9px] text-tertiary hover:text-secondary disabled:cursor-default disabled:opacity-30"
      >
        ▲
      </button>
      <button
        type="button"
        onClick={onDown}
        disabled={!canDown}
        aria-label="Move down"
        className="cursor-pointer px-1 text-[9px] text-tertiary hover:text-secondary disabled:cursor-default disabled:opacity-30"
      >
        ▼
      </button>
    </span>
  );
}

function Field({
  label,
  children,
}: {
  label: string;
  children: React.ReactNode;
}) {
  return (
    <label className="block">
      <span className="caps-mono mb-1 block text-[9px] tracking-[0.06em] text-tertiary">
        {label}
      </span>
      {children}
    </label>
  );
}

function InlineError({ children }: { children: React.ReactNode }) {
  return (
    <div className="mt-1.5 rounded bg-alert-bg px-2 py-1 font-mono text-[10px] text-alert">
      {children}
    </div>
  );
}

// w-full is intentionally omitted here — callers add their own width
// (w-full, flex-1, w-28, w-40, etc.) so flex/grid containers resolve
// correctly without conflicting utility classes.
const inputCls =
  "rounded-md border-[0.5px] border-border-default bg-surface px-2.5 py-1.5 text-[13px] text-primary outline-none focus:border-accent";
