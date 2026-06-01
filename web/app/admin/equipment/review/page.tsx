import { revalidatePath } from 'next/cache';
import {
  getPendingEquipment,
  getAdminCatalog,
  approveEquipment,
  rejectEquipment,
  updateEquipment,
  regenerateEquipmentImage,
  uploadEquipmentImage,
  getEquipmentImagePrompt,
  mergeEquipment,
  getEquipment,
  selectEquipmentImage,
  deleteEquipmentImageCandidate,
} from '@/lib/gym-api';
import { AdminEquipmentClient } from '@/components/admin/AdminEquipmentClient';
import type { EquipmentSpecs, SpecSchema } from '@/lib/types/gym';
import { pageMetadata } from '@/lib/page-metadata';

export const metadata = pageMetadata('Equipment Review');

export const dynamic = 'force-dynamic';

export default async function AdminEquipmentReviewPage() {
  // Admin gating handled by app/admin/layout.tsx
  const [pending, catalog] = await Promise.all([
    getPendingEquipment(),
    getAdminCatalog(),
  ]);

  async function approveAction(equipmentId: string) {
    'use server';
    await approveEquipment(equipmentId);
    revalidatePath('/admin/equipment/review');
  }

  async function rejectAction(equipmentId: string, reason: string) {
    'use server';
    await rejectEquipment(equipmentId, reason);
    revalidatePath('/admin/equipment/review');
  }

  async function updateAction(
    equipmentId: string,
    data: { name: string; category: string; subcategory: string; specSchema: SpecSchema; specs: EquipmentSpecs },
  ) {
    'use server';
    await updateEquipment(equipmentId, data);
    revalidatePath('/admin/equipment/review');
  }

  async function regenerateAction(equipmentId: string, prompt: string) {
    'use server';
    await regenerateEquipmentImage(equipmentId, prompt);
    revalidatePath('/admin/equipment/review');
  }

  async function uploadImageAction(equipmentId: string, file: File) {
    'use server';
    await uploadEquipmentImage(equipmentId, file);
    revalidatePath('/admin/equipment/review');
  }

  async function getImageStatusAction(equipmentId: string): Promise<string | null> {
    'use server';
    const eq = await getEquipment(equipmentId);
    // Wire format is the uppercase Java enum name (e.g. "PENDING"); the
    // Equipment TS type's lowercase literal is incorrect for the actual
    // response, so normalize defensively.
    return eq.imageStatus ? String(eq.imageStatus).toUpperCase() : null;
  }

  async function getImagePromptAction(equipmentId: string) {
    'use server';
    return getEquipmentImagePrompt(equipmentId);
  }

  async function mergeAction(sourceId: string, targetId: string) {
    'use server';
    await mergeEquipment(sourceId, targetId);
    revalidatePath('/admin/equipment/review');
  }

  async function selectImageAction(equipmentId: string, imageUrl: string) {
    'use server';
    await selectEquipmentImage(equipmentId, imageUrl);
    revalidatePath('/admin/equipment/review');
  }

  async function deleteImageAction(equipmentId: string, imageUrl: string) {
    'use server';
    await deleteEquipmentImageCandidate(equipmentId, imageUrl);
    revalidatePath('/admin/equipment/review');
  }

  return (
    <div className="container mx-auto max-w-7xl py-8 px-4">
      <div className="mb-6 flex items-center justify-between">
        <h1 className="text-2xl font-semibold text-primary">Equipment Review</h1>
        <span className="text-sm text-secondary">
          Pending: {pending.length} · Catalog: {catalog.length}
        </span>
      </div>

      <AdminEquipmentClient
        pending={pending}
        catalog={catalog}
        approve={approveAction}
        reject={rejectAction}
        update={updateAction}
        regenerate={regenerateAction}
        uploadImage={uploadImageAction}
        getImageStatus={getImageStatusAction}
        getImagePrompt={getImagePromptAction}
        merge={mergeAction}
        selectImage={selectImageAction}
        deleteImage={deleteImageAction}
      />
    </div>
  );
}
