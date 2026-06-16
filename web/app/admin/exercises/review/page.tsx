import { revalidatePath } from 'next/cache';
import {
  getAdminExerciseReview,
  approveExerciseMedia,
  regenerateMedia,
  regeneratePlan,
  savePlan,
  approvePlan,
  uploadFrame,
  selectFrame,
  deleteFrame,
  getDemoPrompt,
} from '@/lib/exercise-admin-api';
import { AdminExerciseReview } from '@/components/admin/AdminExerciseReview';
import type { FrameSpec } from '@/lib/types/exercise';
import { pageMetadata } from '@/lib/page-metadata';

export const metadata = pageMetadata('Exercise Review');

export const dynamic = 'force-dynamic';

const REVIEW_PATH = '/admin/exercises/review';

export default async function AdminExerciseReviewPage() {
  // Admin gating handled by app/admin/layout.tsx
  const review = await getAdminExerciseReview();

  async function approveMediaAction(exerciseId: string) {
    'use server';
    await approveExerciseMedia(exerciseId);
    revalidatePath(REVIEW_PATH);
  }

  async function regeneratePlanAction(exerciseId: string, promptOverride?: string) {
    'use server';
    await regeneratePlan(exerciseId, promptOverride ?? null);
    revalidatePath(REVIEW_PATH);
  }

  async function savePlanAction(exerciseId: string, frames: FrameSpec[]) {
    'use server';
    await savePlan(exerciseId, frames);
    revalidatePath(REVIEW_PATH);
  }

  async function approvePlanAction(exerciseId: string) {
    'use server';
    await approvePlan(exerciseId);
    revalidatePath(REVIEW_PATH);
  }

  async function regenerateMediaAction(
    exerciseId: string,
    promptOverride: string | null,
    key: string | null,
  ) {
    'use server';
    await regenerateMedia(exerciseId, { promptOverride, key });
    revalidatePath(REVIEW_PATH);
  }

  async function regenerateFrameAction(exerciseId: string, key: string) {
    'use server';
    await regenerateMedia(exerciseId, { key });
    revalidatePath(REVIEW_PATH);
  }

  async function uploadFrameAction(exerciseId: string, key: string, file: File) {
    'use server';
    await uploadFrame(exerciseId, key, file);
    revalidatePath(REVIEW_PATH);
  }

  async function selectFrameAction(exerciseId: string, key: string, imageUrl: string) {
    'use server';
    await selectFrame(exerciseId, key, imageUrl);
    revalidatePath(REVIEW_PATH);
  }

  async function deleteFrameAction(exerciseId: string, key: string, imageUrl: string) {
    'use server';
    await deleteFrame(exerciseId, key, imageUrl);
    revalidatePath(REVIEW_PATH);
  }

  async function getDemoPromptAction(exerciseId: string, key: string) {
    'use server';
    return getDemoPrompt(exerciseId, key);
  }

  return (
    <div className="container mx-auto max-w-7xl px-4 py-8">
      <div className="mb-6 flex items-center justify-between">
        <h1 className="text-2xl font-semibold text-primary">Exercise Review</h1>
        <span className="text-sm text-secondary">
          {review.length} pending media review
        </span>
      </div>

      <AdminExerciseReview
        review={review}
        approveMedia={approveMediaAction}
        regeneratePlan={regeneratePlanAction}
        savePlan={savePlanAction}
        approvePlan={approvePlanAction}
        regenerateMedia={regenerateMediaAction}
        regenerateFrame={regenerateFrameAction}
        uploadFrame={uploadFrameAction}
        selectFrame={selectFrameAction}
        deleteFrame={deleteFrameAction}
        getDemoPrompt={getDemoPromptAction}
      />
    </div>
  );
}
