"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { EquipmentGrid } from "./EquipmentGrid";
import { EquipmentCatalog } from "./EquipmentCatalog";
import { EquipmentImportModal } from "./EquipmentImportModal";
import type {
  Equipment,
  CreateEquipmentRequest,
  ImportPreviewResponse,
  ImportConfirmRequest,
  ImportConfirmResponse,
} from "@/lib/types/gym";

interface LocationEquipmentSectionProps {
  locationId: string;
  locationName: string;
  equipmentIds: string[];
  equipment: Equipment[];
  addEquipmentToLocation: (equipmentId: string) => Promise<void>;
  removeEquipmentFromLocation: (equipmentId: string) => Promise<void>;
  searchCatalog: (
    search: string,
    category: string | null,
    subcategory: string | null,
  ) => Promise<Equipment[]>;
  submitEquipment: (data: CreateEquipmentRequest) => Promise<Equipment>;
  bulkImportPreview: (rawText: string) => Promise<ImportPreviewResponse>;
  bulkImportConfirm: (body: ImportConfirmRequest) => Promise<ImportConfirmResponse>;
}

export function LocationEquipmentSection({
  locationId,
  locationName,
  equipmentIds,
  equipment,
  addEquipmentToLocation,
  removeEquipmentFromLocation,
  searchCatalog,
  submitEquipment,
  bulkImportPreview,
  bulkImportConfirm,
}: LocationEquipmentSectionProps) {
  const router = useRouter();
  const [catalogOpen, setCatalogOpen] = useState(false);
  const [bulkImportOpen, setBulkImportOpen] = useState(false);

  async function handleSave(_newIds: string[]) {
    // Catalog save already persisted server-side via the action props;
    // refetch from the server rather than reconciling local state.
    router.refresh();
  }

  async function handleRemove(equipmentId: string) {
    await removeEquipmentFromLocation(equipmentId);
    router.refresh();
  }

  function handleImportSuccess() {
    // Bulk import confirm mutated the location server-side; refetch from the
    // server rather than trying to reconcile partial state locally.
    router.refresh();
  }

  return (
    <>
      <section className="rounded-[14px] border-[0.5px] border-border-default bg-surface">
        <div className="p-5">
          <EquipmentGrid
            equipment={equipment}
            equipmentIds={equipmentIds}
            onRemove={handleRemove}
            onOpenCatalog={() => setCatalogOpen(true)}
            onOpenBulkImport={() => setBulkImportOpen(true)}
          />
        </div>
      </section>

      <EquipmentCatalog
        locationId={locationId}
        locationName={locationName}
        currentEquipmentIds={equipmentIds}
        isOpen={catalogOpen}
        onClose={() => setCatalogOpen(false)}
        onSave={handleSave}
        addEquipmentToLocation={addEquipmentToLocation}
        removeEquipmentFromLocation={removeEquipmentFromLocation}
        searchCatalog={searchCatalog}
        submitEquipment={submitEquipment}
      />

      <EquipmentImportModal
        locationId={locationId}
        locationName={locationName}
        isOpen={bulkImportOpen}
        onClose={() => setBulkImportOpen(false)}
        onSuccess={handleImportSuccess}
        bulkImportPreview={bulkImportPreview}
        bulkImportConfirm={bulkImportConfirm}
      />
    </>
  );
}
