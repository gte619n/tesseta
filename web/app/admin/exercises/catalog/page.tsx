import { revalidatePath } from 'next/cache';
import {
  getAdminExerciseCatalog,
  createExercise,
  updateExercise,
  publishExercise,
  archiveExercise,
  approveExerciseMedia,
  getDemoPrompt,
  regenerateMedia,
  uploadFrame,
  selectFrame,
  deleteFrame,
  mergeExercise,
  searchEquipment as searchEquipmentApi,
} from '@/lib/exercise-admin-api';
import { AdminExerciseCatalog } from '@/components/admin/AdminExerciseCatalog';
import type { ExerciseEditableFields, DemoPhase } from '@/lib/types/exercise';
import type { Equipment } from '@/lib/types/gym';
import { pageMetadata } from '@/lib/page-metadata';

export const metadata = pageMetadata('Exercise Catalog');

export const dynamic = 'force-dynamic';

const CATALOG_PATH = '/admin/exercises/catalog';

export default async function AdminExerciseCatalogPage() {
  // Admin gating handled by app/admin/layout.tsx
  const catalog = await getAdminExerciseCatalog();

  // Resolve equipment names so existing requirement groups render labels, not
  // raw ids. One catalog read covers every referenced id.
  let equipmentNames: Record<string, string> = {};
  try {
    const equipment = await searchEquipmentApi();
    equipmentNames = Object.fromEntries(equipment.map((e) => [e.equipmentId, e.name]));
  } catch {
    equipmentNames = {};
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

  async function approveMediaAction(exerciseId: string) {
    'use server';
    await approveExerciseMedia(exerciseId);
    revalidatePath(CATALOG_PATH);
  }

  async function getDemoPromptAction(exerciseId: string, phase: DemoPhase) {
    'use server';
    return getDemoPrompt(exerciseId, phase);
  }

  async function regenerateMediaAction(
    exerciseId: string,
    promptOverride: string,
    phase: DemoPhase | null,
  ) {
    'use server';
    await regenerateMedia(exerciseId, { promptOverride, phase });
    revalidatePath(CATALOG_PATH);
  }

  async function regeneratePhaseAction(exerciseId: string, phase: DemoPhase) {
    'use server';
    await regenerateMedia(exerciseId, { phase });
    revalidatePath(CATALOG_PATH);
  }

  async function uploadFrameAction(exerciseId: string, phase: DemoPhase, file: File) {
    'use server';
    await uploadFrame(exerciseId, phase, file);
    revalidatePath(CATALOG_PATH);
  }

  async function selectFrameAction(exerciseId: string, phase: DemoPhase, imageUrl: string) {
    'use server';
    await selectFrame(exerciseId, phase, imageUrl);
    revalidatePath(CATALOG_PATH);
  }

  async function deleteFrameAction(exerciseId: string, phase: DemoPhase, imageUrl: string) {
    'use server';
    await deleteFrame(exerciseId, phase, imageUrl);
    revalidatePath(CATALOG_PATH);
  }

  return (
    <div className="container mx-auto max-w-7xl px-4 py-8">
      <div className="mb-6 flex items-center justify-between">
        <h1 className="text-2xl font-semibold text-primary">Exercise Catalog</h1>
        <span className="text-sm text-secondary">
          {catalog.length} exercise{catalog.length === 1 ? '' : 's'}
        </span>
      </div>

      <AdminExerciseCatalog
        catalog={catalog}
        equipmentNames={equipmentNames}
        save={saveAction}
        searchEquipment={searchEquipmentAction}
        publish={publishAction}
        archive={archiveAction}
        merge={mergeAction}
        approveMedia={approveMediaAction}
        getDemoPrompt={getDemoPromptAction}
        regenerateMedia={regenerateMediaAction}
        regeneratePhase={regeneratePhaseAction}
        uploadFrame={uploadFrameAction}
        selectFrame={selectFrameAction}
        deleteFrame={deleteFrameAction}
      />
    </div>
  );
}
