"use client";

import { useEffect, useState, useTransition } from "react";
import { useUnits } from "@/components/ui/UnitsProvider";
import { cmToFtIn, ftInToCm } from "@/lib/units";

// Backend stores height in cm. We render inputs according to the user's
// height preference (ft+in or a single cm field) and always round-trip
// to/from cm on save so a value edited as 6'2" persists as 188 cm.

export function HeightForm({
  heightCm,
  saveAction,
}: {
  heightCm: number | null;
  saveAction: (heightCm: number | null) => Promise<void>;
}) {
  const { prefs } = useUnits();

  const initialFtIn = heightCm === null ? { ft: 0, in: 0 } : cmToFtIn(heightCm);
  const [ft, setFt] = useState(heightCm === null ? "" : String(initialFtIn.ft));
  const [inches, setInches] = useState(
    heightCm === null ? "" : String(initialFtIn.in),
  );
  const [cm, setCm] = useState(heightCm === null ? "" : String(heightCm));
  const [error, setError] = useState<string | null>(null);
  const [pending, startTransition] = useTransition();
  const [savedAt, setSavedAt] = useState<number | null>(null);

  // Keep the unused-unit fields in sync when the preference flips, so the
  // form shows the same height in either mode without a re-edit.
  useEffect(() => {
    const parsedCm = parseCm(cm);
    if (prefs.height === "FT_IN") {
      if (parsedCm !== null) {
        const v = cmToFtIn(parsedCm);
        setFt(String(v.ft));
        setInches(String(v.in));
      }
    } else {
      const fromFtIn = parseFtIn(ft, inches);
      if (fromFtIn !== null) setCm(String(fromFtIn));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [prefs.height]);

  function currentCm(): number | null {
    return prefs.height === "CM" ? parseCm(cm) : parseFtIn(ft, inches);
  }

  function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    const next = currentCm();
    if (next !== null && (next < 50 || next > 280)) {
      setError("Height looks off — double-check the values.");
      return;
    }
    startTransition(async () => {
      try {
        await saveAction(next);
        setSavedAt(Date.now());
      } catch (err) {
        setError(err instanceof Error ? err.message : "Save failed");
      }
    });
  }

  const showSaved = savedAt !== null && Date.now() - savedAt < 3000;

  return (
    <form onSubmit={onSubmit} className="flex items-end gap-3">
      {prefs.height === "CM" ? (
        <label className="flex flex-col gap-1">
          <span className="caps-mono text-[10px] tracking-[0.06em] text-tertiary">
            Centimeters
          </span>
          <input
            type="text"
            inputMode="numeric"
            value={cm}
            onChange={(e) => setCm(e.target.value)}
            className="w-[88px] rounded-md border-[0.5px] border-border-default bg-canvas px-2 py-1.5 font-mono text-[14px] text-primary outline-none focus:border-accent"
            placeholder="188"
          />
        </label>
      ) : (
        <>
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
        </>
      )}
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

function parseCm(cmStr: string): number | null {
  if (cmStr.trim() === "") return null;
  const n = Number(cmStr);
  if (!Number.isFinite(n)) return null;
  return Math.round(n);
}

function parseFtIn(ftStr: string, inStr: string): number | null {
  if (ftStr.trim() === "" && inStr.trim() === "") return null;
  const ft = ftStr.trim() === "" ? 0 : Number(ftStr);
  const inches = inStr.trim() === "" ? 0 : Number(inStr);
  if (!Number.isFinite(ft) || !Number.isFinite(inches)) return null;
  return ftInToCm(ft, inches);
}
