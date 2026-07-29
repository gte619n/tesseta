"use client";

import { useEffect, useMemo, useRef, useState } from 'react';
import { ModalBackdrop } from '@/components/ui/ModalBackdrop';
import { useToast } from '@/components/ui/Toast';
import { thumbUrl } from '@/lib/exercise-thumb';
import { collectCandidates } from './ReferencePicker';
import type { ExerciseResponse } from '@/lib/types/exercise';

// A frame the modal can target. Derived from the plan (preferred) or, for
// legacy exercises without a plan, the keyed demo frames.
export type RegenTarget = { key: string; label: string };

// Order-insensitive equality for two URL lists (grounding selections).
function sameUrlSet(a: string[], b: string[]): boolean {
  if (a.length !== b.length) return false;
  const set = new Set(b);
  return a.every((u) => set.has(u));
}

interface Props {
  exerciseId: string;
  exerciseName: string;
  // Targets to offer (one per planned/legacy frame). Empty ⇒ only "all".
  targets: RegenTarget[];
  isOpen: boolean;
  // Which frame to target when opened: "" = all frames; a key = that single
  // frame (prefills its editable prompt). Applied each time the modal opens.
  initialKey?: string;
  onClose: () => void;
  // Fired once the regen has been dispatched. The key is the frame that was
  // targeted ("" ⇒ all frames), so the drawer can scrobble only that frame.
  onStarted: (key: string) => void;
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
  // IMPL-20: full detail (when loaded) so the modal can show/select the
  // grounding images this run will use.
  exercise?: ExerciseResponse | null;
  // Upload a brand-new grounding photo from the modal (so an admin can add a
  // pose reference while dialing in one view, not just at the exercise entry).
  // Appends to the exercise's grounding set and returns the updated exercise.
  uploadGroundingImage?: (
    exerciseId: string,
    file: File,
  ) => Promise<ExerciseResponse>;
  // Push the updated exercise back to the host after an in-modal upload so the
  // grounding pool (and the rest of the drawer) reflect the new photo.
  onExerciseUpdated?: (ex: ExerciseResponse) => void;
}

export function RegenerateMediaModal({
  exerciseId,
  exerciseName,
  targets,
  isOpen,
  initialKey,
  onClose,
  onStarted,
  regenerate,
  getDemoPrompt,
  exercise,
  uploadGroundingImage,
  onExerciseUpdated,
}: Props) {
  const toast = useToast();
  // "" sentinel ⇒ all frames; otherwise a specific frame key.
  const [selectedKey, setSelectedKey] = useState<string>('');

  // Seed the target each time the modal opens (e.g. a per-frame Regenerate
  // button opens it pointed at that frame).
  useEffect(() => {
    if (isOpen) setSelectedKey(initialKey ?? '');
  }, [isOpen, initialKey]);
  const [prompt, setPrompt] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [isLoadingPrompt, setIsLoadingPrompt] = useState(false);
  // Composed prompt per view, for the "all frames" preview so the admin can see
  // exactly what each view will regenerate with (rather than an empty box).
  const [viewPrompts, setViewPrompts] = useState<
    { key: string; label: string; prompt: string }[]
  >([]);
  const [loadingViews, setLoadingViews] = useState(false);
  const targetKeysStr = targets.map((t) => t.key).join(',');

  // Which grounding images this run uses. Seeded from the exercise's persisted
  // set when the modal opens; toggling / uploading here overrides the set for
  // this regeneration only (sent as referenceImageUrls). The persisted set is
  // untouched unless the admin uploads a new photo (which appends to it).
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [groundingBusy, setGroundingBusy] = useState(false);
  const [dragOver, setDragOver] = useState(false);
  const groundingInputRef = useRef<HTMLInputElement>(null);

  // Seed the selection from the persisted grounding set each time the modal
  // opens. Keyed on open/exercise only so an in-modal upload (which mutates
  // groundingImageUrls) doesn't wipe manual toggles — the upload handler adds
  // the new URL to the selection explicitly.
  useEffect(() => {
    if (isOpen) setSelected(new Set(exercise?.groundingImageUrls ?? []));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isOpen, exerciseId]);

  // Every image the admin can ground on: the persisted grounding set (including
  // uploads, which aren't frame/reference candidates) plus own-frame / external
  // reference candidates, de-duplicated.
  const groundingPool = useMemo(() => {
    if (!exercise) return [] as { url: string; label: string }[];
    const seen = new Set<string>();
    const out: { url: string; label: string }[] = [];
    for (const url of exercise.groundingImageUrls ?? []) {
      if (url && !seen.has(url)) {
        seen.add(url);
        out.push({ url, label: 'saved' });
      }
    }
    for (const c of collectCandidates(exercise)) {
      if (!seen.has(c.url)) {
        seen.add(c.url);
        out.push({ url: c.url, label: c.group });
      }
    }
    return out;
  }, [exercise]);

  function toggleGrounding(url: string) {
    setSelected((cur) => {
      const next = new Set(cur);
      if (next.has(url)) next.delete(url);
      else next.add(url);
      return next;
    });
  }

  async function handleGroundingUpload(files: File[]) {
    if (!uploadGroundingImage || !exercise) return;
    const images = files.filter((f) => f.type.startsWith('image/'));
    if (images.length === 0) {
      toast.error('Unsupported file', {
        description: 'Please choose JPG, PNG, or WebP images.',
      });
      return;
    }
    setGroundingBusy(true);
    try {
      const prev = new Set(exercise.groundingImageUrls ?? []);
      let latest = exercise;
      for (const file of images) {
        latest = await uploadGroundingImage(exerciseId, file);
      }
      onExerciseUpdated?.(latest);
      // Auto-select whatever was just added so it's used for this run.
      const added = (latest.groundingImageUrls ?? []).filter((u) => !prev.has(u));
      setSelected((cur) => {
        const next = new Set(cur);
        for (const u of added) next.add(u);
        return next;
      });
      toast.success(
        images.length > 1
          ? `${images.length} grounding photos added`
          : 'Grounding photo added',
      );
    } catch (e) {
      toast.error('Upload failed', {
        description: e instanceof Error ? e.message : 'Please try again.',
      });
    } finally {
      setGroundingBusy(false);
    }
  }

  // When a single frame is targeted, seed the textarea with that frame's
  // composed prompt so admins see/edit the exact prompt.
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

  // For "all frames", fetch every view's composed prompt so the modal shows the
  // complete prompts rather than an empty override.
  useEffect(() => {
    if (!isOpen || selectedKey !== '' || targets.length === 0) {
      setViewPrompts([]);
      return;
    }
    let cancelled = false;
    setLoadingViews(true);
    Promise.all(
      targets.map(async (t) => ({
        key: t.key,
        label: t.label,
        prompt: await getDemoPrompt(exerciseId, t.key).catch(() => ''),
      })),
    )
      .then((res) => {
        if (!cancelled) setViewPrompts(res);
      })
      .finally(() => {
        if (!cancelled) setLoadingViews(false);
      });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isOpen, selectedKey, exerciseId, getDemoPrompt, targetKeysStr]);

  if (!isOpen) return null;

  const isAll = selectedKey === '';
  const selectedLabel = targets.find((t) => t.key === selectedKey)?.label ?? selectedKey;

  async function handleSubmit() {
    setIsSubmitting(true);
    try {
      // Only override the grounding set when the admin actually changed it from
      // the persisted selection — otherwise omit referenceImageUrls so the
      // backend keeps its "use the saved set (with reference fallback)" default.
      const persisted = exercise?.groundingImageUrls ?? [];
      const changed =
        !!exercise && !sameUrlSet([...selected], persisted);
      await regenerate(
        exerciseId,
        prompt.trim() ? prompt.trim() : null,
        isAll ? null : selectedKey,
        changed ? [...selected] : undefined,
      );
      toast.success(
        isAll ? 'Regenerating all demo frames' : `Regenerating ${selectedLabel} frame`,
      );
      onStarted(isAll ? '' : selectedKey);
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
        generated from its plan position prompt and the house photography treatment. Review the
        per-view prompts below, or target a single frame to edit its prompt before regenerating.
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

      {isAll ? (
        <>
          <label className="mb-1 block text-xs font-medium text-secondary">
            Prompts per view
            {loadingViews ? (
              <span className="ml-2 text-tertiary">Loading…</span>
            ) : null}
          </label>
          <div className="space-y-1.5">
            {viewPrompts.length === 0 && !loadingViews ? (
              <p className="text-xs text-tertiary">
                No planned views yet — each frame will use its own position
                prompt.
              </p>
            ) : (
              viewPrompts.map((v, i) => (
                <details
                  key={v.key}
                  open={i === 0}
                  className="rounded-md border border-border-default bg-canvas"
                >
                  <summary className="cursor-pointer px-2.5 py-1.5 text-xs font-medium text-primary">
                    {v.label}{' '}
                    <span className="font-normal text-tertiary">({v.key})</span>
                  </summary>
                  <textarea
                    readOnly
                    value={v.prompt}
                    rows={8}
                    className="w-full resize-y rounded-b-md border-t border-border-default bg-surface px-3 py-2 font-mono text-[11px] text-secondary focus:outline-none"
                  />
                </details>
              ))
            )}
          </div>
          <p className="mt-1.5 text-[11px] text-tertiary">
            These are the exact prompts each view will regenerate with. To tweak
            one, select it in <span className="font-medium">Target frame</span>{' '}
            above and edit it there.
          </p>
        </>
      ) : (
        <>
          <label className="mb-1 block text-xs font-medium text-secondary">
            Prompt
            {isLoadingPrompt ? (
              <span className="ml-2 text-tertiary">Loading prompt…</span>
            ) : null}
          </label>
          <textarea
            value={prompt}
            onChange={(e) => setPrompt(e.target.value)}
            disabled={isSubmitting || isLoadingPrompt}
            rows={10}
            placeholder="The composed prompt for this frame. Edit before regenerating."
            className="w-full rounded-md border border-border-default bg-canvas px-3 py-2 font-mono text-xs text-primary focus:outline-none focus:ring-2 focus:ring-accent disabled:opacity-50"
          />
        </>
      )}

      {exercise ? (
        <div className="mt-4 rounded-md border border-border-default bg-canvas p-3">
          <p className="mb-2 text-xs font-medium text-secondary">
            Grounding images
            <span className="ml-1 font-normal text-tertiary">
              —{' '}
              {isAll
                ? 'applied to every frame this run'
                : `pose reference for the ${selectedLabel} view`}
              . Toggle which apply to this regeneration; the saved set is left
              untouched unless you upload a new photo.
            </span>
          </p>

          {uploadGroundingImage ? (
            <>
              <div
                role="button"
                tabIndex={0}
                onClick={() => !groundingBusy && groundingInputRef.current?.click()}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault();
                    if (!groundingBusy) groundingInputRef.current?.click();
                  }
                }}
                onDragOver={(e) => {
                  e.preventDefault();
                  if (!dragOver) setDragOver(true);
                }}
                onDragLeave={(e) => {
                  e.preventDefault();
                  setDragOver(false);
                }}
                onDrop={(e) => {
                  e.preventDefault();
                  setDragOver(false);
                  if (groundingBusy) return;
                  const files = Array.from(e.dataTransfer.files ?? []);
                  if (files.length > 0) void handleGroundingUpload(files);
                }}
                className={
                  'mb-2 flex cursor-pointer items-center justify-center rounded-md border border-dashed px-3 py-3 text-center text-[11px] ' +
                  (dragOver
                    ? 'border-accent bg-accent/5 text-primary'
                    : 'border-border-strong bg-surface text-tertiary hover:text-secondary')
                }
              >
                {groundingBusy
                  ? 'Working…'
                  : dragOver
                    ? 'Drop to upload'
                    : 'Drop or click to add a grounding photo · JPG, PNG, WebP'}
              </div>
              <input
                ref={groundingInputRef}
                type="file"
                accept="image/*"
                multiple
                className="hidden"
                onChange={(e) => {
                  const files = Array.from(e.target.files ?? []);
                  e.target.value = '';
                  if (files.length > 0) void handleGroundingUpload(files);
                }}
              />
            </>
          ) : null}

          {groundingPool.length > 0 ? (
            <div className="flex flex-wrap gap-2">
              {groundingPool.map(({ url, label }) => {
                const on = selected.has(url);
                return (
                  <button
                    key={url}
                    type="button"
                    onClick={() => toggleGrounding(url)}
                    aria-pressed={on}
                    title={label}
                    className={
                      'relative block h-20 w-16 overflow-hidden rounded border p-0 ' +
                      (on
                        ? 'border-accent ring-2 ring-accent'
                        : 'border-border-default opacity-60 hover:opacity-100')
                    }
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
                    {on ? (
                      <span className="absolute right-0.5 top-0.5 flex h-4 w-4 items-center justify-center rounded-full bg-accent text-[10px] leading-none text-inverse">
                        ✓
                      </span>
                    ) : null}
                  </button>
                );
              })}
            </div>
          ) : (
            <p className="text-[11px] text-tertiary">
              No grounding images yet — add one above.
            </p>
          )}

          <p className="mt-1.5 text-[11px] text-tertiary">
            {selected.size} selected.{' '}
            {selected.size === 0
              ? 'No pose reference will be attached.'
              : 'One reference is attached per view (mapped by position).'}
          </p>
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
