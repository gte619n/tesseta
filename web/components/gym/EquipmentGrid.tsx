"use client";

import Image from "next/image";
import { useConfirm } from "@/components/ui/ConfirmDialog";
import type { Equipment } from "@/lib/types/gym";

interface EquipmentGridProps {
  equipment: Equipment[];
  equipmentIds: string[];
  onRemove: (equipmentId: string) => void;
  onOpenCatalog: () => void;
  onOpenBulkImport?: () => void;
}

function getCategoryIcon(category: string) {
  // Reuse the same icon logic from EquipmentCard
  switch (category) {
    case "Free Weights":
      return (
        <svg
          width="20"
          height="20"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.5"
          strokeLinecap="round"
          strokeLinejoin="round"
        >
          <path d="M6 8h12M6 16h12M4 8v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2z" />
        </svg>
      );
    case "Machines - Cardio":
      return (
        <svg
          width="20"
          height="20"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.5"
          strokeLinecap="round"
          strokeLinejoin="round"
        >
          <path d="M22 12h-4l-3 9L9 3l-3 9H2" />
        </svg>
      );
    case "Machines - Strength":
      return (
        <svg
          width="20"
          height="20"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.5"
          strokeLinecap="round"
          strokeLinejoin="round"
        >
          <circle cx="12" cy="12" r="3" />
          <path d="M12 1v6m0 6v6M5.64 5.64l4.24 4.24m4.24 4.24l4.24 4.24M1 12h6m6 0h6M5.64 18.36l4.24-4.24m4.24-4.24l4.24-4.24" />
        </svg>
      );
    case "Cable Systems":
      return (
        <svg
          width="20"
          height="20"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.5"
          strokeLinecap="round"
          strokeLinejoin="round"
        >
          <path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71" />
          <path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71" />
        </svg>
      );
    case "Benches & Racks":
      return (
        <svg
          width="20"
          height="20"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.5"
          strokeLinecap="round"
          strokeLinejoin="round"
        >
          <rect x="3" y="3" width="18" height="18" rx="2" ry="2" />
        </svg>
      );
    case "Bodyweight":
      return (
        <svg
          width="20"
          height="20"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.5"
          strokeLinecap="round"
          strokeLinejoin="round"
        >
          <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
          <circle cx="12" cy="7" r="4" />
        </svg>
      );
    case "Accessories":
      return (
        <svg
          width="20"
          height="20"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.5"
          strokeLinecap="round"
          strokeLinejoin="round"
        >
          <path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z" />
        </svg>
      );
    default:
      return (
        <svg
          width="20"
          height="20"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.5"
          strokeLinecap="round"
          strokeLinejoin="round"
        >
          <rect x="3" y="3" width="18" height="18" rx="2" ry="2" />
        </svg>
      );
  }
}

function EquipmentGridCard({ equipment, onRemove }: { equipment: Equipment; onRemove: () => void }) {
  const confirm = useConfirm();

  async function handleRemove() {
    const ok = await confirm({
      title: "Remove equipment?",
      description: `Remove ${equipment.name} from this location?`,
      confirmLabel: "Remove",
      tone: "danger",
    });
    if (ok) {
      onRemove();
    }
  }

  return (
    <div className="group relative overflow-hidden rounded-lg border border-border-default bg-surface transition-colors hover:border-accent/60">
      {/* Image or Icon */}
      <div className="relative aspect-square bg-canvas">
        {equipment.imageUrl ? (
          <Image
            src={equipment.imageUrl}
            alt={equipment.name}
            fill
            sizes="(max-width: 768px) 50vw, 200px"
            className="object-cover"
          />
        ) : (
          <div className="flex h-full w-full items-center justify-center text-tertiary">
            {getCategoryIcon(equipment.category)}
          </div>
        )}
        {/* Remove button on hover */}
        <button
          type="button"
          onClick={handleRemove}
          className="absolute right-2 top-2 flex h-6 w-6 items-center justify-center rounded-full bg-red-600 text-white opacity-0 transition-opacity group-hover:opacity-100"
          aria-label="Remove equipment"
        >
          <svg
            width="14"
            height="14"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinecap="round"
            strokeLinejoin="round"
          >
            <line x1="18" y1="6" x2="6" y2="18" />
            <line x1="6" y1="6" x2="18" y2="18" />
          </svg>
        </button>
      </div>

      {/* Content */}
      <div className="p-2">
        <h3 className="text-[12px] font-medium text-primary line-clamp-1">
          {equipment.name}
        </h3>
        <p className="mt-0.5 text-[10px] text-tertiary line-clamp-1">
          {equipment.subcategory}
        </p>
      </div>
    </div>
  );
}

export function EquipmentGrid({ equipment, equipmentIds, onRemove, onOpenCatalog, onOpenBulkImport }: EquipmentGridProps) {
  // Filter the prefetched equipment list against the current (possibly optimistic)
  // equipmentIds so that local removals reflect immediately without a refetch.
  const idSet = new Set(equipmentIds);
  const visibleEquipment = equipment.filter((e) => idSet.has(e.equipmentId));

  if (equipmentIds.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center gap-4 rounded-lg border border-dashed border-border-default bg-canvas py-12 text-center">
        <div className="text-tertiary">
          <svg
            width="48"
            height="48"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="1"
            strokeLinecap="round"
            strokeLinejoin="round"
          >
            <path d="M6 8h12M6 16h12M4 8v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2z" />
          </svg>
        </div>
        <div>
          <p className="text-[14px] font-medium text-primary">No equipment added yet</p>
          <p className="mt-1 text-[12px] text-tertiary">
            Add equipment from the catalog to track what&apos;s available at this location
          </p>
        </div>
        <div className="flex gap-2">
          <button
            type="button"
            onClick={onOpenCatalog}
            className="cursor-pointer rounded-md bg-accent px-4 py-2 text-[13px] font-medium text-white hover:bg-accent/90"
          >
            Add from Catalog
          </button>
          {onOpenBulkImport && (
            <button
              type="button"
              onClick={onOpenBulkImport}
              className="cursor-pointer rounded-md border border-border-default bg-canvas px-4 py-2 text-[13px] font-medium text-primary hover:bg-surface"
            >
              Import List
            </button>
          )}
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h3 className="text-[14px] font-medium text-primary">
          Equipment ({visibleEquipment.length})
        </h3>
        <div className="flex gap-2">
          <button
            type="button"
            onClick={onOpenCatalog}
            className="cursor-pointer rounded-md border border-border-default bg-canvas px-3 py-1.5 text-[12px] font-medium text-primary hover:bg-surface"
          >
            + Add from Catalog
          </button>
          {onOpenBulkImport && (
            <button
              type="button"
              onClick={onOpenBulkImport}
              className="cursor-pointer rounded-md border border-border-default bg-canvas px-3 py-1.5 text-[12px] font-medium text-secondary hover:bg-surface"
            >
              Import List
            </button>
          )}
        </div>
      </div>
      <div className="grid grid-cols-3 gap-3 sm:grid-cols-4 md:grid-cols-5 lg:grid-cols-6">
        {visibleEquipment.map((item) => (
          <EquipmentGridCard
            key={item.equipmentId}
            equipment={item}
            onRemove={() => onRemove(item.equipmentId)}
          />
        ))}
      </div>
    </div>
  );
}
