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
    startDate?: string;
    changeNotes?: string;
  }) => Promise<void>;
  changeDose: (medicationId: string, data: {
    dose: number;
    unit?: string;
    startDate?: string;
    changeNotes?: string;
  }) => Promise<void>;
  discontinueMedication: (medicationId: string, reason: string, notes: string | null, endDate?: string) => Promise<void>;
  reactivateMedication: (medicationId: string, resumeDate?: string) => Promise<void>;
  deleteMedication: (medicationId: string) => Promise<void>;
}

export function MedicationsSection({
  medications,
  updateMedication,
  changeDose,
  discontinueMedication,
  reactivateMedication,
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
          changeDose={changeDose}
          discontinueMedication={discontinueMedication}
          reactivateMedication={reactivateMedication}
          deleteMedication={deleteMedication}
        />
      )}
    </>
  );
}
