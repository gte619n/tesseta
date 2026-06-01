"use client";

import { useRef, useState } from 'react';
import { useConfirm } from '@/components/ui/ConfirmDialog';
import { useToast } from '@/components/ui/Toast';
import { ImageLightbox } from './ImageLightbox';
import type { DemoFrame, DemoPhase, ExerciseMediaStatus } from '@/lib/types/exercise';
import { DEMO_PHASES, DEMO_PHASE_LABEL } from '@/lib/types/exercise';

interface Props {
  exerciseId: string;
  exerciseName: string;
  frames: DemoFrame[];
  mediaStatus: ExerciseMediaStatus;
  // Per-phase actions.
  regeneratePhase: (exerciseId: string, phase: DemoPhase) => Promise<void>;
  uploadFrame: (exerciseId: string, phase: DemoPhase, file: File) => Promise<void>;
  selectFrame: (exerciseId: string, phase: DemoPhase, imageUrl: string) => Promise<void>;
  deleteFrame: (exerciseId: string, phase: DemoPhase, imageUrl: string) => Promise<void>;
}

// Renders the START / MID / END demo phase frames side by side, each with its
// active still, candidate strip, and per-phase regenerate / upload controls.
export function ExerciseDemoFrames({
  exerciseId,
  exerciseName,
  frames,
  mediaStatus,
  regeneratePhase,
  uploadFrame,
  selectFrame,
  deleteFrame,
}: Props) {
  const [lightboxSrc, setLightboxSrc] = useState<string | null>(null);

  const frameByPhase = new Map(frames.map((f) => [f.phase, f]));

  return (
    <>
      <div className="grid grid-cols-3 gap-3">
        {DEMO_PHASES.map((phase) => {
          const frame = frameByPhase.get(phase) ?? {
            phase,
            imageUrl: null,
            imageCandidates: [],
          };
          return (
            <PhaseFrame
              key={phase}
              exerciseId={exerciseId}
              exerciseName={exerciseName}
              phase={phase}
              frame={frame}
              mediaStatus={mediaStatus}
              onZoom={(src) => setLightboxSrc(src)}
              regeneratePhase={regeneratePhase}
              uploadFrame={uploadFrame}
              selectFrame={selectFrame}
              deleteFrame={deleteFrame}
            />
          );
        })}
      </div>
      <ImageLightbox
        src={lightboxSrc}
        alt={exerciseName}
        onClose={() => setLightboxSrc(null)}
      />
    </>
  );
}

function PhaseFrame({
  exerciseId,
  exerciseName,
  phase,
  frame,
  mediaStatus,
  onZoom,
  regeneratePhase,
  uploadFrame,
  selectFrame,
  deleteFrame,
}: {
  exerciseId: string;
  exerciseName: string;
  phase: DemoPhase;
  frame: DemoFrame;
  mediaStatus: ExerciseMediaStatus;
  onZoom: (src: string) => void;
  regeneratePhase: (exerciseId: string, phase: DemoPhase) => Promise<void>;
  uploadFrame: (exerciseId: string, phase: DemoPhase, file: File) => Promise<void>;
  selectFrame: (exerciseId: string, phase: DemoPhase, imageUrl: string) => Promise<void>;
  deleteFrame: (exerciseId: string, phase: DemoPhase, imageUrl: string) => Promise<void>;
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
      await uploadFrame(exerciseId, phase, file);
      toast.success(`${DEMO_PHASE_LABEL[phase]} frame uploaded`);
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
      await regeneratePhase(exerciseId, phase);
      toast.success(`Regenerating ${DEMO_PHASE_LABEL[phase]} frame`);
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
      await selectFrame(exerciseId, phase, url);
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
      description: `Remove this ${DEMO_PHASE_LABEL[phase]} candidate from "${exerciseName}"? This permanently deletes the file.`,
      confirmLabel: 'Delete',
      tone: 'danger',
    });
    if (!ok) return;
    setBusy(true);
    try {
      await deleteFrame(exerciseId, phase, url);
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
        {DEMO_PHASE_LABEL[phase]}
      </span>
      {/* Active frame (4:5 vertical body framing per the photography guide). */}
      {url ? (
        isZoomable ? (
          <button
            type="button"
            onClick={() => onZoom(url)}
            className="block aspect-[4/5] w-full cursor-zoom-in overflow-hidden rounded-md border border-border-default p-0"
            aria-label={`Zoom ${DEMO_PHASE_LABEL[phase]} frame for ${exerciseName}`}
          >
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src={url} alt={`${exerciseName} ${phase}`} className="h-full w-full object-cover" />
          </button>
        ) : (
          // eslint-disable-next-line @next/next/no-img-element
          <img
            src={url}
            alt=""
            className="aspect-[4/5] w-full rounded-md border border-border-default object-cover"
          />
        )
      ) : (
        <div className="flex aspect-[4/5] w-full flex-col items-center justify-center rounded-md border border-dashed border-border-default bg-canvas text-tertiary">
          <i className="ti ti-photo text-2xl" aria-hidden />
          <span className="mt-1 text-[10px] uppercase tracking-wider">
            {mediaStatus === 'PENDING' ? 'Pending' : mediaStatus === 'FAILED' ? 'Failed' : 'No frame'}
          </span>
        </div>
      )}

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
                  {/* eslint-disable-next-line @next/next/no-img-element */}
                  <img src={cand} alt="" className="h-full w-full object-cover" />
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
