"use client";

import { useState, useTransition } from "react";

// Feet + inches input → backend stores cm. We round to nearest cm on
// save; rendering back from cm uses the same conversion, so a value
// edited as 6'2" round-trips as 6'2" (188 cm).
const CM_PER_INCH = 2.54;
const INCHES_PER_FOOT = 12;

function cmToFtIn(cm: number | null): { ft: string; in: string } {
  if (cm === null) return { ft: "", in: "" };
  const totalIn = cm / CM_PER_INCH;
  const ft = Math.floor(totalIn / INCHES_PER_FOOT);
  const inches = Math.round(totalIn - ft * INCHES_PER_FOOT);
  return { ft: String(ft), in: String(inches) };
}

function ftInToCm(ftStr: string, inStr: string): number | null {
  const ft = ftStr.trim() === "" ? 0 : Number(ftStr);
  const inches = inStr.trim() === "" ? 0 : Number(inStr);
  if (!Number.isFinite(ft) || !Number.isFinite(inches)) return null;
  if (ftStr.trim() === "" && inStr.trim() === "") return null;
  return Math.round((ft * INCHES_PER_FOOT + inches) * CM_PER_INCH);
}

export function HeightForm({
  heightCm,
  saveAction,
}: {
  heightCm: number | null;
  saveAction: (heightCm: number | null) => Promise<void>;
}) {
  const initial = cmToFtIn(heightCm);
  const [ft, setFt] = useState(initial.ft);
  const [inches, setInches] = useState(initial.in);
  const [error, setError] = useState<string | null>(null);
  const [pending, startTransition] = useTransition();
  const [savedAt, setSavedAt] = useState<number | null>(null);

  function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    let cm: number | null;
    try {
      cm = ftInToCm(ft, inches);
    } catch {
      setError("Enter a valid height.");
      return;
    }
    if (cm !== null && (cm < 50 || cm > 280)) {
      setError("Height looks off — double-check the values.");
      return;
    }
    startTransition(async () => {
      try {
        await saveAction(cm);
        setSavedAt(Date.now());
      } catch (err) {
        setError(err instanceof Error ? err.message : "Save failed");
      }
    });
  }

  const showSaved = savedAt !== null && Date.now() - savedAt < 3000;

  return (
    <form onSubmit={onSubmit} className="flex items-end gap-3">
      <label className="flex flex-col gap-1">
        <span className="caps-mono text-[10px] tracking-[0.06em] text-tertiary">
          Feet
        </span>
        <input
          type="text"
          inputMode="numeric"
          value={ft}
          onChange={(e) => setFt(e.target.value)}
          className="w-[68px] rounded-md border-[0.5px] border-border-default bg-canvas px-2 py-1.5 font-mono text-[14px] text-primary outline-none focus:border-accent"
          placeholder="6"
        />
      </label>
      <label className="flex flex-col gap-1">
        <span className="caps-mono text-[10px] tracking-[0.06em] text-tertiary">
          Inches
        </span>
        <input
          type="text"
          inputMode="numeric"
          value={inches}
          onChange={(e) => setInches(e.target.value)}
          className="w-[68px] rounded-md border-[0.5px] border-border-default bg-canvas px-2 py-1.5 font-mono text-[14px] text-primary outline-none focus:border-accent"
          placeholder="2"
        />
      </label>
      <button
        type="submit"
        disabled={pending}
        className="cursor-pointer rounded-md bg-accent px-4 py-1.5 text-[13px] font-medium text-inverse disabled:opacity-60"
      >
        {pending ? "Saving…" : "Save"}
      </button>
      {showSaved && (
        <span className="font-mono text-[11px] text-tertiary">Saved</span>
      )}
      {error && (
        <span className="font-mono text-[11px] text-red-600">{error}</span>
      )}
    </form>
  );
}
