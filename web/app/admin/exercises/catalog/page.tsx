import { revalidatePath } from 'next/cache';
import {
  getAdminExerciseCatalogSummary,
  getAdminExercise,
  createExercise,
  updateExercise,
  publishExercise,
  archiveExercise,
  approveExerciseMedia,
  regenerateMedia,
  regeneratePlan,
  savePlan,
  approvePlan,
  uploadFrame,
  selectFrame,
  deleteFrame,
  getDemoPrompt,
  mergeExercise,
  setReviewed,
  saveGrounding,
  searchEquipment as searchEquipmentApi,
} from '@/lib/exercise-admin-api';
import { AdminExerciseCatalog } from '@/components/admin/AdminExerciseCatalog';
import type { CatalogPreset } from '@/components/admin/catalog-filters';
import type {
  ExerciseEditableFields,
  ExerciseResponse,
  FrameSpec,
} from '@/lib/types/exercise';
import type { Equipment } from '@/lib/types/gym';
import { pageMetadata } from '@/lib/page-metadata';

export const metadata = pageMetadata('Exercise Catalog');

export const dynamic = 'force-dynamic';

const CATALOG_PATH = '/admin/exercises/catalog';

export default async function AdminExerciseCatalogPage({
  searchParams,
}: {
  searchParams: Promise<{ preset?: string }>;
}) {
  // Admin gating handled by app/admin/layout.tsx
  const summary = await getAdminExerciseCatalogSummary();

  // IMPL-20: the Review tab redirects here with ?preset=needs-review.
  const { preset: presetParam } = await searchParams;
  const preset: CatalogPreset = presetParam === 'needs-review' ? 'needs-review' : null;

  // Resolve equipment names so the edit modal renders labels, not raw ids.
  let equipmentNames: Record<string, string> = {};
  try {
    const equipment = await searchEquipmentApi();
    equipmentNames = Object.fromEntries(equipment.map((e) => [e.equipmentId, e.name]));
  } catch {
    equipmentNames = {};
  }

  // Lazy full-detail load for the drawer (server action → server-only helper).
  async function loadExerciseAction(exerciseId: string): Promise<ExerciseResponse> {
    'use server';
    return getAdminExercise(exerciseId);
  }

  async function saveAction(data: ExerciseEditableFields, exerciseId: string | null) {
    'use server';
    if (exerciseId) await updateExercise(exerciseId, data);
    else await createExercise(data);
    revalidatePath(CATALOG_PATH);
  }

  async function searchEquipmentAction(search: string): Promise<Equipment[]> {
    'use server';
    return searchEquipmentApi(search);
  }

  async function publishAction(exerciseId: string) {
    'use server';
    await publishExercise(exerciseId);
    revalidatePath(CATALOG_PATH);
  }

  async function archiveAction(exerciseId: string) {
    'use server';
    await archiveExercise(exerciseId);
    revalidatePath(CATALOG_PATH);
  }

  async function mergeAction(sourceId: string, targetId: string) {
    'use server';
    await mergeExercise(sourceId, targetId);
    revalidatePath(CATALOG_PATH);
  }

  async function setReviewedAction(exerciseId: string, reviewed: boolean) {
    'use server';
    await setReviewed(exerciseId, reviewed);
    revalidatePath(CATALOG_PATH);
  }

  async function saveGroundingAction(exerciseId: string, imageUrls: string[]) {
    'use server';
    await saveGrounding(exerciseId, imageUrls);
    revalidatePath(CATALOG_PATH);
  }

  async function approveMediaAction(exerciseId: string) {
    'use server';
    await approveExerciseMedia(exerciseId);
    revalidatePath(CATALOG_PATH);
  }

  async function regeneratePlanAction(exerciseId: string, promptOverride?: string) {
    'use server';
    await regeneratePlan(exerciseId, promptOverride ?? null);
    revalidatePath(CATALOG_PATH);
  }

  async function savePlanAction(exerciseId: string, frames: FrameSpec[]) {
    'use server';
    await savePlan(exerciseId, frames);
    revalidatePath(CATALOG_PATH);
  }

  async function approvePlanAction(exerciseId: string) {
    'use server';
    await approvePlan(exerciseId);
    revalidatePath(CATALOG_PATH);
  }

  async function regenerateMediaAction(
    exerciseId: string,
    promptOverride: string | null,
    key: string | null,
    referenceImageUrls?: string[],
  ) {
    'use server';
    await regenerateMedia(exerciseId, { promptOverride, key, referenceImageUrls });
    revalidatePath(CATALOG_PATH);
  }

  async function regenerateFrameAction(exerciseId: string, key: string) {
    'use server';
    await regenerateMedia(exerciseId, { key });
    revalidatePath(CATALOG_PATH);
  }

  async function uploadFrameAction(exerciseId: string, key: string, file: File) {
    'use server';
    await uploadFrame(exerciseId, key, file);
    revalidatePath(CATALOG_PATH);
  }

  async function selectFrameAction(exerciseId: string, key: string, imageUrl: string) {
    'use server';
    await selectFrame(exerciseId, key, imageUrl);
    revalidatePath(CATALOG_PATH);
  }

  async function deleteFrameAction(exerciseId: string, key: string, imageUrl: string) {
    'use server';
    await deleteFrame(exerciseId, key, imageUrl);
    revalidatePath(CATALOG_PATH);
  }

  async function getDemoPromptAction(exerciseId: string, key: string) {
    'use server';
    return getDemoPrompt(exerciseId, key);
  }

  return (
    <div className="container mx-auto max-w-7xl px-4 py-8">
      <div className="mb-6 flex items-center justify-between">
        <h1 className="text-2xl font-semibold text-primary">Exercise Catalog</h1>
        <span className="text-sm text-secondary">
          {summary.length} exercise{summary.length === 1 ? '' : 's'}
        </span>
      </div>

      <AdminExerciseCatalog
        summary={summary}
        equipmentNames={equipmentNames}
        preset={preset}
        save={saveAction}
        searchEquipment={searchEquipmentAction}
        publish={publishAction}
        archive={archiveAction}
        merge={mergeAction}
        setReviewed={setReviewedAction}
        saveGrounding={saveGroundingAction}
        loadExercise={loadExerciseAction}
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
