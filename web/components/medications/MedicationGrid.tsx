"use client";

import type { Medication } from "@/lib/types/medication";
import { MedicationCard } from "./MedicationCard";

interface MedicationGridProps {
  medications: Medication[];
  onSelect?: (medication: Medication) => void;
}

export function MedicationGrid({ medications, onSelect }: MedicationGridProps) {
  if (medications.length === 0) {
    return null;
  }

  return (
    <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-4">
      {medications.map((med) => (
        <MedicationCard
          key={med.medicationId}
          medication={med}
          onClick={() => onSelect?.(med)}
        />
      ))}
    </div>
  );
}
