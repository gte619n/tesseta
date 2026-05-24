"use client";

import { useState, useTransition, useEffect, useRef } from "react";
import type { Drug, DrugCategory, DrugForm, FrequencyType, TimeWindow, TimeSlot, DayOfWeek } from "@/lib/types/medication";
import {
  CATEGORY_LABELS,
  FORM_LABELS,
  FREQUENCY_LABELS,
  TIME_WINDOW_LABELS,
  DAY_LABELS,
} from "@/lib/types/medication";

interface AddMedicationButtonProps {
  addMedication: (formData: FormData) => Promise<void>;
  lookupDrug: (query: string) => Promise<Drug | null>;
  drugs: Drug[];
}

export function AddMedicationButton({ addMedication, lookupDrug, drugs }: AddMedicationButtonProps) {
  const [open, setOpen] = useState(false);
  const [isPending, startTransition] = useTransition();
  const [isLookingUp, setIsLookingUp] = useState(false);
  const [step, setStep] = useState<"search" | "form" | "custom">("search");
  const [selectedDrug, setSelectedDrug] = useState<Drug | null>(null);
  const [searchQuery, setSearchQuery] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [lookupMessage, setLookupMessage] = useState<string | null>(null);

  // Form state
  const [dose, setDose] = useState("");
  const [unit, setUnit] = useState("");
  const [frequencyType, setFrequencyType] = useState<FrequencyType>("DAILY");
  const [timesPerPeriod, setTimesPerPeriod] = useState("1");
  const [selectedWindows, setSelectedWindows] = useState<TimeWindow[]>(["MORNING"]);
  const [selectedDays, setSelectedDays] = useState<DayOfWeek[]>([]); // For weekly
  const [dayOfMonth, setDayOfMonth] = useState("1"); // For monthly
  const [notes, setNotes] = useState("");
  const [prescribedBy, setPrescribedBy] = useState("");

  // Custom drug form state
  const [customName, setCustomName] = useState("");
  const [customCategory, setCustomCategory] = useState<DrugCategory>("SUPPLEMENT");
  const [customForm, setCustomForm] = useState<DrugForm>("TABLET");
  const [customUnit, setCustomUnit] = useState("mg");

  // Filter drugs based on search
  const filteredDrugs = searchQuery.trim()
    ? drugs.filter(
        (d) =>
          d.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
          d.aliases.some((a) => a.toLowerCase().includes(searchQuery.toLowerCase()))
      )
    : drugs;

  // Track which queries we've already looked up to avoid duplicate calls
  const lookupAttemptedRef = useRef<string | null>(null);

  // Auto-trigger AI lookup when no catalog results found
  useEffect(() => {
    const query = searchQuery.trim();
    if (
      query &&
      query.length >= 3 &&
      filteredDrugs.length === 0 &&
      !isLookingUp &&
      step === "search" &&
      lookupAttemptedRef.current !== query
    ) {
      lookupAttemptedRef.current = query;
      handleAILookup();
    }
  }, [searchQuery, filteredDrugs.length, isLookingUp, step]);

  async function handleAILookup() {
    if (!searchQuery.trim()) return;

    setIsLookingUp(true);
    setError(null);
    setLookupMessage("Searching...");

    try {
      // Use SSE endpoint for real-time progress updates
      const response = await fetch("/api/drugs/lookup/stream", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ query: searchQuery }),
      });

      if (!response.ok) {
        throw new Error("Lookup failed");
      }

      const reader = response.body?.getReader();
      if (!reader) {
        throw new Error("No response body");
      }

      const decoder = new TextDecoder();
      let buffer = "";

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;

        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split("\n");
        buffer = lines.pop() || "";

        for (const line of lines) {
          if (line.startsWith("data:")) {
            try {
              const data = JSON.parse(line.slice(5).trim());
              if (data.phase === "complete" && data.drug) {
                setLookupMessage(null);
                handleSelectDrug(data.drug as Drug);
                setIsLookingUp(false);
                return;
              } else if (data.phase === "not_found" || data.phase === "failed") {
                setLookupMessage(data.message || data.error || "Not found. You can add it manually below.");
                setIsLookingUp(false);
                return;
              } else if (data.message) {
                setLookupMessage(data.message);
              }
            } catch {
              // Ignore parse errors for partial data
            }
          }
        }
      }

      // If we get here without finding a drug, show not found
      setLookupMessage("Not found. You can add it manually below.");
    } catch (e) {
      setError(e instanceof Error ? e.message : "Lookup failed");
      setLookupMessage(null);
    } finally {
      setIsLookingUp(false);
    }
  }

  function handleSelectDrug(drug: Drug) {
    setSelectedDrug(drug);
    setUnit(drug.defaultUnit);
    setStep("form");
    setLookupMessage(null);
  }

  function handleCustomEntry() {
    setCustomName(searchQuery);
    setStep("custom");
    setLookupMessage(null);
  }

  function handleBack() {
    if (step === "custom") {
      setStep("search");
      setCustomName("");
    } else {
      setStep("search");
      setSelectedDrug(null);
    }
    setError(null);
  }

  function handleClose() {
    setOpen(false);
    setStep("search");
    setSelectedDrug(null);
    setSearchQuery("");
    setDose("");
    setUnit("");
    setFrequencyType("DAILY");
    setTimesPerPeriod("1");
    setSelectedWindows(["MORNING"]);
    setSelectedDays([]);
    setDayOfMonth("1");
    setNotes("");
    setPrescribedBy("");
    setCustomName("");
    setCustomCategory("SUPPLEMENT");
    setCustomForm("TABLET");
    setCustomUnit("mg");
    setError(null);
    setLookupMessage(null);
  }

  function toggleDay(day: DayOfWeek) {
    setSelectedDays((prev) =>
      prev.includes(day)
        ? prev.filter((d) => d !== day)
        : [...prev, day]
    );
  }

  function toggleWindow(window: TimeWindow) {
    setSelectedWindows((prev) =>
      prev.includes(window)
        ? prev.filter((w) => w !== window)
        : [...prev, window]
    );
  }

  function handleSubmit() {
    if (!selectedDrug) return;
    if (!dose || Number(dose) <= 0) {
      setError("Dose is required");
      return;
    }

    const formData = new FormData();
    formData.set("drugId", selectedDrug.drugId);
    formData.set("dose", dose);
    formData.set("unit", unit || selectedDrug.defaultUnit);
    formData.set("frequencyType", frequencyType);
    formData.set("timesPerPeriod", timesPerPeriod);

    // Add specific days for weekly frequency
    if (frequencyType === "WEEKLY" && selectedDays.length > 0) {
      formData.set("specificDays", JSON.stringify(selectedDays));
    }

    // Add day of month for monthly frequency
    if (frequencyType === "MONTHLY") {
      formData.set("dayOfMonth", dayOfMonth);
    }

    // Build time slots from selected windows
    const timeSlots: TimeSlot[] = selectedWindows.map((window) => ({
      window,
      dose: Number(dose) / selectedWindows.length,
    }));
    formData.set("timeSlots", JSON.stringify(timeSlots));
    formData.set("correlatedMarkers", JSON.stringify(selectedDrug.suggestedMarkers || []));
    if (notes) formData.set("notes", notes);
    if (prescribedBy) formData.set("prescribedBy", prescribedBy);

    startTransition(async () => {
      try {
        await addMedication(formData);
        handleClose();
      } catch (e) {
        setError(e instanceof Error ? e.message : "Failed to add medication");
      }
    });
  }

  function handleCustomSubmit() {
    if (!customName.trim()) {
      setError("Name is required");
      return;
    }
    if (!dose || Number(dose) <= 0) {
      setError("Dose is required");
      return;
    }

    const formData = new FormData();
    // No drugId - backend will create a private entry
    formData.set("customName", customName.trim());
    formData.set("customCategory", customCategory);
    formData.set("customForm", customForm);
    formData.set("dose", dose);
    formData.set("unit", customUnit || "mg");
    formData.set("frequencyType", frequencyType);
    formData.set("timesPerPeriod", timesPerPeriod);

    // Add specific days for weekly frequency
    if (frequencyType === "WEEKLY" && selectedDays.length > 0) {
      formData.set("specificDays", JSON.stringify(selectedDays));
    }

    // Add day of month for monthly frequency
    if (frequencyType === "MONTHLY") {
      formData.set("dayOfMonth", dayOfMonth);
    }

    const timeSlots: TimeSlot[] = selectedWindows.map((window) => ({
      window,
      dose: Number(dose) / selectedWindows.length,
    }));
    formData.set("timeSlots", JSON.stringify(timeSlots));
    formData.set("correlatedMarkers", JSON.stringify([]));
    if (notes) formData.set("notes", notes);
    if (prescribedBy) formData.set("prescribedBy", prescribedBy);

    startTransition(async () => {
      try {
        await addMedication(formData);
        handleClose();
      } catch (e) {
        setError(e instanceof Error ? e.message : "Failed to add medication");
      }
    });
  }

  const showNoResults = searchQuery.trim() && filteredDrugs.length === 0;

  return (
    <>
      <button
        type="button"
        onClick={() => setOpen(true)}
        className="cursor-pointer inline-flex items-center gap-1.5 rounded-md bg-accent px-4 py-2 text-[13px] font-medium text-inverse"
      >
        <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
          <path strokeLinecap="round" strokeLinejoin="round" d="M12 4v16m8-8H4" />
        </svg>
        Add medication
      </button>

      {open && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4">
          <div className="w-full max-w-md rounded-xl bg-surface shadow-xl">
            {/* Header */}
            <div className="flex items-center justify-between border-b border-border-subtle px-5 py-4">
              <h2 className="text-[16px] font-medium text-primary">
                {step === "search" ? "Add medication" : step === "custom" ? "Add custom medication" : selectedDrug?.name}
              </h2>
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
              {step === "search" ? (
                <div className="space-y-4">
                  {/* Search input */}
                  <div>
                    <input
                      type="text"
                      placeholder="Search medications..."
                      value={searchQuery}
                      onChange={(e) => {
                        setSearchQuery(e.target.value);
                        setLookupMessage(null);
                      }}
                      onKeyDown={(e) => {
                        if (e.key === "Enter" && showNoResults) {
                          handleAILookup();
                        }
                      }}
                      className="w-full rounded-lg border border-border-default bg-canvas px-3 py-2 text-[14px] text-primary placeholder:text-tertiary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                      autoFocus
                    />
                  </div>

                  {/* Lookup message */}
                  {lookupMessage && (
                    <p className="text-center text-[13px] text-secondary">
                      {lookupMessage}
                    </p>
                  )}

                  {/* Error */}
                  {error && (
                    <div className="rounded-lg bg-alert/10 px-3 py-2 text-[13px] text-alert">
                      {error}
                    </div>
                  )}

                  {/* Drug list */}
                  <div className="space-y-1">
                    {filteredDrugs.length > 0 ? (
                      filteredDrugs.slice(0, 10).map((drug) => (
                        <button
                          key={drug.drugId}
                          type="button"
                          onClick={() => handleSelectDrug(drug)}
                          className="flex w-full items-center gap-3 rounded-lg px-3 py-2 text-left hover:bg-canvas-sunken"
                        >
                          <div className="flex-1">
                            <div className="text-[14px] font-medium text-primary">
                              {drug.name}
                            </div>
                            <div className="text-[12px] text-tertiary">
                              {CATEGORY_LABELS[drug.category]} · {FORM_LABELS[drug.form]}
                            </div>
                          </div>
                          <svg className="h-4 w-4 text-tertiary" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                            <path strokeLinecap="round" strokeLinejoin="round" d="M9 5l7 7-7 7" />
                          </svg>
                        </button>
                      ))
                    ) : searchQuery.trim() ? (
                      <div className="space-y-3 py-4">
                        <p className="text-center text-[13px] text-secondary">
                          No medications found in catalog.
                        </p>

                        {/* AI Lookup button */}
                        <button
                          type="button"
                          onClick={handleAILookup}
                          disabled={isLookingUp}
                          className="flex w-full items-center justify-center gap-2 rounded-lg border border-border-default bg-canvas px-4 py-3 text-[13px] font-medium text-primary hover:bg-canvas-sunken disabled:opacity-50"
                        >
                          {isLookingUp ? (
                            <>
                              <svg className="h-4 w-4 animate-spin" viewBox="0 0 24 24" fill="none">
                                <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                                <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" />
                              </svg>
                              Searching...
                            </>
                          ) : (
                            <>
                              <svg className="h-4 w-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2}>
                                <path strokeLinecap="round" strokeLinejoin="round" d="M9.663 17h4.673M12 3v1m6.364 1.636l-.707.707M21 12h-1M4 12H3m3.343-5.657l-.707-.707m2.828 9.9a5 5 0 117.072 0l-.548.547A3.374 3.374 0 0014 18.469V19a2 2 0 11-4 0v-.531c0-.895-.356-1.754-.988-2.386l-.548-.547z" />
                              </svg>
                              Search with AI
                            </>
                          )}
                        </button>

                        {/* Manual entry option */}
                        <button
                          type="button"
                          onClick={handleCustomEntry}
                          className="flex w-full items-center justify-center gap-2 rounded-lg px-4 py-2 text-[13px] font-medium text-secondary hover:text-primary"
                        >
                          <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                            <path strokeLinecap="round" strokeLinejoin="round" d="M12 4v16m8-8H4" />
                          </svg>
                          Add manually instead
                        </button>
                      </div>
                    ) : (
                      <p className="py-4 text-center text-[13px] text-secondary">
                        Type to search for a medication
                      </p>
                    )}
                  </div>
                </div>
              ) : step === "custom" ? (
                <div className="space-y-4">
                  {error && (
                    <div className="rounded-lg bg-alert/10 px-3 py-2 text-[13px] text-alert">
                      {error}
                    </div>
                  )}

                  {/* Custom name */}
                  <div>
                    <label className="mb-1.5 block text-[12px] font-medium text-secondary">
                      Medication name
                    </label>
                    <input
                      type="text"
                      value={customName}
                      onChange={(e) => setCustomName(e.target.value)}
                      placeholder="e.g., Vitamin D3"
                      className="w-full rounded-lg border border-border-default bg-canvas px-3 py-2 text-[14px] text-primary placeholder:text-tertiary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                      autoFocus
                    />
                  </div>

                  {/* Category & Form */}
                  <div className="grid grid-cols-2 gap-3">
                    <div>
                      <label className="mb-1.5 block text-[12px] font-medium text-secondary">
                        Category
                      </label>
                      <select
                        value={customCategory}
                        onChange={(e) => setCustomCategory(e.target.value as DrugCategory)}
                        className="w-full rounded-lg border border-border-default bg-canvas px-3 py-2 text-[14px] text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                      >
                        {Object.entries(CATEGORY_LABELS).map(([key, label]) => (
                          <option key={key} value={key}>
                            {label}
                          </option>
                        ))}
                      </select>
                    </div>
                    <div>
                      <label className="mb-1.5 block text-[12px] font-medium text-secondary">
                        Form
                      </label>
                      <select
                        value={customForm}
                        onChange={(e) => setCustomForm(e.target.value as DrugForm)}
                        className="w-full rounded-lg border border-border-default bg-canvas px-3 py-2 text-[14px] text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                      >
                        {Object.entries(FORM_LABELS).map(([key, label]) => (
                          <option key={key} value={key}>
                            {label}
                          </option>
                        ))}
                      </select>
                    </div>
                  </div>

                  {/* Dose */}
                  <div>
                    <label className="mb-1.5 block text-[12px] font-medium text-secondary">
                      Dose
                    </label>
                    <div className="flex gap-2">
                      <input
                        type="number"
                        value={dose}
                        onChange={(e) => setDose(e.target.value)}
                        placeholder="200"
                        className="flex-1 rounded-lg border border-border-default bg-canvas px-3 py-2 text-[14px] text-primary placeholder:text-tertiary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                      />
                      <input
                        type="text"
                        value={customUnit}
                        onChange={(e) => setCustomUnit(e.target.value)}
                        placeholder="mg"
                        className="w-20 rounded-lg border border-border-default bg-canvas px-3 py-2 text-[14px] text-primary placeholder:text-tertiary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                      />
                    </div>
                  </div>

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
                            className={`rounded-md px-3 py-1.5 text-[13px] font-medium transition-colors ${
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

                  {/* Day of week selection for weekly frequency */}
                  {frequencyType === "WEEKLY" && (
                    <div>
                      <label className="mb-1.5 block text-[12px] font-medium text-secondary">
                        Days of the week
                      </label>
                      <div className="flex flex-wrap gap-2">
                        {(Object.keys(DAY_LABELS) as DayOfWeek[]).map((day) => (
                          <button
                            key={day}
                            type="button"
                            onClick={() => toggleDay(day)}
                            className={`rounded-md px-3 py-1.5 text-[13px] font-medium transition-colors ${
                              selectedDays.includes(day)
                                ? "bg-accent text-inverse"
                                : "bg-canvas-sunken text-secondary hover:bg-canvas hover:text-primary"
                            }`}
                          >
                            {DAY_LABELS[day]}
                          </button>
                        ))}
                      </div>
                    </div>
                  )}

                  {/* Day of month selection for monthly frequency */}
                  {frequencyType === "MONTHLY" && (
                    <div>
                      <label className="mb-1.5 block text-[12px] font-medium text-secondary">
                        Day of the month
                      </label>
                      <select
                        value={dayOfMonth}
                        onChange={(e) => setDayOfMonth(e.target.value)}
                        className="w-full rounded-lg border border-border-default bg-canvas px-3 py-2 text-[14px] text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                      >
                        {Array.from({ length: 28 }, (_, i) => i + 1).map((day) => (
                          <option key={day} value={day}>
                            {day === 1 ? "1st" : day === 2 ? "2nd" : day === 3 ? "3rd" : `${day}th`}
                          </option>
                        ))}
                      </select>
                    </div>
                  )}

                  {/* Advanced section */}
                  <details className="group">
                    <summary className="flex cursor-pointer items-center gap-2 text-[13px] font-medium text-secondary hover:text-primary">
                      <svg
                        className="h-4 w-4 transition-transform group-open:rotate-90"
                        fill="none"
                        viewBox="0 0 24 24"
                        stroke="currentColor"
                        strokeWidth={2}
                      >
                        <path strokeLinecap="round" strokeLinejoin="round" d="M9 5l7 7-7 7" />
                      </svg>
                      Advanced options
                    </summary>
                    <div className="mt-3 space-y-4 pl-6">
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
                    </div>
                  </details>
                </div>
              ) : (
                <div className="space-y-4">
                  {error && (
                    <div className="rounded-lg bg-alert/10 px-3 py-2 text-[13px] text-alert">
                      {error}
                    </div>
                  )}

                  {/* Dose */}
                  <div>
                    <label className="mb-1.5 block text-[12px] font-medium text-secondary">
                      Dose
                    </label>
                    <div className="flex gap-2">
                      <input
                        type="number"
                        value={dose}
                        onChange={(e) => setDose(e.target.value)}
                        placeholder="200"
                        className="flex-1 rounded-lg border border-border-default bg-canvas px-3 py-2 text-[14px] text-primary placeholder:text-tertiary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                      />
                      <input
                        type="text"
                        value={unit}
                        onChange={(e) => setUnit(e.target.value)}
                        placeholder={selectedDrug?.defaultUnit ?? "mg"}
                        className="w-20 rounded-lg border border-border-default bg-canvas px-3 py-2 text-[14px] text-primary placeholder:text-tertiary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                      />
                    </div>
                  </div>

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
                            className={`rounded-md px-3 py-1.5 text-[13px] font-medium transition-colors ${
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

                  {/* Day of week selection for weekly frequency */}
                  {frequencyType === "WEEKLY" && (
                    <div>
                      <label className="mb-1.5 block text-[12px] font-medium text-secondary">
                        Days of the week
                      </label>
                      <div className="flex flex-wrap gap-2">
                        {(Object.keys(DAY_LABELS) as DayOfWeek[]).map((day) => (
                          <button
                            key={day}
                            type="button"
                            onClick={() => toggleDay(day)}
                            className={`rounded-md px-3 py-1.5 text-[13px] font-medium transition-colors ${
                              selectedDays.includes(day)
                                ? "bg-accent text-inverse"
                                : "bg-canvas-sunken text-secondary hover:bg-canvas hover:text-primary"
                            }`}
                          >
                            {DAY_LABELS[day]}
                          </button>
                        ))}
                      </div>
                    </div>
                  )}

                  {/* Day of month selection for monthly frequency */}
                  {frequencyType === "MONTHLY" && (
                    <div>
                      <label className="mb-1.5 block text-[12px] font-medium text-secondary">
                        Day of the month
                      </label>
                      <select
                        value={dayOfMonth}
                        onChange={(e) => setDayOfMonth(e.target.value)}
                        className="w-full rounded-lg border border-border-default bg-canvas px-3 py-2 text-[14px] text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                      >
                        {Array.from({ length: 28 }, (_, i) => i + 1).map((day) => (
                          <option key={day} value={day}>
                            {day === 1 ? "1st" : day === 2 ? "2nd" : day === 3 ? "3rd" : `${day}th`}
                          </option>
                        ))}
                      </select>
                    </div>
                  )}

                  {/* Advanced section */}
                  <details className="group">
                    <summary className="flex cursor-pointer items-center gap-2 text-[13px] font-medium text-secondary hover:text-primary">
                      <svg
                        className="h-4 w-4 transition-transform group-open:rotate-90"
                        fill="none"
                        viewBox="0 0 24 24"
                        stroke="currentColor"
                        strokeWidth={2}
                      >
                        <path strokeLinecap="round" strokeLinejoin="round" d="M9 5l7 7-7 7" />
                      </svg>
                      Advanced options
                    </summary>
                    <div className="mt-3 space-y-4 pl-6">
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
                    </div>
                  </details>
                </div>
              )}
            </div>

            {/* Footer */}
            <div className="flex items-center justify-between border-t border-border-subtle px-5 py-4">
              {step === "form" ? (
                <>
                  <button
                    type="button"
                    onClick={handleBack}
                    className="text-[13px] font-medium text-secondary hover:text-primary"
                  >
                    ← Back
                  </button>
                  <button
                    type="button"
                    onClick={handleSubmit}
                    disabled={isPending}
                    className="cursor-pointer rounded-md bg-accent px-4 py-2 text-[13px] font-medium text-inverse disabled:opacity-50"
                  >
                    {isPending ? "Adding..." : "Add medication"}
                  </button>
                </>
              ) : step === "custom" ? (
                <>
                  <button
                    type="button"
                    onClick={handleBack}
                    className="text-[13px] font-medium text-secondary hover:text-primary"
                  >
                    ← Back
                  </button>
                  <button
                    type="button"
                    onClick={handleCustomSubmit}
                    disabled={isPending}
                    className="cursor-pointer rounded-md bg-accent px-4 py-2 text-[13px] font-medium text-inverse disabled:opacity-50"
                  >
                    {isPending ? "Adding..." : "Add medication"}
                  </button>
                </>
              ) : (
                <div className="ml-auto">
                  <button
                    type="button"
                    onClick={handleClose}
                    className="text-[13px] font-medium text-secondary hover:text-primary"
                  >
                    Cancel
                  </button>
                </div>
              )}
            </div>
          </div>
        </div>
      )}
    </>
  );
}
