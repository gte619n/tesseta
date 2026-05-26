import { revalidatePath } from 'next/cache';
import {
  getPendingEquipment,
  getAdminCatalog,
  approveEquipment,
  rejectEquipment,
  updateEquipment,
  regenerateEquipmentImage,
  getEquipmentImagePrompt,
  mergeEquipment,
} from '@/lib/gym-api';
import { AdminEquipmentClient } from '@/components/admin/AdminEquipmentClient';
import type { EquipmentSpecs, SpecSchema } from '@/lib/types/gym';

export const dynamic = 'force-dynamic';

export default async function AdminEquipmentPage() {
  // Admin gating handled by app/admin/layout.tsx
  const [pending, catalog] = await Promise.all([
    getPendingEquipment(),
    getAdminCatalog(),
  ]);

  async function approveAction(equipmentId: string) {
    'use server';
    await approveEquipment(equipmentId);
    revalidatePath('/admin/equipment');
  }

  async function rejectAction(equipmentId: string, reason: string) {
    'use server';
    await rejectEquipment(equipmentId, reason);
    revalidatePath('/admin/equipment');
  }

  async function updateAction(
    equipmentId: string,
    data: { name: string; category: string; subcategory: string; specSchema: SpecSchema; specs: EquipmentSpecs },
  ) {
    'use server';
    await updateEquipment(equipmentId, data);
    revalidatePath('/admin/equipment');
  }

  async function regenerateAction(equipmentId: string, prompt: string) {
    'use server';
    await regenerateEquipmentImage(equipmentId, prompt);
    revalidatePath('/admin/equipment');
  }

  async function getImagePromptAction(equipmentId: string) {
    'use server';
    return getEquipmentImagePrompt(equipmentId);
  }

  async function mergeAction(sourceId: string, targetId: string) {
    'use server';
    await mergeEquipment(sourceId, targetId);
    revalidatePath('/admin/equipment');
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
        getImagePrompt={getImagePromptAction}
        merge={mergeAction}
      />
    </div>
  );
}
