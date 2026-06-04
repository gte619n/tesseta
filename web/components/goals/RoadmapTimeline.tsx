"use client";

import { useState, useTransition } from "react";
import type {
  GoalDeepResponse,
  PhaseResponse,
  StepResponse,
} from "@/lib/types/goals";
import { metricMeta } from "@/lib/types/goals";
import { RangeIndicator } from "@/components/ui/RangeIndicator";
import { useToast } from "@/components/ui/Toast";
import { formatDateUpper } from "@/lib/format-date";

// Interactive roadmap timeline for a deep Goal. Server-component page
// fetches the goal and passes mutation server actions as props (the
// established blood/meds pattern); this client component owns only the
// interaction transitions and per-row pending state.

export type StepToggleAction = (args: {
  phaseId: string;
  stepId: string;
  done: boolean;
}) => Promise<void>;

export type StepResetAction = (args: {
  phaseId: string;
  stepId: string;
}) => Promise<void>;

type Props = {
  goal: GoalDeepResponse;
  // Resolved current metric values keyed by metricKey, for live readouts.
  // The deep response does not include them today, so this is best-effort;
  // missing keys render as "— → target".
  metricValues?: Record<string, number | null>;
  toggleStep: StepToggleAction;
  resetStep: StepResetAction;
};

function isPastDue(iso: string): boolean {
  const t = new Date(`${iso}T00:00:00`).getTime();
  return Date.now() > t;
}

export function RoadmapTimeline({
  goal,
  metricValues,
  toggleStep,
  resetStep,
}: Props) {
  return (
    <ol className="relative m-0 list-none space-y-0 p-0">
      {goal.phases.map((phase, i) => (
        <PhaseRow
          key={phase.phaseId}
          phase={phase}
          isLast={i === goal.phases.length - 1}
          metricValues={metricValues}
          toggleStep={toggleStep}
          resetStep={resetStep}
        />
      ))}
    </ol>
  );
}

function PhaseRow({
  phase,
  isLast,
  metricValues,
  toggleStep,
  resetStep,
}: {
  phase: PhaseResponse;
  isLast: boolean;
  metricValues?: Record<string, number | null>;
  toggleStep: StepToggleAction;
  resetStep: StepResetAction;
}) {
  const locked = phase.status === "LOCKED";
  const active = phase.status === "ACTIVE";
  const completed = phase.status === "COMPLETED";
  const behind = active && isPastDue(phase.targetEndDate);

  // Locked phases collapse their step list; user can still expand.
  const [expanded, setExpanded] = useState(!locked);

  const spineColor = completed || active ? "bg-accent" : "bg-border-default";
  const nodeClasses = completed
    ? "bg-accent border-accent"
    : active
      ? behind
        ? "bg-warn-bg border-warn"
        : "bg-surface border-accent"
      : "bg-canvas-muted border-border-default";

  return (
    <li className="relative flex gap-4 pb-6">
      {/* Left spine + node */}
      <div className="relative flex w-5 shrink-0 flex-col items-center">
        <span
          className={`z-10 mt-1 block rounded-full border-2 ${nodeClasses} ${
            active ? "h-4 w-4" : "h-3 w-3"
          }`}
          aria-hidden
        />
        {!isLast ? (
          <span
            className={`absolute top-5 bottom-[-24px] w-px ${spineColor}`}
            aria-hidden
          />
        ) : null}
      </div>

      {/* Phase panel */}
      <div
        className={`flex-1 rounded-[14px] border-[0.5px] px-5 py-4 ${
          behind
            ? "border-warn/40 bg-warn-bg/30"
            : "border-border-default bg-surface"
        } ${locked ? "opacity-60" : ""}`}
      >
        <div className="flex flex-wrap items-start justify-between gap-2">
          <div className="min-w-0">
            <h3 className="m-0 text-[15px] font-medium tracking-[-0.01em] text-primary">
              {phase.title}
            </h3>
            <div className="caps-mono mt-1 text-[10px] tracking-[0.06em] text-tertiary">
              {formatDateUpper(phase.targetStartDate)} – {formatDateUpper(phase.targetEndDate)}
            </div>
          </div>
          <div className="flex items-center gap-2">
            {behind ? (
              <span className="caps-mono rounded-[3px] bg-warn-bg px-1.5 py-px text-[9px] tracking-[0.06em] text-warn">
                Behind schedule
              </span>
            ) : null}
            <PhaseStatusPill status={phase.status} />
            {locked ? (
              <button
                type="button"
                onClick={() => setExpanded((v) => !v)}
                className="caps-mono cursor-pointer rounded-[3px] border-[0.5px] border-border-default px-1.5 py-px text-[9px] tracking-[0.06em] text-tertiary hover:text-secondary"
              >
                {expanded ? "Hide" : "Show"}
              </button>
            ) : null}
          </div>
        </div>

        {phase.description ? (
          <p className="mt-2 text-[13px] leading-[1.5] text-secondary">
            {phase.description}
          </p>
        ) : null}

        {expanded ? (
          <ul className="mt-3 space-y-0.5 border-t-[0.5px] border-border-subtle pt-2">
            {phase.steps.map((step) => (
              <StepRow
                key={step.stepId}
                step={step}
                phaseLocked={locked}
                metricValues={metricValues}
                toggleStep={toggleStep}
                resetStep={resetStep}
              />
            ))}
            {phase.steps.length === 0 ? (
              <li className="py-2 text-[12px] text-tertiary">No steps yet.</li>
            ) : null}
          </ul>
        ) : null}
      </div>
    </li>
  );
}

function PhaseStatusPill({ status }: { status: PhaseResponse["status"] }) {
  const map = {
    COMPLETED: { label: "Completed", cls: "bg-accent-bg text-accent-dim" },
    ACTIVE: { label: "Active", cls: "bg-good-bg text-accent-dim" },
    LOCKED: { label: "Locked", cls: "bg-canvas-muted text-tertiary" },
  } as const;
  const m = map[status];
  return (
    <span
      className={`caps-mono rounded-[3px] px-1.5 py-px text-[9px] tracking-[0.06em] ${m.cls}`}
    >
      {m.label}
    </span>
  );
}

function StepRow({
  step,
  phaseLocked,
  metricValues,
  toggleStep,
  resetStep,
}: {
  step: StepResponse;
  phaseLocked: boolean;
  metricValues?: Record<string, number | null>;
  toggleStep: StepToggleAction;
  resetStep: StepResetAction;
}) {
  const toast = useToast();
  const [pending, startTransition] = useTransition();

  const autoDone = step.done && !step.manualOverride && step.kind !== "MANUAL";
  const meta = step.metric ? metricMeta(step.metric.metricKey) : undefined;
  const current = step.metric
    ? metricValues?.[step.metric.metricKey] ?? null
    : null;

  function onToggle() {
    startTransition(async () => {
      try {
        await toggleStep({
          phaseId: step.phaseId,
          stepId: step.stepId,
          done: !step.done,
        });
      } catch {
        toast.error("Couldn't update step", {
          description: "The change wasn't saved. Try again.",
        });
      }
    });
  }

  function onReset() {
    startTransition(async () => {
      try {
        await resetStep({ phaseId: step.phaseId, stepId: step.stepId });
        toast.success("Reset to auto", {
          description: "Step will re-evaluate against live data.",
        });
      } catch {
        toast.error("Couldn't reset step");
      }
    });
  }

  return (
    <li className="flex items-start gap-2.5 py-1.5">
      <button
        type="button"
        onClick={onToggle}
        disabled={pending || phaseLocked}
        aria-pressed={step.done}
        aria-label={step.done ? "Mark not done" : "Mark done"}
        className={`mt-0.5 flex h-[18px] w-[18px] shrink-0 items-center justify-center rounded-[5px] border-[1.5px] transition-colors ${
          step.done
            ? "border-accent bg-accent text-inverse"
            : "border-border-default bg-surface hover:border-accent"
        } ${pending || phaseLocked ? "cursor-not-allowed opacity-60" : "cursor-pointer"}`}
      >
        {step.done ? (
          <svg viewBox="0 0 12 12" className="h-2.5 w-2.5" aria-hidden>
            <path
              d="M2.5 6.5L5 9L9.5 3.5"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.8"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          </svg>
        ) : null}
      </button>

      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <span
            className={`text-[13px] ${
              step.done ? "text-tertiary line-through" : "text-primary"
            }`}
          >
            {step.title}
          </span>
          {autoDone ? (
            <span className="caps-mono rounded-[3px] bg-accent-bg px-1 py-px text-[8px] tracking-[0.06em] text-accent-dim">
              Auto
            </span>
          ) : null}
          {step.metricRegressed ? (
            <span className="caps-mono rounded-[3px] bg-alert-bg px-1 py-px text-[8px] tracking-[0.06em] text-alert">
              Metric regressed
            </span>
          ) : null}
          {step.manualOverride && step.kind !== "MANUAL" ? (
            <button
              type="button"
              onClick={onReset}
              disabled={pending}
              className="caps-mono cursor-pointer rounded-[3px] border-[0.5px] border-border-default px-1 py-px text-[8px] tracking-[0.06em] text-tertiary hover:text-secondary disabled:opacity-60"
            >
              Reset to auto
            </button>
          ) : null}
        </div>

        {step.metric ? (
          <div className="mt-1">
            <RangeIndicator
              current={current}
              target={step.metric.targetValue}
              comparator={step.metric.comparator}
              unit={meta?.unit}
              regressed={step.metricRegressed === true}
            />
          </div>
        ) : null}
      </div>
    </li>
  );
}
