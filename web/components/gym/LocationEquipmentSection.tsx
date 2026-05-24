"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { EquipmentGrid } from "./EquipmentGrid";
import { EquipmentCatalog } from "./EquipmentCatalog";
import { removeEquipmentFromLocation } from "@/lib/gym-api";

interface LocationEquipmentSectionProps {
  locationId: string;
  locationName: string;
  equipmentIds: string[];
}

export function LocationEquipmentSection({
  locationId,
  locationName,
  equipmentIds: initialEquipmentIds,
}: LocationEquipmentSectionProps) {
  const router = useRouter();
  const [catalogOpen, setCatalogOpen] = useState(false);
  const [equipmentIds, setEquipmentIds] = useState(initialEquipmentIds);

  async function handleSave(newIds: string[]) {
    // Update local state immediately for optimistic UI
    setEquipmentIds(newIds);
    // Revalidate the page to get fresh data
    router.refresh();
  }

  async function handleRemove(equipmentId: string) {
    const newIds = equipmentIds.filter((id) => id !== equipmentId);
    setEquipmentIds(newIds);
    await removeEquipmentFromLocation(locationId, equipmentId);
    router.refresh();
  }

  return (
    <>
      <section className="rounded-[14px] border-[0.5px] border-border-default bg-surface">
        <div className="p-5">
          <EquipmentGrid
            equipmentIds={equipmentIds}
            onRemove={handleRemove}
            onOpenCatalog={() => setCatalogOpen(true)}
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
      />
    </>
  );
}
