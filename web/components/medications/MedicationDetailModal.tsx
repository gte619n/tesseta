"use client";

import { useState, useTransition } from "react";
import { useRouter } from "next/navigation";
import type { Medication, DosagePeriod, FrequencyConfig, TimeSlot, TimeWindow, FrequencyType } from "@/lib/types/medication";
import {
  formatFrequency,
  TIME_WINDOW_LABELS,
  CATEGORY_LABELS,
  FORM_LABELS,
  FREQUENCY_LABELS,
  DISCONTINUE_REASON_LABELS,
  DiscontinueReason,
} from "@/lib/types/medication";
import { DrugImage } from "./DrugImage";
import { AdherenceSparkline } from "./AdherenceSparkline";
import { useConfirm } from "@/components/ui/ConfirmDialog";
import { useToast } from "@/components/ui/Toast";

interface MedicationDetailModalProps {
  medication: Medication;
  open: boolean;
  onClose: () => void;
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

function formatPeriodRange(p: DosagePeriod): string {
  const opts: Intl.DateTimeFormatOptions = { month: "short", year: "numeric" };
  const start = new Date(p.startDate).toLocaleDateString(undefined, opts);
  if (p.endDate === null) return `${start} – Present`;
  const end = new Date(p.endDate).toLocaleDateString(undefined, opts);
  return `${start} – ${end}`;
}

export function MedicationDetailModal({
  medication,
  open,
  onClose,
  updateMedication,
  changeDose,
  discontinueMedication,
  reactivateMedication,
  deleteMedication,
}: MedicationDetailModalProps) {
  const router = useRouter();
  const confirm = useConfirm();
  const toast = useToast();
  const [isPending, startTransition] = useTransition();
  const [view, setView] = useState<"detail" | "edit" | "changeDose" | "discontinue" | "reactivate">("detail");

  // Change-dose form state
  const [newDose, setNewDose] = useState("");
  const [newDoseUnit, setNewDoseUnit] = useState(medication.unit);
  const [doseEffectiveDate, setDoseEffectiveDate] = useState(
    new Date().toISOString().slice(0, 10)
  );
  const [doseChangeNotes, setDoseChangeNotes] = useState("");
  const [frequencyType, setFrequencyType] = useState<FrequencyType>(medication.frequency.type);
  const [timesPerPeriod, setTimesPerPeriod] = useState(
    String(medication.frequency.timesPerPeriod ?? 1)
  );
  const [selectedWindows, setSelectedWindows] = useState<TimeWindow[]>(
    medication.timeSlots.map(s => s.window)
  );
  const [notes, setNotes] = useState(medication.notes ?? "");
  const [prescribedBy, setPrescribedBy] = useState(medication.prescribedBy ?? "");
  const [changeNotes, setChangeNotes] = useState("");
  const [startDate, setStartDate] = useState(medication.startDate.slice(0, 10));

  // Discontinue form state
  const [discontinueReason, setDiscontinueReason] = useState<DiscontinueReason>("OTHER");
  const [discontinueNotes, setDiscontinueNotes] = useState("");
  const [discontinueDate, setDiscontinueDate] = useState(
    new Date().toISOString().slice(0, 10)
  );

  // Reactivate form state
  const [resumeDate, setResumeDate] = useState(
    new Date().toISOString().slice(0, 10)
  );

  const { drug, status } = medication;
  const name = medication.customName ?? drug?.name ?? "Unknown";
  const category = drug?.category;
  const form = drug?.form ?? "TABLET";

  if (!open) return null;

  function handleClose() {
    setView("detail");
    onClose();
  }

  function toggleWindow(window: TimeWindow) {
    setSelectedWindows((prev) =>
      prev.includes(window)
        ? prev.filter((w) => w !== window)
        : [...prev, window]
    );
  }

  function handleSaveEdit() {
    const newFrequency: FrequencyConfig = {
      type: frequencyType,
      timesPerPeriod: Number(timesPerPeriod) || 1,
    };

    // Dose is managed via the dedicated "Change dose" flow so it builds a dated
    // history; here we only split the current dose across the selected windows.
    const timeSlots: TimeSlot[] = selectedWindows.map((window) => ({
      window,
      dose: medication.dose / Math.max(selectedWindows.length, 1),
    }));

    startTransition(async () => {
      try {
        await updateMedication(medication.medicationId, {
          frequency: newFrequency,
          timeSlots,
          notes: notes || null,
          prescribedBy: prescribedBy || null,
          startDate: startDate !== medication.startDate.slice(0, 10) ? startDate : undefined,
          changeNotes: changeNotes || undefined,
        });
        toast.success("Medication updated");
        setView("detail");
        router.refresh();
      } catch (e) {
        toast.error("Failed to update medication", {
          description: e instanceof Error ? e.message : "Unknown error",
        });
      }
    });
  }

  function handleReactivate() {
    startTransition(async () => {
      try {
        await reactivateMedication(medication.medicationId, resumeDate || undefined);
        toast.success("Medication resumed");
        handleClose();
        router.refresh();
      } catch (e) {
        toast.error("Failed to resume medication", {
          description: e instanceof Error ? e.message : "Unknown error",
        });
      }
    });
  }

  function handleChangeDose() {
    const parsed = Number(newDose);
    if (!parsed || parsed <= 0) {
      toast.error("Enter a dose greater than zero");
      return;
    }
    startTransition(async () => {
      try {
        await changeDose(medication.medicationId, {
          dose: parsed,
          unit: newDoseUnit || undefined,
          startDate: doseEffectiveDate || undefined,
          changeNotes: doseChangeNotes || undefined,
        });
        toast.success("Dose updated");
        setNewDose("");
        setDoseChangeNotes("");
        setView("detail");
        router.refresh();
      } catch (e) {
        toast.error("Failed to change dose", {
          description: e instanceof Error ? e.message : "Unknown error",
        });
      }
    });
  }

  function handleDiscontinue() {
    startTransition(async () => {
      try {
        await discontinueMedication(
          medication.medicationId,
          discontinueReason,
          discontinueNotes || null,
          discontinueDate || undefined
        );
        toast.success("Medication discontinued");
        handleClose();
        router.refresh();
      } catch (e) {
        toast.error("Failed to discontinue medication", {
          description: e instanceof Error ? e.message : "Unknown error",
        });
      }
    });
  }

  async function handleDelete() {
    const ok = await confirm({
      title: `Delete ${name}?`,
      description: "This will permanently remove this medication and all its history. This cannot be undone.",
      confirmLabel: "Delete",
      tone: "danger",
    });

    if (!ok) return;

    startTransition(async () => {
      try {
        await deleteMedication(medication.medicationId);
        toast.success("Medication deleted");
        handleClose();
        router.refresh();
      } catch (e) {
        toast.error("Failed to delete medication", {
          description: e instanceof Error ? e.message : "Unknown error",
        });
      }
    });
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4">
      <div className="w-full max-w-lg rounded-xl bg-surface shadow-xl">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-border-subtle px-5 py-4">
          <div className="flex items-center gap-3">
            {view !== "detail" && (
              <button
                type="button"
                onClick={() => setView("detail")}
                className="rounded-lg p-1 text-tertiary hover:bg-canvas-sunken hover:text-secondary"
              >
                <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                  <path strokeLinecap="round" strokeLinejoin="round" d="M15 19l-7-7 7-7" />
                </svg>
              </button>
            )}
            <h2 className="text-[16px] font-medium text-primary">
              {view === "detail" && name}
              {view === "edit" && "Edit medication"}
              {view === "changeDose" && "Change dose"}
              {view === "discontinue" && "Discontinue medication"}
              {view === "reactivate" && "Resume medication"}
            </h2>
          </div>
          <button
            type="button"
            onClick={handleClose}
            className="rounded-lg p-1.5 text-tertiary hover:bg-canvas-sunken hover:text-secondary"
          >
            <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
              <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>

        {/* Content */}
        <div className="max-h-[60vh] overflow-y-auto p-5">
          {view === "detail" && (
            <div className="space-y-5">
              {/* Drug image and info */}
              <div className="flex gap-4">
                <div className="h-24 w-24 flex-shrink-0 overflow-hidden rounded-lg bg-canvas-sunken">
                  <DrugImage
                    imageUrl={drug?.imageUrl ?? null}
                    fallbackUrl={drug?.imageFallback ?? null}
                    form={form}
                    name={name}
                    className="h-full w-full"
                  />
                </div>
                <div className="flex-1 space-y-1">
                  <div className="flex items-start justify-between gap-2">
                    <h3 className="text-[16px] font-medium text-primary">{name}</h3>
                    {status === "DISCONTINUED" && (
                      <span className="rounded-full bg-alert/10 px-2 py-0.5 text-[10px] font-medium uppercase tracking-wide text-alert">
                        Discontinued
                      </span>
                    )}
                  </div>
                  {category && (
                    <div className="text-[12px] text-tertiary">
                      {CATEGORY_LABELS[category]} · {FORM_LABELS[form]}
                    </div>
                  )}
                  <div className="text-[14px] text-secondary">
                    <span className="font-mono">{medication.dose} {medication.unit}</span>
                    <span className="mx-1.5 text-tertiary">·</span>
                    <span>{formatFrequency(medication.frequency)}</span>
                  </div>
                </div>
              </div>

              {/* Dosing history */}
              {medication.dosagePeriods && medication.dosagePeriods.length > 1 && (
                <div>
                  <label className="mb-1.5 block text-[12px] font-medium text-secondary">
                    Dosing history
                  </label>
                  <div className="space-y-1.5">
                    {[...medication.dosagePeriods]
                      .sort((a, b) => b.startDate.localeCompare(a.startDate))
                      .map((p, i) => (
                        <div
                          key={`${p.startDate}-${i}`}
                          className="flex items-center justify-between rounded-lg bg-canvas-sunken px-2.5 py-1.5"
                        >
                          <span className="font-mono text-[13px] text-primary">
                            {p.dose} {p.unit}
                          </span>
                          <span className="flex items-center text-[12px] text-tertiary">
                            {formatPeriodRange(p)}
                            {p.endDate === null && (
                              <span className="ml-2 rounded-full bg-accent/10 px-1.5 py-0.5 text-[10px] font-medium uppercase tracking-wide text-accent">
                                Current
                              </span>
                            )}
                          </span>
                        </div>
                      ))}
                  </div>
                </div>
              )}

              {/* Time slots */}
              {medication.timeSlots.length > 0 && (
                <div>
                  <label className="mb-1.5 block text-[12px] font-medium text-secondary">
                    Time of day
                  </label>
                  <div className="flex flex-wrap gap-1.5">
                    {medication.timeSlots.map((slot) => (
                      <span
                        key={slot.window}
                        className="rounded-lg bg-canvas-sunken px-2.5 py-1 text-[12px] font-medium text-secondary"
                      >
                        {TIME_WINDOW_LABELS[slot.window]}
                        {slot.dose !== medication.dose && (
                          <span className="ml-1 font-mono text-tertiary">
                            ({slot.dose} {medication.unit})
                          </span>
                        )}
                      </span>
                    ))}
                  </div>
                </div>
              )}

              {/* Adherence */}
              {medication.adherence && status === "ACTIVE" && (
                <div>
                  <label className="mb-1.5 block text-[12px] font-medium text-secondary">
                    30-day adherence
                  </label>
                  <AdherenceSparkline
                    data={medication.adherence.last30Days}
                    percentage={medication.adherence.percentage}
                  />
                </div>
              )}

              {/* Correlated markers */}
              {medication.correlatedMarkers && medication.correlatedMarkers.length > 0 && (
                <div>
                  <label className="mb-1.5 block text-[12px] font-medium text-secondary">
                    Blood markers to track
                  </label>
                  <div className="flex flex-wrap gap-1.5">
                    {medication.correlatedMarkers.map((marker) => (
                      <span
                        key={marker}
                        className="rounded-full bg-canvas-sunken px-2 py-0.5 text-[11px] font-medium text-tertiary"
                      >
                        {marker}
                      </span>
                    ))}
                  </div>
                </div>
              )}

              {/* Notes */}
              {medication.notes && (
                <div>
                  <label className="mb-1.5 block text-[12px] font-medium text-secondary">
                    Notes
                  </label>
                  <p className="text-[13px] text-primary">{medication.notes}</p>
                </div>
              )}

              {/* Prescribed by */}
              {medication.prescribedBy && (
                <div>
                  <label className="mb-1.5 block text-[12px] font-medium text-secondary">
                    Prescribed by
                  </label>
                  <p className="text-[13px] text-primary">{medication.prescribedBy}</p>
                </div>
              )}

              {/* Start/End dates */}
              <div className="flex gap-6">
                <div>
                  <label className="mb-0.5 block text-[11px] font-medium text-tertiary">
                    Started
                  </label>
                  <p className="text-[13px] text-primary">
                    {new Date(medication.startDate).toLocaleDateString()}
                  </p>
                </div>
                {medication.endDate && (
                  <div>
                    <label className="mb-0.5 block text-[11px] font-medium text-tertiary">
                      Ended
                    </label>
                    <p className="text-[13px] text-primary">
                      {new Date(medication.endDate).toLocaleDateString()}
                    </p>
                  </div>
                )}
              </div>

              {/* Discontinue reason */}
              {medication.discontinueReason && (
                <div>
                  <label className="mb-0.5 block text-[11px] font-medium text-tertiary">
                    Reason for discontinuing
                  </label>
                  <p className="text-[13px] text-primary">
                    {DISCONTINUE_REASON_LABELS[medication.discontinueReason]}
                    {medication.discontinueNotes && (
                      <span className="text-secondary"> — {medication.discontinueNotes}</span>
                    )}
                  </p>
                </div>
              )}
            </div>
          )}

          {view === "edit" && (
            <div className="space-y-4">
              <p className="rounded-lg bg-canvas-sunken px-3 py-2 text-[12px] text-tertiary">
                Current dose is{" "}
                <span className="font-mono text-secondary">
                  {medication.dose} {medication.unit}
                </span>
                . Use <span className="font-medium text-secondary">Change dose</span> to
                adjust it and keep a dated history.
              </p>

              {/* Frequency */}
              <div>
                <label className="mb-1.5 block text-[12px] font-medium text-secondary">
                  Frequency
                </label>
                <div className="flex gap-2">
                  <select
                    value={frequencyType}
                    onChange={(e) => setFrequencyType(e.target.value as FrequencyType)}
                    className="flex-1 rounded-lg border border-border-default bg-canvas px-3 py-2 text-[14px] text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                  >
                    {Object.entries(FREQUENCY_LABELS).map(([key, label]) => (
                      <option key={key} value={key}>
                        {label}
                      </option>
                    ))}
                  </select>
                  {(frequencyType === "DAILY" || frequencyType === "WEEKLY") && (
                    <input
                      type="number"
                      value={timesPerPeriod}
                      onChange={(e) => setTimesPerPeriod(e.target.value)}
                      min="1"
                      max="10"
                      className="w-16 rounded-lg border border-border-default bg-canvas px-3 py-2 text-[14px] text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                    />
                  )}
                </div>
              </div>

              {/* Time windows */}
              {frequencyType !== "PRN" && (
                <div>
                  <label className="mb-1.5 block text-[12px] font-medium text-secondary">
                    Time of day
                  </label>
                  <div className="flex flex-wrap gap-2">
                    {(Object.keys(TIME_WINDOW_LABELS) as TimeWindow[]).map((window) => (
                      <button
                        key={window}
                        type="button"
                        onClick={() => toggleWindow(window)}
                        className={`rounded-lg px-3 py-1.5 text-[13px] font-medium transition-colors ${
                          selectedWindows.includes(window)
                            ? "bg-accent text-inverse"
                            : "bg-canvas-sunken text-secondary hover:bg-canvas hover:text-primary"
                        }`}
                      >
                        {TIME_WINDOW_LABELS[window]}
                      </button>
                    ))}
                  </div>
                </div>
              )}

              {/* Start date */}
              <div>
                <label className="mb-1.5 block text-[12px] font-medium text-secondary">
                  Start date
                </label>
                <input
                  type="date"
                  value={startDate}
                  onChange={(e) => setStartDate(e.target.value)}
                  className="w-full rounded-lg border border-border-default bg-canvas px-3 py-2 text-[14px] text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                />
              </div>

              {/* Prescribed by */}
              <div>
                <label className="mb-1.5 block text-[12px] font-medium text-secondary">
                  Prescribed by
                </label>
                <input
                  type="text"
                  value={prescribedBy}
                  onChange={(e) => setPrescribedBy(e.target.value)}
                  placeholder="Dr. Smith"
                  className="w-full rounded-lg border border-border-default bg-canvas px-3 py-2 text-[14px] text-primary placeholder:text-tertiary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                />
              </div>

              {/* Notes */}
              <div>
                <label className="mb-1.5 block text-[12px] font-medium text-secondary">
                  Notes
                </label>
                <textarea
                  value={notes}
                  onChange={(e) => setNotes(e.target.value)}
                  placeholder="Any additional notes..."
                  rows={2}
                  className="w-full rounded-lg border border-border-default bg-canvas px-3 py-2 text-[14px] text-primary placeholder:text-tertiary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                />
              </div>

              {/* Change notes (for history) */}
              <div>
                <label className="mb-1.5 block text-[12px] font-medium text-secondary">
                  Reason for change (optional)
                </label>
                <input
                  type="text"
                  value={changeNotes}
                  onChange={(e) => setChangeNotes(e.target.value)}
                  placeholder="e.g., Adjusting dose per lab results"
                  className="w-full rounded-lg border border-border-default bg-canvas px-3 py-2 text-[14px] text-primary placeholder:text-tertiary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                />
              </div>
            </div>
          )}

          {view === "changeDose" && (
            <div className="space-y-4">
              <p className="text-[13px] text-secondary">
                Current dose:{" "}
                <span className="font-mono text-primary">
                  {medication.dose} {medication.unit}
                </span>
                . The new dose takes effect on the date below; the current dose is
                recorded up to that date in the dosing history.
              </p>

              {/* New dose */}
              <div>
                <label className="mb-1.5 block text-[12px] font-medium text-secondary">
                  New dose
                </label>
                <div className="flex gap-2">
                  <input
                    type="number"
                    value={newDose}
                    onChange={(e) => setNewDose(e.target.value)}
                    placeholder="50"
                    className="flex-1 rounded-lg border border-border-default bg-canvas px-3 py-2 text-[14px] text-primary placeholder:text-tertiary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                  />
                  <input
                    type="text"
                    value={newDoseUnit}
                    onChange={(e) => setNewDoseUnit(e.target.value)}
                    className="w-20 rounded-lg border border-border-default bg-canvas px-3 py-2 text-[14px] text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                  />
                </div>
              </div>

              {/* Effective date */}
              <div>
                <label className="mb-1.5 block text-[12px] font-medium text-secondary">
                  Effective from
                </label>
                <input
                  type="date"
                  value={doseEffectiveDate}
                  onChange={(e) => setDoseEffectiveDate(e.target.value)}
                  className="w-full rounded-lg border border-border-default bg-canvas px-3 py-2 text-[14px] text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                />
              </div>

              {/* Reason */}
              <div>
                <label className="mb-1.5 block text-[12px] font-medium text-secondary">
                  Reason for change (optional)
                </label>
                <input
                  type="text"
                  value={doseChangeNotes}
                  onChange={(e) => setDoseChangeNotes(e.target.value)}
                  placeholder="e.g., Increased per lab results"
                  className="w-full rounded-lg border border-border-default bg-canvas px-3 py-2 text-[14px] text-primary placeholder:text-tertiary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                />
              </div>
            </div>
          )}

          {view === "discontinue" && (
            <div className="space-y-4">
              <p className="text-[13px] text-secondary">
                This will mark <span className="font-medium text-primary">{name}</span> as discontinued.
                It will move to your medication history.
              </p>

              {/* Reason */}
              <div>
                <label className="mb-1.5 block text-[12px] font-medium text-secondary">
                  Reason
                </label>
                <select
                  value={discontinueReason}
                  onChange={(e) => setDiscontinueReason(e.target.value as DiscontinueReason)}
                  className="w-full rounded-lg border border-border-default bg-canvas px-3 py-2 text-[14px] text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                >
                  {Object.entries(DISCONTINUE_REASON_LABELS).map(([key, label]) => (
                    <option key={key} value={key}>
                      {label}
                    </option>
                  ))}
                </select>
              </div>

              {/* End date */}
              <div>
                <label className="mb-1.5 block text-[12px] font-medium text-secondary">
                  End date
                </label>
                <input
                  type="date"
                  value={discontinueDate}
                  onChange={(e) => setDiscontinueDate(e.target.value)}
                  className="w-full rounded-lg border border-border-default bg-canvas px-3 py-2 text-[14px] text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                />
              </div>

              {/* Notes */}
              <div>
                <label className="mb-1.5 block text-[12px] font-medium text-secondary">
                  Additional notes (optional)
                </label>
                <textarea
                  value={discontinueNotes}
                  onChange={(e) => setDiscontinueNotes(e.target.value)}
                  placeholder="Any additional context..."
                  rows={3}
                  className="w-full rounded-lg border border-border-default bg-canvas px-3 py-2 text-[14px] text-primary placeholder:text-tertiary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                />
              </div>
            </div>
          )}

          {view === "reactivate" && (
            <div className="space-y-4">
              <p className="text-[13px] text-secondary">
                Resume <span className="font-medium text-primary">{name}</span> at its last
                dose of{" "}
                <span className="font-mono text-primary">
                  {medication.dose} {medication.unit}
                </span>
                . A new dosing period opens from the date below; the pause stays in the
                dosing history.
              </p>

              {/* Resume date */}
              <div>
                <label className="mb-1.5 block text-[12px] font-medium text-secondary">
                  Resume from
                </label>
                <input
                  type="date"
                  value={resumeDate}
                  onChange={(e) => setResumeDate(e.target.value)}
                  className="w-full rounded-lg border border-border-default bg-canvas px-3 py-2 text-[14px] text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                />
              </div>
            </div>
          )}
        </div>

        {/* Footer */}
        <div className="flex items-center justify-between border-t border-border-subtle px-5 py-4">
          {view === "detail" && (
            <>
              {status === "ACTIVE" ? (
                <div className="flex gap-2">
                  <button
                    type="button"
                    onClick={() => setView("discontinue")}
                    className="text-[13px] font-medium text-secondary hover:text-alert"
                  >
                    Discontinue
                  </button>
                  <span className="text-tertiary">·</span>
                  <button
                    type="button"
                    onClick={handleDelete}
                    disabled={isPending}
                    className="text-[13px] font-medium text-secondary hover:text-alert"
                  >
                    Delete
                  </button>
                </div>
              ) : (
                <button
                  type="button"
                  onClick={handleDelete}
                  disabled={isPending}
                  className="text-[13px] font-medium text-secondary hover:text-alert"
                >
                  Delete
                </button>
              )}
              {status === "DISCONTINUED" && (
                <button
                  type="button"
                  onClick={() => {
                    setResumeDate(new Date().toISOString().slice(0, 10));
                    setView("reactivate");
                  }}
                  className="rounded-lg bg-accent px-4 py-2 text-[13px] font-medium text-inverse transition-colors hover:bg-accent-dim"
                >
                  Resume
                </button>
              )}
              {status === "ACTIVE" && (
                <div className="flex gap-2">
                  <button
                    type="button"
                    onClick={() => {
                      setNewDose("");
                      setNewDoseUnit(medication.unit);
                      setDoseChangeNotes("");
                      setView("changeDose");
                    }}
                    className="rounded-lg border border-border-default bg-canvas px-4 py-2 text-[13px] font-medium text-secondary transition-colors hover:bg-canvas-sunken"
                  >
                    Change dose
                  </button>
                  <button
                    type="button"
                    onClick={() => setView("edit")}
                    className="rounded-lg bg-accent px-4 py-2 text-[13px] font-medium text-inverse transition-colors hover:bg-accent-dim"
                  >
                    Edit
                  </button>
                </div>
              )}
            </>
          )}

          {view === "changeDose" && (
            <>
              <button
                type="button"
                onClick={() => setView("detail")}
                className="text-[13px] font-medium text-secondary hover:text-primary"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={handleChangeDose}
                disabled={isPending}
                className="rounded-lg bg-accent px-4 py-2 text-[13px] font-medium text-inverse transition-colors hover:bg-accent-dim disabled:opacity-50"
              >
                {isPending ? "Saving..." : "Save dose"}
              </button>
            </>
          )}

          {view === "edit" && (
            <>
              <button
                type="button"
                onClick={() => setView("detail")}
                className="text-[13px] font-medium text-secondary hover:text-primary"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={handleSaveEdit}
                disabled={isPending}
                className="rounded-lg bg-accent px-4 py-2 text-[13px] font-medium text-inverse transition-colors hover:bg-accent-dim disabled:opacity-50"
              >
                {isPending ? "Saving..." : "Save changes"}
              </button>
            </>
          )}

          {view === "discontinue" && (
            <>
              <button
                type="button"
                onClick={() => setView("detail")}
                className="text-[13px] font-medium text-secondary hover:text-primary"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={handleDiscontinue}
                disabled={isPending}
                className="rounded-lg bg-alert px-4 py-2 text-[13px] font-medium text-white transition-colors hover:bg-alert/90 disabled:opacity-50"
              >
                {isPending ? "Discontinuing..." : "Discontinue"}
              </button>
            </>
          )}

          {view === "reactivate" && (
            <>
              <button
                type="button"
                onClick={() => setView("detail")}
                className="text-[13px] font-medium text-secondary hover:text-primary"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={handleReactivate}
                disabled={isPending}
                className="rounded-lg bg-accent px-4 py-2 text-[13px] font-medium text-inverse transition-colors hover:bg-accent-dim disabled:opacity-50"
              >
                {isPending ? "Resuming..." : "Resume"}
              </button>
            </>
          )}
        </div>
      </div>
    </div>
  );
}
