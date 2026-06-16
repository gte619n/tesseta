"use client";

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { useConfirm } from '@/components/ui/ConfirmDialog';
import { useToast } from '@/components/ui/Toast';
import { ExerciseDemoFrames } from './ExerciseDemoFrames';
import { RegenerateMediaModal, type RegenTarget } from './RegenerateMediaModal';
import { MediaStatusPill } from './ExercisePills';
import type { ExerciseResponse, FrameSpec, DemoFrame } from '@/lib/types/exercise';
import { MOVEMENT_PATTERN_LABEL } from '@/lib/types/exercise';

// IMPL-19: all admin demo-frame actions are keyed to the plan (not phase).
export interface ExerciseAdminActions {
  approveMedia: (exerciseId: string) => Promise<void>;
  // Plan editor.
  regeneratePlan: (exerciseId: string, promptOverride?: string) => Promise<void>;
  savePlan: (exerciseId: string, frames: FrameSpec[]) => Promise<void>;
  approvePlan: (exerciseId: string) => Promise<void>;
  // Media. key == null ⇒ all frames.
  regenerateMedia: (
    exerciseId: string,
    promptOverride: string | null,
    key: string | null,
  ) => Promise<void>;
  regenerateFrame: (exerciseId: string, key: string) => Promise<void>;
  uploadFrame: (exerciseId: string, key: string, file: File) => Promise<void>;
  selectFrame: (exerciseId: string, key: string, imageUrl: string) => Promise<void>;
  deleteFrame: (exerciseId: string, key: string, imageUrl: string) => Promise<void>;
  // IMPL-19: composed image prompt for one frame key (seeds the Regenerate flow).
  getDemoPrompt: (exerciseId: string, key: string) => Promise<string>;
}

// Build the modal's frame targets from the plan (preferred) or legacy frames.
export function regenTargets(
  demoPlan: FrameSpec[] | null,
  frames: DemoFrame[],
): RegenTarget[] {
  if (demoPlan && demoPlan.length > 0) {
    return [...demoPlan]
      .sort((a, b) => a.order - b.order)
      .map((s) => ({ key: s.key, label: s.label || s.key }));
  }
  return [...frames]
    .sort((a, b) => a.order - b.order)
    .map((f) => ({ key: f.key, label: f.label || f.key }));
}

interface Props extends ExerciseAdminActions {
  review: ExerciseResponse[];
}

export function AdminExerciseReview({
  review,
  approveMedia,
  regeneratePlan,
  savePlan,
  approvePlan,
  regenerateMedia,
  regenerateFrame,
  uploadFrame,
  selectFrame,
  deleteFrame,
  getDemoPrompt,
}: Props) {
  if (review.length === 0) {
    return (
      <div className="rounded-lg border border-border-default bg-surface px-6 py-12 text-center">
        <p className="text-sm text-secondary">No exercises pending media review</p>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <div className="rounded-md border border-warn/40 bg-warn-bg px-4 py-2.5 text-xs text-warn">
        <i className="ti ti-alert-triangle mr-1" aria-hidden />
        Review each phase for anatomical correctness — check joint angles, spinal position, and grip
        before approving. A wrong angle teaches an injurious movement.
      </div>
      {review.map((ex) => (
        <ReviewCard
          key={ex.exerciseId}
          exercise={ex}
          approveMedia={approveMedia}
          regeneratePlan={regeneratePlan}
          savePlan={savePlan}
          approvePlan={approvePlan}
          regenerateMedia={regenerateMedia}
          regenerateFrame={regenerateFrame}
          uploadFrame={uploadFrame}
          selectFrame={selectFrame}
          deleteFrame={deleteFrame}
          getDemoPrompt={getDemoPrompt}
        />
      ))}
    </div>
  );
}

function ReviewCard({
  exercise,
  approveMedia,
  regeneratePlan,
  savePlan,
  approvePlan,
  regenerateMedia,
  regenerateFrame,
  uploadFrame,
  selectFrame,
  deleteFrame,
  getDemoPrompt,
}: { exercise: ExerciseResponse } & ExerciseAdminActions) {
  const router = useRouter();
  const confirm = useConfirm();
  const toast = useToast();
  const [isRegenOpen, setIsRegenOpen] = useState(false);
  const [busy, setBusy] = useState(false);

  async function handleApprove() {
    const ok = await confirm({
      title: 'Approve media',
      description: `Mark the demo media for "${exercise.name}" as approved? It becomes visible to users and the program generator.`,
      confirmLabel: 'Approve',
    });
    if (!ok) return;
    setBusy(true);
    try {
      await approveMedia(exercise.exerciseId);
      toast.success('Media approved');
      router.refresh();
    } catch (e) {
      toast.error('Failed to approve media', {
        description: e instanceof Error ? e.message : 'Unknown error',
      });
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <div className="rounded-lg border border-border-default bg-surface p-5">
        <div className="mb-3 flex items-start justify-between gap-3">
          <div className="min-w-0">
            <div className="flex items-center gap-2">
              <h3 className="truncate text-base font-semibold text-primary">{exercise.name}</h3>
              <MediaStatusPill status={exercise.mediaStatus} />
            </div>
            <p className="mt-0.5 text-xs text-secondary">
              {MOVEMENT_PATTERN_LABEL[exercise.movementPattern]}
              {exercise.primaryMuscles.length > 0
                ? ` · ${exercise.primaryMuscles.join(', ')}`
                : ''}
            </p>
          </div>
          <div className="flex shrink-0 items-center gap-2">
            <button
              onClick={() => setIsRegenOpen(true)}
              disabled={busy}
              className="cursor-pointer rounded-md border border-border-default bg-canvas px-3 py-1.5 text-xs font-medium text-primary hover:bg-surface disabled:cursor-not-allowed disabled:opacity-50"
            >
              Regenerate all
            </button>
            <button
              onClick={handleApprove}
              disabled={busy}
              className="cursor-pointer rounded-md bg-green-600 px-3 py-1.5 text-xs font-medium text-white hover:bg-green-700 disabled:cursor-not-allowed disabled:opacity-50"
            >
              Approve media
            </button>
          </div>
        </div>

        <ExerciseDemoFrames
          exerciseId={exercise.exerciseId}
          exerciseName={exercise.name}
          demoPlan={exercise.demoPlan}
          planStatus={exercise.planStatus}
          frames={exercise.demoFrames}
          mediaStatus={exercise.mediaStatus}
          regeneratePlan={async (id, override) => {
            await regeneratePlan(id, override);
            router.refresh();
          }}
          savePlan={async (id, frames) => {
            await savePlan(id, frames);
            router.refresh();
          }}
          approvePlan={async (id) => {
            await approvePlan(id);
            router.refresh();
          }}
          regenerateFrame={async (id, key) => {
            await regenerateFrame(id, key);
            router.refresh();
          }}
          uploadFrame={async (id, key, file) => {
            await uploadFrame(id, key, file);
            router.refresh();
          }}
          selectFrame={async (id, key, url) => {
            await selectFrame(id, key, url);
            router.refresh();
          }}
          deleteFrame={async (id, key, url) => {
            await deleteFrame(id, key, url);
            router.refresh();
          }}
        />

        {exercise.formCues.length > 0 ? (
          <div className="mt-3 border-t border-border-subtle pt-3">
            <span className="caps-mono text-[9px] tracking-[0.06em] text-tertiary">Form cues</span>
            <ul className="mt-1 list-disc space-y-0.5 pl-4 text-xs text-secondary">
              {exercise.formCues.map((cue, i) => (
                <li key={i}>{cue}</li>
              ))}
            </ul>
          </div>
        ) : null}
      </div>

      <RegenerateMediaModal
        exerciseId={exercise.exerciseId}
        exerciseName={exercise.name}
        targets={regenTargets(exercise.demoPlan, exercise.demoFrames)}
        isOpen={isRegenOpen}
        onClose={() => setIsRegenOpen(false)}
        onStarted={() => {
          setIsRegenOpen(false);
          router.refresh();
        }}
        regenerate={regenerateMedia}
        getDemoPrompt={getDemoPrompt}
      />
    </>
  );
}
