import { revalidatePath } from 'next/cache';
import {
  getAdminCatalog,
  createCatalogEquipment,
  updateEquipment,
  regenerateEquipmentImage,
  uploadEquipmentImage,
  getEquipmentImagePrompt,
  getEquipment,
  selectEquipmentImage,
  deleteEquipmentImageCandidate,
} from '@/lib/gym-api';
import { AdminEquipmentCatalog } from '@/components/admin/AdminEquipmentCatalog';
import type { EquipmentSpecs, SpecSchema } from '@/lib/types/gym';
import { pageMetadata } from '@/lib/page-metadata';

export const metadata = pageMetadata('Equipment Catalog');

export const dynamic = 'force-dynamic';

export default async function AdminEquipmentCatalogPage() {
  // Admin gating handled by app/admin/layout.tsx
  const catalog = await getAdminCatalog();

  async function createAction(
    data: { name: string; category: string; subcategory: string; specSchema: SpecSchema; specs: EquipmentSpecs },
  ) {
    'use server';
    await createCatalogEquipment(data);
    revalidatePath('/admin/equipment/catalog');
  }

  async function updateAction(
    equipmentId: string,
    data: { name: string; category: string; subcategory: string; specSchema: SpecSchema; specs: EquipmentSpecs },
  ) {
    'use server';
    await updateEquipment(equipmentId, data);
    revalidatePath('/admin/equipment/catalog');
  }

  async function regenerateAction(equipmentId: string, prompt: string) {
    'use server';
    await regenerateEquipmentImage(equipmentId, prompt);
    revalidatePath('/admin/equipment/catalog');
  }

  async function uploadImageAction(equipmentId: string, file: File) {
    'use server';
    await uploadEquipmentImage(equipmentId, file);
    revalidatePath('/admin/equipment/catalog');
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

  async function selectImageAction(equipmentId: string, imageUrl: string) {
    'use server';
    await selectEquipmentImage(equipmentId, imageUrl);
    revalidatePath('/admin/equipment/catalog');
  }

  async function deleteImageAction(equipmentId: string, imageUrl: string) {
    'use server';
    await deleteEquipmentImageCandidate(equipmentId, imageUrl);
    revalidatePath('/admin/equipment/catalog');
  }

  return (
    <div className="container mx-auto max-w-7xl py-8 px-4">
      <div className="mb-6 flex items-center justify-between">
        <h1 className="text-2xl font-semibold text-primary">Equipment Catalog</h1>
        <span className="text-sm text-secondary">
          {catalog.length} active item{catalog.length === 1 ? '' : 's'}
        </span>
      </div>

      <AdminEquipmentCatalog
        catalog={catalog}
        create={createAction}
        update={updateAction}
        regenerate={regenerateAction}
        uploadImage={uploadImageAction}
        getImageStatus={getImageStatusAction}
        getImagePrompt={getImagePromptAction}
        selectImage={selectImageAction}
        deleteImage={deleteImageAction}
      />
    </div>
  );
}
