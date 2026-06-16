import type { RegenTarget } from './RegenerateMediaModal';
import type { FrameSpec, DemoFrame } from '@/lib/types/exercise';

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
