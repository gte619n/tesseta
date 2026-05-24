"use client";

import { useState } from "react";
import type { Medication, FrequencyConfig, TimeSlot } from "@/lib/types/medication";
import { MedicationGrid } from "./MedicationGrid";
import { MedicationDetailModal } from "./MedicationDetailModal";

interface MedicationsSectionProps {
  medications: Medication[];
  updateMedication: (medicationId: string, data: {
    dose?: number;
    unit?: string;
    frequency?: FrequencyConfig;
    timeSlots?: TimeSlot[];
    notes?: string | null;
    prescribedBy?: string | null;
    changeNotes?: string;
  }) => Promise<void>;
  discontinueMedication: (medicationId: string, reason: string, notes: string | null) => Promise<void>;
  deleteMedication: (medicationId: string) => Promise<void>;
}

export function MedicationsSection({
  medications,
  updateMedication,
  discontinueMedication,
  deleteMedication,
}: MedicationsSectionProps) {
  const [selectedMedication, setSelectedMedication] = useState<Medication | null>(null);

  return (
    <>
      <MedicationGrid
        medications={medications}
        onSelect={setSelectedMedication}
      />

      {selectedMedication && (
        <MedicationDetailModal
          medication={selectedMedication}
          open={!!selectedMedication}
          onClose={() => setSelectedMedication(null)}
          updateMedication={updateMedication}
          discontinueMedication={discontinueMedication}
          deleteMedication={deleteMedication}
        />
      )}
    </>
  );
}
