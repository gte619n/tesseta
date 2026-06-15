"use client";

import Image from 'next/image';
import { useRef, useState } from 'react';
import { useConfirm } from '@/components/ui/ConfirmDialog';
import { useToast } from '@/components/ui/Toast';
import { ImageLightbox } from './ImageLightbox';
import type {
  DemoFrame,
  ExerciseMediaStatus,
  FrameSpec,
} from '@/lib/types/exercise';

// One rendered row of the media grid: the plan spec (what to teach) joined
// with whatever DemoFrame images exist for that key. When there is no plan we
// synthesize specs from the legacy frames so old exercises still render.
type RenderFrame = {
  key: string;
  label: string;
  caption: string;
  order: number;
  imageUrl: string | null;
  imageCandidates: string[];
};

interface Props {
  exerciseId: string;
  exerciseName: string;
  demoPlan: FrameSpec[] | null;
  planStatus: ExerciseMediaStatus;
  frames: DemoFrame[];
  mediaStatus: ExerciseMediaStatus;
  // Plan editor actions (IMPL-19).
  regeneratePlan: (exerciseId: string, promptOverride?: string) => Promise<void>;
  savePlan: (exerciseId: string, frames: FrameSpec[]) => Promise<void>;
  approvePlan: (exerciseId: string) => Promise<void>;
  // Per-frame media actions, keyed to the plan.
  regenerateFrame: (exerciseId: string, key: string) => Promise<void>;
  uploadFrame: (exerciseId: string, key: string, file: File) => Promise<void>;
  selectFrame: (exerciseId: string, key: string, imageUrl: string) => Promise<void>;
  deleteFrame: (exerciseId: string, key: string, imageUrl: string) => Promise<void>;
}

const PLAN_STATUS_LABEL: Record<ExerciseMediaStatus, string> = {
  NONE: 'No plan',
  PENDING: 'Planning…',
  NEEDS_REVIEW: 'Needs review',
  APPROVED: 'Approved',
  FAILED: 'Failed',
};

const PLAN_STATUS_TONE: Record<ExerciseMediaStatus, string> = {
  NONE: 'border-border-default text-tertiary',
  PENDING: 'border-border-default text-secondary',
  NEEDS_REVIEW: 'border-warn/40 bg-warn-bg text-warn',
  APPROVED: 'border-green-600/40 bg-green-600/10 text-green-700',
  FAILED: 'border-red-600/40 bg-red-600/10 text-red-700',
};

function slugify(input: string): string {
  return input
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 40);
}

// Join the plan with the generated frames. The plan is the source of truth for
// which rows exist, their order, labels, and captions; the frames carry images.
function joinFrames(
  demoPlan: FrameSpec[] | null,
  frames: DemoFrame[],
): RenderFrame[] {
  const frameByKey = new Map(frames.map((f) => [f.key, f]));
  if (demoPlan && demoPlan.length > 0) {
    return [...demoPlan]
      .sort((a, b) => a.order - b.order)
      .map((spec) => {
        const f = frameByKey.get(spec.key);
        return {
          key: spec.key,
          label: spec.label || spec.key,
          caption: spec.caption,
          order: spec.order,
          imageUrl: f?.imageUrl ?? null,
          imageCandidates: f?.imageCandidates ?? [],
        };
      });
  }
  // Legacy: no plan — render whatever frames exist, in their stored order.
  return [...frames]
    .sort((a, b) => a.order - b.order)
    .map((f) => ({
      key: f.key,
      label: f.label || f.key,
      caption: f.caption ?? '',
      order: f.order,
      imageUrl: f.imageUrl,
      imageCandidates: f.imageCandidates,
    }));
}

// Renders the N planned demo frames keyed to the plan, plus an editable plan
// list (label / caption / positionPrompt, add / remove / reorder) and the
// plan-level Regenerate / Approve actions.
export function ExerciseDemoFrames({
  exerciseId,
  exerciseName,
  demoPlan,
  planStatus,
  frames,
  mediaStatus,
  regeneratePlan,
  savePlan,
  approvePlan,
  regenerateFrame,
  uploadFrame,
  selectFrame,
  deleteFrame,
}: Props) {
  const [lightboxSrc, setLightboxSrc] = useState<string | null>(null);
  const renderFrames = joinFrames(demoPlan, frames);

  return (
    <>
      <PlanEditor
        exerciseId={exerciseId}
        demoPlan={demoPlan}
        planStatus={planStatus}
        regeneratePlan={regeneratePlan}
        savePlan={savePlan}
        approvePlan={approvePlan}
      />

      {renderFrames.length > 0 ? (
        <div className="mt-4 grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5">
          {renderFrames.map((frame) => (
            <KeyedFrame
              key={frame.key}
              exerciseId={exerciseId}
              exerciseName={exerciseName}
              frame={frame}
              mediaStatus={mediaStatus}
              onZoom={(src) => setLightboxSrc(src)}
              regenerateFrame={regenerateFrame}
              uploadFrame={uploadFrame}
              selectFrame={selectFrame}
              deleteFrame={deleteFrame}
            />
          ))}
        </div>
      ) : (
        <p className="mt-4 text-xs text-tertiary">
          No frames yet. {demoPlan ? 'Generate media once the plan is approved.' : 'Generate a plan to define the frames for this exercise.'}
        </p>
      )}

      <ImageLightbox
        src={lightboxSrc}
        alt={exerciseName}
        onClose={() => setLightboxSrc(null)}
      />
    </>
  );
}

// ── Plan editor ──────────────────────────────────────────────────────

function PlanEditor({
  exerciseId,
  demoPlan,
  planStatus,
  regeneratePlan,
  savePlan,
  approvePlan,
}: {
  exerciseId: string;
  demoPlan: FrameSpec[] | null;
  planStatus: ExerciseMediaStatus;
  regeneratePlan: (exerciseId: string, promptOverride?: string) => Promise<void>;
  savePlan: (exerciseId: string, frames: FrameSpec[]) => Promise<void>;
  approvePlan: (exerciseId: string) => Promise<void>;
}) {
  const toast = useToast();
  const confirm = useConfirm();
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  // Local working copy of the plan while editing.
  const [draft, setDraft] = useState<FrameSpec[]>(
    () => (demoPlan ? [...demoPlan].sort((a, b) => a.order - b.order) : []),
  );

  const planCount = demoPlan?.length ?? 0;

  function resetDraft() {
    setDraft(demoPlan ? [...demoPlan].sort((a, b) => a.order - b.order) : []);
  }

  function reorder(next: FrameSpec[]): FrameSpec[] {
    return next.map((f, i) => ({ ...f, order: i }));
  }

  function updateFrame(idx: number, patch: Partial<FrameSpec>) {
    setDraft((cur) => cur.map((f, i) => (i === idx ? { ...f, ...patch } : f)));
  }

  function addFrame() {
    setDraft((cur) =>
      reorder([
        ...cur,
        {
          key: `p${cur.length + 1}`,
          order: cur.length,
          label: `Position ${cur.length + 1}`,
          caption: '',
          positionPrompt: '',
        },
      ]),
    );
  }

  function removeFrame(idx: number) {
    setDraft((cur) => reorder(cur.filter((_, i) => i !== idx)));
  }

  function move(idx: number, dir: -1 | 1) {
    setDraft((cur) => {
      const next = [...cur];
      const target = idx + dir;
      if (target < 0 || target >= next.length) return cur;
      const a = next[idx];
      const b = next[target];
      if (!a || !b) return cur;
      next[idx] = b;
      next[target] = a;
      return reorder(next);
    });
  }

  async function handleRegenerate() {
    setBusy(true);
    try {
      await regeneratePlan(exerciseId);
      toast.success('Regenerating frame plan');
    } catch (err) {
      toast.error('Failed to regenerate plan', {
        description: err instanceof Error ? err.message : 'Unknown error',
      });
    } finally {
      setBusy(false);
    }
  }

  async function handleSave() {
    // Normalize keys: slugify labels for blank keys, dedupe, reindex order.
    const seen = new Set<string>();
    const normalized = reorder(draft).map((f, i) => {
      let key = (f.key || slugify(f.label) || `p${i + 1}`).trim();
      if (!key) key = `p${i + 1}`;
      let unique = key;
      let n = 2;
      while (seen.has(unique)) unique = `${key}-${n++}`;
      seen.add(unique);
      return { ...f, key: unique };
    });
    if (normalized.length === 0) {
      toast.error('A plan needs at least one frame');
      return;
    }
    setBusy(true);
    try {
      await savePlan(exerciseId, normalized);
      toast.success('Plan saved');
      setOpen(false);
    } catch (err) {
      toast.error('Failed to save plan', {
        description: err instanceof Error ? err.message : 'Unknown error',
      });
    } finally {
      setBusy(false);
    }
  }

  async function handleApprove() {
    const ok = await confirm({
      title: 'Approve plan',
      description: 'Mark this frame plan as approved? Approved plans gate media generation.',
      confirmLabel: 'Approve',
    });
    if (!ok) return;
    setBusy(true);
    try {
      await approvePlan(exerciseId);
      toast.success('Plan approved');
    } catch (err) {
      toast.error('Failed to approve plan', {
        description: err instanceof Error ? err.message : 'Unknown error',
      });
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="rounded-md border border-border-default bg-canvas p-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <span className="caps-mono text-[9px] tracking-[0.06em] text-tertiary">Frame plan</span>
          <span
            className={
              'rounded-full border px-2 py-0.5 text-[10px] font-medium ' +
              PLAN_STATUS_TONE[planStatus]
            }
          >
            {PLAN_STATUS_LABEL[planStatus]}
          </span>
          <span className="text-[11px] text-tertiary">
            {planCount} frame{planCount === 1 ? '' : 's'}
          </span>
        </div>
        <div className="flex items-center gap-1.5">
          <button
            type="button"
            onClick={handleRegenerate}
            disabled={busy}
            className="cursor-pointer rounded border border-border-default bg-surface px-2 py-1 text-[11px] font-medium text-primary hover:bg-canvas disabled:cursor-not-allowed disabled:opacity-50"
          >
            {demoPlan ? 'Regenerate plan' : 'Generate plan'}
          </button>
          <button
            type="button"
            onClick={() => {
              if (!open) resetDraft();
              setOpen((v) => !v);
            }}
            disabled={busy}
            className="cursor-pointer rounded border border-border-default bg-surface px-2 py-1 text-[11px] font-medium text-primary hover:bg-canvas disabled:cursor-not-allowed disabled:opacity-50"
          >
            {open ? 'Close editor' : 'Edit plan'}
          </button>
          <button
            type="button"
            onClick={handleApprove}
            disabled={busy || planStatus !== 'NEEDS_REVIEW'}
            className="cursor-pointer rounded bg-green-600 px-2 py-1 text-[11px] font-medium text-white hover:bg-green-700 disabled:cursor-not-allowed disabled:opacity-50"
          >
            Approve plan
          </button>
        </div>
      </div>

      {open ? (
        <div className="mt-3 space-y-2">
          {draft.length === 0 ? (
            <p className="text-[11px] text-tertiary">
              No frames. Add one below or run the planner.
            </p>
          ) : (
            draft.map((f, idx) => (
              <div
                key={idx}
                className="rounded border border-border-default bg-surface p-2"
              >
                <div className="flex items-center gap-2">
                  <span className="caps-mono w-5 text-center text-[9px] text-tertiary">
                    {idx + 1}
                  </span>
                  <input
                    value={f.label}
                    onChange={(e) => updateFrame(idx, { label: e.target.value })}
                    placeholder="Label"
                    className="w-32 rounded border border-border-default bg-canvas px-2 py-1 text-xs text-primary focus:outline-none focus:ring-1 focus:ring-accent"
                  />
                  <input
                    value={f.caption}
                    onChange={(e) => updateFrame(idx, { caption: e.target.value })}
                    placeholder="Teaching cue (caption)"
                    className="flex-1 rounded border border-border-default bg-canvas px-2 py-1 text-xs text-primary focus:outline-none focus:ring-1 focus:ring-accent"
                  />
                  <div className="flex items-center gap-0.5">
                    <button
                      type="button"
                      onClick={() => move(idx, -1)}
                      disabled={idx === 0}
                      aria-label="Move up"
                      className="cursor-pointer rounded border border-border-default px-1.5 py-1 text-[11px] text-primary hover:bg-canvas disabled:cursor-not-allowed disabled:opacity-30"
                    >
                      ↑
                    </button>
                    <button
                      type="button"
                      onClick={() => move(idx, 1)}
                      disabled={idx === draft.length - 1}
                      aria-label="Move down"
                      className="cursor-pointer rounded border border-border-default px-1.5 py-1 text-[11px] text-primary hover:bg-canvas disabled:cursor-not-allowed disabled:opacity-30"
                    >
                      ↓
                    </button>
                    <button
                      type="button"
                      onClick={() => removeFrame(idx)}
                      aria-label="Remove frame"
                      className="cursor-pointer rounded border border-red-600/40 px-1.5 py-1 text-[11px] text-red-600 hover:bg-red-600/10"
                    >
                      ×
                    </button>
                  </div>
                </div>
                <textarea
                  value={f.positionPrompt}
                  onChange={(e) => updateFrame(idx, { positionPrompt: e.target.value })}
                  placeholder="Position prompt (fed to the image model)"
                  rows={2}
                  className="mt-1.5 w-full rounded border border-border-default bg-canvas px-2 py-1 font-mono text-[11px] text-primary focus:outline-none focus:ring-1 focus:ring-accent"
                />
              </div>
            ))
          )}

          <div className="flex items-center gap-2 pt-1">
            <button
              type="button"
              onClick={addFrame}
              disabled={busy}
              className="cursor-pointer rounded border border-border-default bg-surface px-2 py-1 text-[11px] font-medium text-primary hover:bg-canvas disabled:opacity-50"
            >
              + Add frame
            </button>
            <div className="ml-auto flex items-center gap-1.5">
              <button
                type="button"
                onClick={() => {
                  resetDraft();
                  setOpen(false);
                }}
                disabled={busy}
                className="cursor-pointer rounded border border-border-default bg-surface px-2 py-1 text-[11px] font-medium text-primary hover:bg-canvas disabled:opacity-50"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={handleSave}
                disabled={busy}
                className="cursor-pointer rounded bg-accent px-3 py-1 text-[11px] font-medium text-inverse hover:bg-accent/90 disabled:opacity-50"
              >
                Save plan
              </button>
            </div>
          </div>
        </div>
      ) : null}
    </div>
  );
}

// ── One keyed media frame ────────────────────────────────────────────

function KeyedFrame({
  exerciseId,
  exerciseName,
  frame,
  mediaStatus,
  onZoom,
  regenerateFrame,
  uploadFrame,
  selectFrame,
  deleteFrame,
}: {
  exerciseId: string;
  exerciseName: string;
  frame: RenderFrame;
  mediaStatus: ExerciseMediaStatus;
  onZoom: (src: string) => void;
  regenerateFrame: (exerciseId: string, key: string) => Promise<void>;
  uploadFrame: (exerciseId: string, key: string, file: File) => Promise<void>;
  selectFrame: (exerciseId: string, key: string, imageUrl: string) => Promise<void>;
  deleteFrame: (exerciseId: string, key: string, imageUrl: string) => Promise<void>;
}) {
  const confirm = useConfirm();
  const toast = useToast();
  const [busy, setBusy] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);

  async function handleUpload(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    e.target.value = '';
    if (!file) return;
    setBusy(true);
    try {
      await uploadFrame(exerciseId, frame.key, file);
      toast.success(`${frame.label} frame uploaded`);
    } catch (err) {
      toast.error('Upload failed', {
        description: err instanceof Error ? err.message : 'Unknown error',
      });
    } finally {
      setBusy(false);
    }
  }

  async function handleRegenerate() {
    setBusy(true);
    try {
      await regenerateFrame(exerciseId, frame.key);
      toast.success(`Regenerating ${frame.label} frame`);
    } catch (err) {
      toast.error('Failed to start regeneration', {
        description: err instanceof Error ? err.message : 'Unknown error',
      });
    } finally {
      setBusy(false);
    }
  }

  async function handleSelect(url: string) {
    if (url === frame.imageUrl || busy) return;
    setBusy(true);
    try {
      await selectFrame(exerciseId, frame.key, url);
      toast.success('Frame selected');
    } catch (err) {
      toast.error('Failed to select frame', {
        description: err instanceof Error ? err.message : 'Unknown error',
      });
    } finally {
      setBusy(false);
    }
  }

  async function handleDelete(url: string) {
    if (busy) return;
    const ok = await confirm({
      title: 'Delete frame',
      description: `Remove this ${frame.label} candidate from "${exerciseName}"? This permanently deletes the file.`,
      confirmLabel: 'Delete',
      tone: 'danger',
    });
    if (!ok) return;
    setBusy(true);
    try {
      await deleteFrame(exerciseId, frame.key, url);
      toast.success('Frame deleted');
    } catch (err) {
      toast.error('Failed to delete frame', {
        description: err instanceof Error ? err.message : 'Unknown error',
      });
    } finally {
      setBusy(false);
    }
  }

  const url = frame.imageUrl;
  const isZoomable = !!url && mediaStatus !== 'PENDING';

  return (
    <div className="flex flex-col gap-1.5">
      <span className="caps-mono text-[9px] tracking-[0.06em] text-tertiary">
        {frame.label}
      </span>
      {/* Active frame (4:5 vertical body framing per the photography guide). */}
      {url ? (
        isZoomable ? (
          <button
            type="button"
            onClick={() => onZoom(url)}
            className="relative block aspect-[4/5] w-full cursor-zoom-in overflow-hidden rounded-md border border-border-default p-0"
            aria-label={`Zoom ${frame.label} frame for ${exerciseName}`}
          >
            <Image
              src={url}
              alt={`${exerciseName} ${frame.label}`}
              fill
              sizes="(max-width: 768px) 50vw, 200px"
              className="object-cover"
            />
          </button>
        ) : (
          <div className="relative aspect-[4/5] w-full overflow-hidden rounded-md border border-border-default">
            <Image
              src={url}
              alt=""
              fill
              sizes="(max-width: 768px) 50vw, 200px"
              className="object-cover"
            />
          </div>
        )
      ) : (
        <div className="flex aspect-[4/5] w-full flex-col items-center justify-center rounded-md border border-dashed border-border-default bg-canvas text-tertiary">
          <i className="ti ti-photo text-2xl" aria-hidden />
          <span className="mt-1 text-[10px] uppercase tracking-wider">
            {mediaStatus === 'PENDING' ? 'Pending' : mediaStatus === 'FAILED' ? 'Failed' : 'No frame'}
          </span>
        </div>
      )}

      {frame.caption ? (
        <span className="text-[11px] leading-snug text-secondary">{frame.caption}</span>
      ) : null}

      {/* Candidate strip */}
      {frame.imageCandidates.length > 1 ? (
        <div className="flex flex-wrap gap-1.5">
          {frame.imageCandidates.map((cand) => {
            const isActive = cand === frame.imageUrl;
            return (
              <div key={cand} className="group relative">
                <button
                  type="button"
                  onClick={() => handleSelect(cand)}
                  disabled={busy}
                  aria-label={isActive ? 'Active frame' : 'Make this the active frame'}
                  aria-pressed={isActive}
                  className={
                    'block h-10 w-8 overflow-hidden rounded border p-0 disabled:opacity-50 ' +
                    (isActive
                      ? 'border-accent ring-2 ring-accent cursor-default'
                      : 'border-border-default cursor-pointer hover:border-accent')
                  }
                >
                  <Image
                    src={cand}
                    alt=""
                    width={32}
                    height={40}
                    className="h-full w-full object-cover"
                  />
                </button>
                <button
                  type="button"
                  onClick={() => handleDelete(cand)}
                  disabled={busy}
                  aria-label="Delete this frame"
                  className="absolute -right-1.5 -top-1.5 flex h-4 w-4 items-center justify-center rounded-full bg-red-600 text-[10px] leading-none text-white opacity-0 transition-opacity hover:bg-red-700 group-hover:opacity-100 disabled:cursor-not-allowed"
                >
                  ×
                </button>
              </div>
            );
          })}
        </div>
      ) : null}

      <div className="mt-0.5 flex items-center gap-1">
        <button
          type="button"
          onClick={handleRegenerate}
          disabled={busy}
          className="cursor-pointer rounded border border-border-default bg-canvas px-1.5 py-1 text-[10px] font-medium text-primary hover:bg-surface disabled:cursor-not-allowed disabled:opacity-50"
        >
          Regenerate
        </button>
        <button
          type="button"
          onClick={() => fileInputRef.current?.click()}
          disabled={busy}
          className="cursor-pointer rounded border border-border-default bg-canvas px-1.5 py-1 text-[10px] font-medium text-primary hover:bg-surface disabled:cursor-not-allowed disabled:opacity-50"
        >
          Upload
        </button>
        <input
          ref={fileInputRef}
          type="file"
          accept="image/*"
          className="hidden"
          onChange={handleUpload}
        />
      </div>
    </div>
  );
}
