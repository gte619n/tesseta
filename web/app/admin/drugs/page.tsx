import { revalidatePath } from 'next/cache';
import {
  listAdminDrugs,
  createAdminDrug,
  updateAdminDrug,
  deleteAdminDrug,
  getDrugImagePrompt,
  regenerateDrugImage,
  uploadDrugImage,
  selectDrugImage,
  deleteDrugImageCandidate,
  mergeAdminDrugs,
} from '@/lib/drug-admin-api';
import { AdminDrugClient } from '@/components/admin/AdminDrugClient';
import type { DrugCategory, DrugForm } from '@/lib/types/medication';
import { pageMetadata } from '@/lib/page-metadata';

export const metadata = pageMetadata('Drug Admin');

export const dynamic = 'force-dynamic';

export default async function AdminDrugsPage() {
  // Admin gating handled by app/admin/layout.tsx
  const drugs = await listAdminDrugs();

  async function createAction(data: {
    name: string;
    aliases: string[];
    category: DrugCategory;
    form: DrugForm;
    defaultUnit: string;
    commonDoses: string[];
    suggestedMarkers: string[];
    description: string | null;
  }) {
    'use server';
    await createAdminDrug(data);
    revalidatePath('/admin/drugs');
  }

  async function updateAction(
    drugId: string,
    data: {
      name: string;
      aliases: string[];
      category: DrugCategory;
      form: DrugForm;
      defaultUnit: string;
    },
  ) {
    'use server';
    await updateAdminDrug(drugId, data);
    revalidatePath('/admin/drugs');
  }

  async function regenerateAction(drugId: string, prompt: string) {
    'use server';
    await regenerateDrugImage(drugId, prompt);
    revalidatePath('/admin/drugs');
  }

  async function uploadImageAction(drugId: string, file: File) {
    'use server';
    await uploadDrugImage(drugId, file);
    revalidatePath('/admin/drugs');
  }

  async function selectImageAction(drugId: string, imageUrl: string) {
    'use server';
    await selectDrugImage(drugId, imageUrl);
    revalidatePath('/admin/drugs');
  }

  async function deleteImageAction(drugId: string, imageUrl: string) {
    'use server';
    await deleteDrugImageCandidate(drugId, imageUrl);
    revalidatePath('/admin/drugs');
  }

  async function getImagePromptAction(drugId: string) {
    'use server';
    return getDrugImagePrompt(drugId);
  }

  async function mergeAction(sourceId: string, targetId: string) {
    'use server';
    await mergeAdminDrugs(sourceId, targetId);
    revalidatePath('/admin/drugs');
  }

  async function deleteAction(drugId: string) {
    'use server';
    await deleteAdminDrug(drugId);
    revalidatePath('/admin/drugs');
  }

  return (
    <div className="container mx-auto max-w-7xl py-8 px-4">
      <div className="mb-6 flex items-center justify-between">
        <h1 className="text-2xl font-semibold text-primary">Drug catalog</h1>
        <span className="text-sm text-secondary">{drugs.length} in catalog</span>
      </div>

      <AdminDrugClient
        drugs={drugs}
        create={createAction}
        update={updateAction}
        regenerate={regenerateAction}
        uploadImage={uploadImageAction}
        selectImage={selectImageAction}
        deleteImage={deleteImageAction}
        getImagePrompt={getImagePromptAction}
        merge={mergeAction}
        remove={deleteAction}
      />
    </div>
  );
}
