"use client";

import { useEffect, useState } from 'react';
import { ModalBackdrop } from '@/components/ui/ModalBackdrop';
import { useToast } from '@/components/ui/Toast';
import { thumbUrl } from '@/lib/exercise-thumb';
import type { ExerciseResponse } from '@/lib/types/exercise';

// A frame the modal can target. Derived from the plan (preferred) or, for
// legacy exercises without a plan, the keyed demo frames.
export type RegenTarget = { key: string; label: string };

interface Props {
  exerciseId: string;
  exerciseName: string;
  // Targets to offer (one per planned/legacy frame). Empty ⇒ only "all".
  targets: RegenTarget[];
  isOpen: boolean;
  onClose: () => void;
  onStarted: () => void;
  // key == null regenerates every frame; a key regenerates that one frame. The
  // optional prompt override is applied to whichever target is selected.
  // IMPL-20: `referenceImageUrls` overrides the persisted grounding set for the
  // run; undefined ⇒ backend uses the persisted set.
  regenerate: (
    exerciseId: string,
    promptOverride: string | null,
    key: string | null,
    referenceImageUrls?: string[],
  ) => Promise<void>;
  // IMPL-19: fetch the composed image prompt for one frame key, so admins can
  // see/edit the exact prompt before regenerating a single frame.
  getDemoPrompt: (exerciseId: string, key: string) => Promise<string>;
  // IMPL-20: full detail (when loaded) so the modal can preview the saved
  // grounding set that this run will use. Edit the set in the exercise editor's
  // grounding gallery; the regen always grounds on the persisted set.
  exercise?: ExerciseResponse | null;
}

export function RegenerateMediaModal({
  exerciseId,
  exerciseName,
  targets,
  isOpen,
  onClose,
  onStarted,
  regenerate,
  getDemoPrompt,
  exercise,
}: Props) {
  const toast = useToast();
  // "" sentinel ⇒ all frames; otherwise a specific frame key.
  const [selectedKey, setSelectedKey] = useState<string>('');
  const [prompt, setPrompt] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [isLoadingPrompt, setIsLoadingPrompt] = useState(false);

  // When a single frame is targeted, seed the textarea with that frame's
  // composed prompt so admins see/edit the exact prompt. For "all frames" we
  // keep the blank-override behavior (each frame uses its own position prompt).
  useEffect(() => {
    if (!isOpen) return;
    if (selectedKey === '') {
      setPrompt('');
      return;
    }
    let cancelled = false;
    setIsLoadingPrompt(true);
    getDemoPrompt(exerciseId, selectedKey)
      .then((p) => {
        if (!cancelled) setPrompt(p);
      })
      .catch(() => {
        if (!cancelled) setPrompt('');
      })
      .finally(() => {
        if (!cancelled) setIsLoadingPrompt(false);
      });
    return () => {
      cancelled = true;
    };
  }, [isOpen, selectedKey, exerciseId, getDemoPrompt]);

  if (!isOpen) return null;

  const isAll = selectedKey === '';
  const selectedLabel = targets.find((t) => t.key === selectedKey)?.label ?? selectedKey;

  async function handleSubmit() {
    setIsSubmitting(true);
    try {
      // Always ground on the exercise's persisted set (curated in the editor's
      // grounding gallery) — omit referenceImageUrls so the backend uses it.
      await regenerate(
        exerciseId,
        prompt.trim() ? prompt.trim() : null,
        isAll ? null : selectedKey,
      );
      toast.success(
        isAll ? 'Regenerating all demo frames' : `Regenerating ${selectedLabel} frame`,
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
    <ModalBackdrop
      onClose={onClose}
      contentClassName="w-[680px] max-w-[92vw] max-h-[90vh] overflow-y-auto rounded-lg border border-border-default bg-surface p-6 shadow-[0_24px_64px_rgba(0,0,0,0.16)]"
    >
      <h2 className="mb-1 text-xl font-semibold text-primary">Regenerate demo media</h2>
      <p className="mb-4 text-sm text-secondary">
        For <span className="font-medium text-primary">{exerciseName}</span>. Each frame is
        generated from its plan position prompt and the house photography treatment. Add an
        optional override below to nudge a specific look.
      </p>

      <div className="mb-4 rounded-md border border-warn/40 bg-warn-bg px-3 py-2 text-xs text-warn">
        <i className="ti ti-alert-triangle mr-1" aria-hidden />
        Generated media lands as NEEDS_REVIEW. Check joint angles, grip, and anatomical
        correctness before approving — a wrong angle teaches an injurious movement.
      </div>

      <label className="mb-1 block text-xs font-medium text-secondary">Target frame</label>
      <select
        value={selectedKey}
        onChange={(e) => setSelectedKey(e.target.value)}
        className="mb-4 w-full rounded-md border border-border-default bg-canvas px-2 py-2 text-sm text-primary focus:outline-none focus:ring-2 focus:ring-accent"
      >
        <option value="">All frames</option>
        {targets.map((t) => (
          <option key={t.key} value={t.key}>
            {t.label} ({t.key})
          </option>
        ))}
      </select>

      <label className="mb-1 block text-xs font-medium text-secondary">
        {isAll ? 'Prompt override (optional)' : 'Prompt'}
        {isLoadingPrompt ? (
          <span className="ml-2 text-tertiary">Loading prompt…</span>
        ) : null}
      </label>
      <textarea
        value={prompt}
        onChange={(e) => setPrompt(e.target.value)}
        disabled={isSubmitting || isLoadingPrompt}
        rows={10}
        placeholder={
          isAll
            ? "Leave blank to use the plan's position prompt for each frame."
            : "The composed prompt for this frame. Edit before regenerating."
        }
        className="w-full rounded-md border border-border-default bg-canvas px-3 py-2 font-mono text-xs text-primary focus:outline-none focus:ring-2 focus:ring-accent disabled:opacity-50"
      />

      {exercise ? (
        <div className="mt-4 rounded-md border border-border-default bg-canvas p-3">
          <p className="mb-2 text-xs font-medium text-secondary">
            Pose references
            <span className="ml-1 font-normal text-tertiary">
              — this run grounds on the exercise&rsquo;s saved grounding set. Edit
              it in the exercise editor&rsquo;s grounding gallery.
            </span>
          </p>
          {exercise.groundingImageUrls.length > 0 ? (
            <div className="flex flex-wrap gap-2">
              {exercise.groundingImageUrls.map((url) => (
                <div
                  key={url}
                  className="h-20 w-16 overflow-hidden rounded border border-border-default"
                >
                  {/* eslint-disable-next-line @next/next/no-img-element */}
                  <img
                    src={thumbUrl(url)}
                    alt="grounding reference"
                    loading="lazy"
                    onError={(e) => {
                      const img = e.currentTarget;
                      if (img.src !== url) img.src = url;
                    }}
                    className="h-full w-full object-cover"
                  />
                </div>
              ))}
            </div>
          ) : (
            <p className="text-[11px] text-tertiary">
              No saved grounding photos — regeneration falls back to reference
              images.
            </p>
          )}
        </div>
      ) : null}

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
          disabled={isSubmitting || isLoadingPrompt}
          className="cursor-pointer rounded-md bg-accent px-4 py-2 text-sm font-medium text-inverse hover:bg-accent/90 disabled:cursor-not-allowed disabled:opacity-50"
        >
          {isSubmitting ? 'Submitting…' : 'Regenerate'}
        </button>
      </div>
    </ModalBackdrop>
  );
}
