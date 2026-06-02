"use client";

import { useEffect, useRef, useState } from 'react';
import { useToast } from '@/components/ui/Toast';
import { DEMO_PHASES, DEMO_PHASE_LABEL } from '@/lib/types/exercise';
import type { DemoPhase } from '@/lib/types/exercise';

interface Props {
  exerciseId: string;
  exerciseName: string;
  isOpen: boolean;
  onClose: () => void;
  onStarted: () => void;
  // The default prompt is per-phase; we seed the textarea from the selected
  // phase's built default (GET .../demo-prompt?phase=).
  getPrompt: (exerciseId: string, phase: DemoPhase) => Promise<string>;
  regenerate: (
    exerciseId: string,
    promptOverride: string,
    phase: DemoPhase | null,
  ) => Promise<void>;
}

export function RegenerateMediaModal({
  exerciseId,
  exerciseName,
  isOpen,
  onClose,
  onStarted,
  getPrompt,
  regenerate,
}: Props) {
  const toast = useToast();
  const [phase, setPhase] = useState<DemoPhase>('START');
  // When true, regenerate ALL phases (using the per-phase defaults); the edited
  // override only applies to the selected phase.
  const [allPhases, setAllPhases] = useState(true);
  const [prompt, setPrompt] = useState('');
  const [isLoading, setIsLoading] = useState(false);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const downOnBackdropRef = useRef(false);

  function handleBackdropMouseDown(e: React.MouseEvent) {
    downOnBackdropRef.current = e.target === e.currentTarget;
  }

  function handleBackdropClick(e: React.MouseEvent) {
    const downOnBackdrop = downOnBackdropRef.current;
    downOnBackdropRef.current = false;
    if (downOnBackdrop && e.target === e.currentTarget) {
      onClose();
    }
  }

  // Seed the prompt from the selected phase's built default whenever the modal
  // opens or the phase changes.
  useEffect(() => {
    if (!isOpen) return;
    let cancelled = false;
    setIsLoading(true);
    getPrompt(exerciseId, phase)
      .then((p) => {
        if (!cancelled) setPrompt(p);
      })
      .catch(() => {
        if (!cancelled) setPrompt('');
      })
      .finally(() => {
        if (!cancelled) setIsLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [isOpen, exerciseId, phase, getPrompt]);

  if (!isOpen) return null;

  async function handleSubmit() {
    setIsSubmitting(true);
    try {
      // When regenerating all phases, pass phase=null so the backend rebuilds
      // each phase from its own default; the prompt override targets one phase.
      await regenerate(exerciseId, prompt, allPhases ? null : phase);
      toast.success(
        allPhases ? 'Regenerating all demo frames' : `Regenerating ${DEMO_PHASE_LABEL[phase]} frame`,
      );
      onStarted();
      onClose();
    } catch (e) {
      toast.error('Failed to start regeneration', {
        description: e instanceof Error ? e.message : 'Unknown error',
      });
    } finally {
      setIsSubmitting(false);
    }
  }

  return (
    <div
      className="fixed inset-0 z-[200] flex items-center justify-center bg-canvas/75 backdrop-blur-sm"
      onMouseDown={handleBackdropMouseDown}
      onClick={handleBackdropClick}
    >
      <div
        className="w-[680px] max-w-[92vw] max-h-[90vh] overflow-y-auto rounded-lg border border-border-default bg-surface p-6 shadow-[0_24px_64px_rgba(0,0,0,0.16)]"
        onMouseDown={(e) => e.stopPropagation()}
        onClick={(e) => e.stopPropagation()}
      >
        <h2 className="mb-1 text-xl font-semibold text-primary">Regenerate demo media</h2>
        <p className="mb-4 text-sm text-secondary">
          For <span className="font-medium text-primary">{exerciseName}</span>. The prompt is
          derived from the house photography treatment. Edit it before re-running for a different
          look.
        </p>

        <div className="mb-4 rounded-md border border-warn/40 bg-warn-bg px-3 py-2 text-xs text-warn">
          <i className="ti ti-alert-triangle mr-1" aria-hidden />
          Generated media lands as NEEDS_REVIEW. Check joint angles, grip, and anatomical
          correctness before approving — a wrong angle teaches an injurious movement.
        </div>

        <div className="mb-3 flex items-center gap-4">
          <label className="flex items-center gap-2 text-sm text-primary">
            <input
              type="checkbox"
              checked={allPhases}
              onChange={(e) => setAllPhases(e.target.checked)}
              className="cursor-pointer rounded border-border-default text-accent focus:ring-2 focus:ring-accent"
            />
            Regenerate all phases
          </label>

          <label className="flex items-center gap-2 text-sm text-secondary">
            Prompt phase
            <select
              value={phase}
              onChange={(e) => setPhase(e.target.value as DemoPhase)}
              className="rounded-md border border-border-default bg-canvas px-2 py-1 text-xs text-primary focus:outline-none focus:ring-2 focus:ring-accent"
            >
              {DEMO_PHASES.map((p) => (
                <option key={p} value={p}>
                  {DEMO_PHASE_LABEL[p]}
                </option>
              ))}
            </select>
          </label>
        </div>

        <label className="mb-1 block text-xs font-medium text-secondary">
          Prompt ({DEMO_PHASE_LABEL[phase]} phase)
        </label>
        <textarea
          value={prompt}
          onChange={(e) => setPrompt(e.target.value)}
          disabled={isLoading || isSubmitting}
          rows={14}
          className="w-full rounded-md border border-border-default bg-canvas px-3 py-2 font-mono text-xs text-primary focus:outline-none focus:ring-2 focus:ring-accent disabled:opacity-50"
        />

        <div className="mt-6 flex justify-end gap-2">
          <button
            onClick={onClose}
            disabled={isSubmitting}
            className="cursor-pointer rounded-md border border-border-default bg-canvas px-4 py-2 text-sm font-medium text-primary hover:bg-surface disabled:cursor-not-allowed disabled:opacity-50"
          >
            Cancel
          </button>
          <button
            onClick={handleSubmit}
            disabled={isLoading || isSubmitting || !prompt.trim()}
            className="cursor-pointer rounded-md bg-accent px-4 py-2 text-sm font-medium text-inverse hover:bg-accent/90 disabled:cursor-not-allowed disabled:opacity-50"
          >
            {isSubmitting ? 'Submitting…' : 'Regenerate'}
          </button>
        </div>
      </div>
    </div>
  );
}
