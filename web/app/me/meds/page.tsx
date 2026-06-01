import type { Metadata } from "next";
import Link from "next/link";

export const metadata: Metadata = {
  title: "Meds",
};
import { revalidatePath } from "next/cache";
import { apiFetch, apiJson } from "@/lib/api";
import type { Medication, Drug, FrequencyConfig, TimeSlot, DayOfWeek } from "@/lib/types/medication";
import { MedicationsSection } from "@/components/medications/MedicationsSection";
import { AddMedicationButton } from "@/components/medications/AddMedicationButton";

export const dynamic = "force-dynamic";

export default async function MedsPage() {
  const [medications, drugs] = await Promise.all([
    apiJson<Medication[]>("/api/me/medications"),
    apiJson<Drug[]>("/api/drugs"),
  ]);

  // Separate active and discontinued
  const activeMeds = medications.filter(m => m.status === "ACTIVE");
  const discontinuedMeds = medications.filter(m => m.status === "DISCONTINUED");

  // Server action: AI-powered drug lookup
  async function lookupDrug(query: string): Promise<Drug | null> {
    "use server";
    const res = await apiFetch("/api/drugs/lookup", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ query }),
    });

    if (!res.ok) {
      const text = await res.text();
      throw new Error(`Lookup failed: ${text}`);
    }

    const result = await res.json() as { found: boolean; drug: Drug | null; message: string | null };
    return result.found ? result.drug : null;
  }

  // Server action: Add medication
  async function addMedication(formData: FormData) {
    "use server";
    const drugId = formData.get("drugId") as string | null;
    const customName = formData.get("customName") as string | null;
    const dose = Number(formData.get("dose"));
    const unit = formData.get("unit") as string;
    const frequencyType = formData.get("frequencyType") as string;
    const timesPerPeriod = formData.get("timesPerPeriod")
      ? Number(formData.get("timesPerPeriod"))
      : undefined;
    const timeSlots = formData.get("timeSlots")
      ? JSON.parse(formData.get("timeSlots") as string) as TimeSlot[]
      : [];
    const correlatedMarkers = formData.get("correlatedMarkers")
      ? JSON.parse(formData.get("correlatedMarkers") as string) as string[]
      : [];
    const notes = formData.get("notes") as string | null;
    const prescribedBy = formData.get("prescribedBy") as string | null;
    // Day-of-week selection for weekly meds — without this the chosen day
    // never reaches the backend, so the dose schedules every day and renders
    // as a bare "Once weekly".
    const specificDays = formData.get("specificDays")
      ? (JSON.parse(formData.get("specificDays") as string) as DayOfWeek[])
      : undefined;

    const frequency: FrequencyConfig = {
      type: frequencyType as FrequencyConfig["type"],
      timesPerPeriod,
      ...(specificDays && specificDays.length > 0 ? { specificDays } : {}),
    };

    // Build request body - support both catalog drugs and custom entries
    const body: Record<string, unknown> = {
      dose,
      unit,
      frequency,
      timeSlots,
      correlatedMarkers,
      notes: notes || null,
      prescribedBy: prescribedBy || null,
    };

    if (drugId) {
      body.drugId = drugId;
    } else if (customName) {
      // Custom medication entry
      body.customName = customName;
      body.customCategory = formData.get("customCategory") as string;
      body.customForm = formData.get("customForm") as string;
    }

    const res = await apiFetch("/api/me/medications", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });

    if (!res.ok) {
      const text = await res.text();
      throw new Error(`Failed to add medication: ${text}`);
    }

    revalidatePath("/me/meds");
  }

  async function updateMedication(medicationId: string, data: {
    dose?: number;
    unit?: string;
    frequency?: FrequencyConfig;
    timeSlots?: TimeSlot[];
    notes?: string | null;
    prescribedBy?: string | null;
    startDate?: string;
    changeNotes?: string;
  }) {
    "use server";
    const res = await apiFetch(`/api/me/medications/${medicationId}`, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(data),
    });

    if (!res.ok) {
      const text = await res.text();
      throw new Error(`Failed to update medication: ${text}`);
    }

    revalidatePath("/me/meds");
  }

  async function changeDose(medicationId: string, data: {
    dose: number;
    unit?: string;
    startDate?: string;
    changeNotes?: string;
  }) {
    "use server";
    const res = await apiFetch(`/api/me/medications/${medicationId}/dosage`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(data),
    });

    if (!res.ok) {
      const text = await res.text();
      throw new Error(`Failed to change dose: ${text}`);
    }

    revalidatePath("/me/meds");
  }

  async function discontinueMedication(
    medicationId: string,
    reason: string,
    notes: string | null,
    endDate?: string,
  ) {
    "use server";
    const res = await apiFetch(`/api/me/medications/${medicationId}/discontinue`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ reason, notes, endDate: endDate ?? null }),
    });

    if (!res.ok) {
      throw new Error(`Failed to discontinue: ${res.status}`);
    }

    revalidatePath("/me/meds");
  }

  async function reactivateMedication(medicationId: string, resumeDate?: string) {
    "use server";
    const res = await apiFetch(`/api/me/medications/${medicationId}/reactivate`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ resumeDate: resumeDate ?? null }),
    });

    if (!res.ok) {
      const text = await res.text();
      throw new Error(`Failed to reactivate: ${text}`);
    }

    revalidatePath("/me/meds");
  }

  async function deleteMedication(medicationId: string) {
    "use server";
    const res = await apiFetch(`/api/me/medications/${medicationId}`, {
      method: "DELETE",
    });

    if (!res.ok) {
      throw new Error(`Failed to delete: ${res.status}`);
    }

    revalidatePath("/me/meds");
  }

  const hasActiveMeds = activeMeds.length > 0;
  const hasDiscontinuedMeds = discontinuedMeds.length > 0;

  return (
    <main className="min-h-screen bg-canvas p-8">
      <div className="mx-auto max-w-[1100px] space-y-6">
        <Link
          href="/"
          className="inline-flex items-center gap-1.5 font-mono text-[11px] uppercase tracking-[0.04em] text-tertiary hover:text-secondary"
        >
          ← Dashboard
        </Link>

        <header className="flex items-start justify-between">
          <div>
            <h1 className="m-0 text-[22px] font-medium tracking-[-0.015em] text-primary">
              Medications
            </h1>
            <p className="mt-1 text-[13px] text-secondary">
              Track your prescriptions, supplements, and protocols.
            </p>
          </div>
          <AddMedicationButton
            addMedication={addMedication}
            lookupDrug={lookupDrug}
            drugs={drugs}
          />
        </header>

        {/* Active Medications */}
        <section className="rounded-[14px] border-[0.5px] border-border-default bg-surface">
          <div className="border-b-[0.5px] border-border-subtle px-5 py-3">
            <h2 className="m-0 text-[14px] font-medium text-primary">
              Current ({activeMeds.length})
            </h2>
          </div>
          <div className="p-5">
            {hasActiveMeds ? (
              <MedicationsSection
                medications={activeMeds}
                updateMedication={updateMedication}
                changeDose={changeDose}
                discontinueMedication={discontinueMedication}
                reactivateMedication={reactivateMedication}
                deleteMedication={deleteMedication}
              />
            ) : (
              <EmptyState
                title="No active medications"
                description="Add your first medication to start tracking."
              />
            )}
          </div>
        </section>

        {/* Discontinued Medications */}
        {hasDiscontinuedMeds && (
          <section className="rounded-[14px] border-[0.5px] border-border-default bg-surface">
            <div className="border-b-[0.5px] border-border-subtle px-5 py-3">
              <h2 className="m-0 text-[14px] font-medium text-primary">
                History ({discontinuedMeds.length})
              </h2>
            </div>
            <div className="p-5">
              <MedicationsSection
                medications={discontinuedMeds}
                updateMedication={updateMedication}
                changeDose={changeDose}
                discontinueMedication={discontinueMedication}
                reactivateMedication={reactivateMedication}
                deleteMedication={deleteMedication}
              />
            </div>
          </section>
        )}
      </div>
    </main>
  );
}

function EmptyState({ title, description }: { title: string; description: string }) {
  return (
    <div className="flex flex-col items-center justify-center py-12 text-center">
      <div className="mb-4 rounded-full bg-canvas-sunken p-4">
        <svg
          className="h-8 w-8 text-tertiary"
          fill="none"
          viewBox="0 0 24 24"
          stroke="currentColor"
          strokeWidth={1.5}
        >
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            d="M9.75 9.75l4.5 4.5m0-4.5l-4.5 4.5M21 12a9 9 0 11-18 0 9 9 0 0118 0z"
          />
        </svg>
      </div>
      <h3 className="text-[14px] font-medium text-primary">{title}</h3>
      <p className="mt-1 text-[13px] text-secondary">{description}</p>
    </div>
  );
}
