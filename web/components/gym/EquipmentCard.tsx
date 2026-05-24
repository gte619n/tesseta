"use client";

import type { Equipment } from "@/lib/types/gym";

interface EquipmentCardProps {
  equipment: Equipment;
  isSelected: boolean;
  onToggle: () => void;
}

function getCategoryIcon(category: string) {
  // Map categories to Lucide-style SVG icons
  switch (category) {
    case "Free Weights":
      return (
        <svg
          width="24"
          height="24"
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
          width="24"
          height="24"
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
          width="24"
          height="24"
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
          width="24"
          height="24"
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
          width="24"
          height="24"
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
          width="24"
          height="24"
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
          width="24"
          height="24"
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
          width="24"
          height="24"
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

export function EquipmentCard({ equipment, isSelected, onToggle }: EquipmentCardProps) {
  return (
    <div
      onClick={onToggle}
      className={`group relative cursor-pointer overflow-hidden rounded-lg border bg-surface transition-all ${
        isSelected
          ? "border-accent ring-2 ring-accent/20"
          : "border-border-default hover:border-accent/60"
      }`}
    >
      {/* Image or Icon */}
      <div className="relative aspect-square bg-canvas">
        {equipment.imageUrl ? (
          <img
            src={equipment.imageUrl}
            alt={equipment.name}
            className="h-full w-full object-cover"
          />
        ) : (
          <div className="flex h-full w-full items-center justify-center text-tertiary">
            {getCategoryIcon(equipment.category)}
          </div>
        )}
        {/* Selected Indicator */}
        {isSelected && (
          <div className="absolute right-2 top-2 flex h-6 w-6 items-center justify-center rounded-full bg-accent text-white">
            <svg
              width="14"
              height="14"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="3"
              strokeLinecap="round"
              strokeLinejoin="round"
            >
              <polyline points="20 6 9 17 4 12" />
            </svg>
          </div>
        )}
      </div>

      {/* Content */}
      <div className="p-3">
        <h3 className="text-[13px] font-medium text-primary line-clamp-1">
          {equipment.name}
        </h3>
        <p className="mt-0.5 text-[11px] text-tertiary">
          {equipment.category}
          {equipment.subcategory && ` • ${equipment.subcategory}`}
        </p>
        {equipment.exerciseCount !== null && equipment.exerciseCount > 0 && (
          <div className="mt-2 inline-flex items-center rounded-md bg-canvas px-2 py-0.5 text-[10px] font-medium text-secondary">
            {equipment.exerciseCount} exercise{equipment.exerciseCount !== 1 ? "s" : ""}
          </div>
        )}
      </div>

      {/* Toggle Button */}
      <div className="absolute bottom-0 left-0 right-0 bg-gradient-to-t from-surface to-transparent p-3 pt-8 opacity-0 transition-opacity group-hover:opacity-100">
        <button
          type="button"
          onClick={(e) => {
            e.stopPropagation();
            onToggle();
          }}
          className={`w-full rounded-md px-3 py-1.5 text-[12px] font-medium transition-colors ${
            isSelected
              ? "bg-canvas text-primary ring-1 ring-border-default"
              : "bg-accent text-white"
          }`}
        >
          {isSelected ? "Remove" : "Add"}
        </button>
      </div>
    </div>
  );
}
