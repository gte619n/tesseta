"use client";

import { useState, useTransition } from "react";

// Biological sex + date of birth feed the Mifflin-St Jeor calorie estimate.
// Kept alongside HeightForm and PATCHed to the same /api/me endpoint.

export function BodyDetailsForm({
  biologicalSex,
  dateOfBirth,
  saveAction,
}: {
  biologicalSex: string | null;
  dateOfBirth: string | null;
  saveAction: (
    biologicalSex: string | null,
    dateOfBirth: string | null,
  ) => Promise<void>;
}) {
  const [sex, setSex] = useState(biologicalSex ?? "");
  const [dob, setDob] = useState(dateOfBirth ?? "");
  const [error, setError] = useState<string | null>(null);
  const [pending, startTransition] = useTransition();
  const [savedAt, setSavedAt] = useState<number | null>(null);

  function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    startTransition(async () => {
      try {
        await saveAction(sex === "" ? null : sex, dob === "" ? null : dob);
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
          Biological sex
        </span>
        <select
          data-testid="biological-sex"
          value={sex}
          onChange={(e) => setSex(e.target.value)}
          className="w-[120px] rounded-md border-[0.5px] border-border-default bg-canvas px-2 py-1.5 font-mono text-[14px] text-primary outline-none focus:border-accent"
        >
          <option value="">—</option>
          <option value="MALE">Male</option>
          <option value="FEMALE">Female</option>
        </select>
      </label>
      <label className="flex flex-col gap-1">
        <span className="caps-mono text-[10px] tracking-[0.06em] text-tertiary">
          Date of birth
        </span>
        <input
          type="date"
          data-testid="date-of-birth"
          value={dob}
          onChange={(e) => setDob(e.target.value)}
          className="w-[160px] rounded-md border-[0.5px] border-border-default bg-canvas px-2 py-1.5 font-mono text-[14px] text-primary outline-none focus:border-accent"
        />
      </label>
      <button
        type="submit"
        data-testid="body-details-save"
        disabled={pending}
        className="cursor-pointer rounded-md bg-accent px-4 py-1.5 text-[13px] font-medium text-inverse disabled:opacity-60"
      >
        {pending ? "Saving…" : "Save"}
      </button>
      {showSaved && (
        <span
          data-testid="body-details-saved"
          className="font-mono text-[11px] text-tertiary"
        >
          Saved
        </span>
      )}
      {error && (
        <span className="font-mono text-[11px] text-red-600">{error}</span>
      )}
    </form>
  );
}
