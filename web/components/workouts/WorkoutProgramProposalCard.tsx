"use client";

import { useMemo, useState, useTransition } from "react";
import type {
  WorkoutProgramDeepResponse,
  PrescriptionExercise,
} from "@/lib/types/workout-program";
import { WEEK_DAY_LABEL } from "@/lib/types/workout-program";
import { BLOCK_TYPE_LABEL } from "@/lib/types/exercise";
import { formatPrescription } from "@/lib/workout-format";
import { useToast } from "@/components/ui/Toast";
import { ExerciseDetailSheet } from "./ExerciseDetailSheet";

export type ProgramProposalDraft = {
  title: string;
  description: string;
  // The full proposed structure, kept as-is for commit. Title/description are
  // overlaid from the editable fields at save time.
  proposal: WorkoutProgramDeepResponse;
  issues: string[];
};

type Props = {
  initialValue: ProgramProposalDraft;
  // Commit the edited proposal. Resolves on success; throws on validation
  // failure so this card can keep showing the (re-flagged) issues.
  onSave: (draft: ProgramProposalDraft) => Promise<void>;
  onDiscard?: () => void;
  heading?: string;
  saveLabel?: string;
};

export function WorkoutProgramProposalCard({
  initialValue,
  onSave,
  onDiscard,
  heading = "Proposed program",
  saveLabel = "Save program",
}: Props) {
  const toast = useToast();
  const seed = useMemo(() => initialValue, [initialValue]);
  const [title, setTitle] = useState(seed.title);
  const [description, setDescription] = useState(seed.description);
  const [pending, startTransition] = useTransition();
  const [sheetExercise, setSheetExercise] = useState<PrescriptionExercise | null>(null);

  const proposal = seed.proposal;
  const issues = seed.issues;

  function handleSave() {
    if (!title.trim()) {
      toast.error("Can't save yet", { description: "The program needs a title." });
      return;
    }
    startTransition(async () => {
      try {
        await onSave({
          title: title.trim(),
          description: description.trim(),
          proposal: { ...proposal, title: title.trim(), description: description.trim() },
          issues,
        });
      } catch {
        // The host surfaces the toast and re-flags issues; nothing to do here.
      }
    });
  }

  return (
    <div className="rounded-[14px] border-[0.5px] border-border-default bg-surface">
      <div className="flex items-center justify-between border-b-[0.5px] border-border-subtle px-5 py-3">
        <h2 className="m-0 text-[14px] font-medium text-primary">{heading}</h2>
        <span className="caps-mono text-[9px] tracking-[0.06em] text-tertiary">
          {proposal.phases.length} phase{proposal.phases.length === 1 ? "" : "s"}
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

        <label className="block">
          <span className="caps-mono mb-1 block text-[9px] tracking-[0.06em] text-tertiary">
            Title
          </span>
          <input
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            className="w-full rounded-md border-[0.5px] border-border-default bg-surface px-2.5 py-1.5 text-[13px] text-primary outline-none focus:border-accent"
          />
        </label>
        <label className="block">
          <span className="caps-mono mb-1 block text-[9px] tracking-[0.06em] text-tertiary">
            Description
          </span>
          <textarea
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            rows={2}
            className="w-full rounded-md border-[0.5px] border-border-default bg-surface px-2.5 py-1.5 text-[13px] text-primary outline-none focus:border-accent"
          />
        </label>

        {/* Read-only structural preview of phases → days → blocks. */}
        <div className="space-y-3">
          {proposal.phases.map((phase) => (
            <div
              key={phase.phaseId}
              className="rounded-[10px] border-[0.5px] border-border-default bg-canvas px-4 py-3"
            >
              <div className="flex flex-wrap items-center gap-2">
                <span className="text-[14px] font-medium text-primary">{phase.title}</span>
                <span className="caps-mono text-[10px] tracking-[0.06em] text-tertiary">
                  {phase.weeks} wk{phase.weeks === 1 ? "" : "s"}
                </span>
                {phase.deloadWeekIndex != null ? (
                  <span className="caps-mono rounded-[3px] bg-warn-bg px-1.5 py-px text-[9px] tracking-[0.06em] text-warn">
                    Deload wk {phase.deloadWeekIndex}
                  </span>
                ) : null}
              </div>
              {phase.focus ? (
                <p className="mt-0.5 text-[12px] text-secondary">{phase.focus}</p>
              ) : null}

              <div className="mt-2 space-y-2">
                {phase.days.map((day) => (
                  <div
                    key={day.dayId}
                    className="rounded-md border-[0.5px] border-border-subtle bg-surface px-3 py-2"
                  >
                    <div className="flex items-center gap-2">
                      <span className="text-[13px] font-medium text-primary">{day.label}</span>
                      <span className="caps-mono text-[10px] tracking-[0.06em] text-tertiary">
                        {WEEK_DAY_LABEL[day.dayOfWeek]} · {day.locationName}
                      </span>
                    </div>
                    <div className="mt-1.5 space-y-1.5">
                      {day.blocks.map((block) => (
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
                              const flagged = !!p.validationError;
                              return (
                                <li key={i}>
                                  <button
                                    type="button"
                                    disabled={!ex}
                                    onClick={() => ex && setSheetExercise(ex)}
                                    className={`flex w-full items-start justify-between gap-3 rounded px-2 py-1 text-left ${
                                      flagged
                                        ? "border-[0.5px] border-alert/50 bg-alert-bg"
                                        : ex
                                          ? "cursor-pointer hover:bg-canvas-muted"
                                          : ""
                                    }`}
                                  >
                                    <span className="min-w-0 flex-1">
                                      <span className="text-[13px] text-primary">
                                        {ex?.name ?? p.exerciseId}
                                      </span>
                                      <span className="ml-2 text-[12px] text-tertiary">
                                        {formatPrescription(p)}
                                      </span>
                                      {flagged ? (
                                        <span className="mt-0.5 block font-mono text-[10px] text-alert">
                                          {p.validationError}
                                        </span>
                                      ) : null}
                                    </span>
                                  </button>
                                </li>
                              );
                            })}
                          </ul>
                        </div>
                      ))}
                    </div>
                  </div>
                ))}
              </div>
            </div>
          ))}
        </div>
      </div>

      <div className="flex items-center justify-end gap-2 border-t-[0.5px] border-border-subtle px-5 py-3">
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
          {pending ? "Saving…" : saveLabel}
        </button>
      </div>

      <ExerciseDetailSheet exercise={sheetExercise} onClose={() => setSheetExercise(null)} />
    </div>
  );
}
