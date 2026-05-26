import { revalidatePath } from 'next/cache';
import {
  listAdminDrugs,
  updateAdminDrug,
  deleteAdminDrug,
  getDrugImagePrompt,
  regenerateDrugImage,
  mergeAdminDrugs,
} from '@/lib/drug-admin-api';
import { AdminDrugClient } from '@/components/admin/AdminDrugClient';
import type { DrugCategory, DrugForm } from '@/lib/types/medication';

export const dynamic = 'force-dynamic';

export default async function AdminDrugsPage() {
  // Admin gating handled by app/admin/layout.tsx
  const drugs = await listAdminDrugs();

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
        update={updateAction}
        regenerate={regenerateAction}
        getImagePrompt={getImagePromptAction}
        merge={mergeAction}
        remove={deleteAction}
      />
    </div>
  );
}
