"use client";

import { useState, useTransition } from "react";

// Free-text standing preferences the program designer honors on every build
// (exercises to avoid, injuries to work around, training style). Persisted to
// the backend via a server action; a blank box clears the stored value.

const MAX_LENGTH = 2000;

export function WorkoutPreferencesForm({
  preferences,
  saveAction,
}: {
  preferences: string | null;
  saveAction: (preferences: string) => Promise<void>;
}) {
  const initial = preferences ?? "";
  const [text, setText] = useState(initial);
  const [saved, setSaved] = useState(initial);
  const [error, setError] = useState<string | null>(null);
  const [pending, startTransition] = useTransition();
  const [savedAt, setSavedAt] = useState<number | null>(null);

  const dirty = text.trim() !== saved.trim();

  function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    const next = text.trim().slice(0, MAX_LENGTH);
    startTransition(async () => {
      try {
        await saveAction(next);
        setSaved(next);
        setText(next);
        setSavedAt(Date.now());
      } catch (err) {
        setError(err instanceof Error ? err.message : "Save failed");
      }
    });
  }

  const showSaved = savedAt !== null && Date.now() - savedAt < 3000;

  return (
    <form onSubmit={onSubmit} className="flex flex-col gap-3">
      <label className="flex flex-col gap-1.5">
        <span className="caps-mono text-[10px] tracking-[0.06em] text-tertiary">
          Standing preferences
        </span>
        <textarea
          data-testid="workout-preferences"
          value={text}
          maxLength={MAX_LENGTH}
          onChange={(e) => {
            setText(e.target.value);
            setError(null);
          }}
          rows={6}
          placeholder="e.g. No bent-over rows or deadlifts — they hurt my lower back. Prefer machines over free weights for legs. Keep sessions under an hour."
          className="w-full resize-y rounded-md border-[0.5px] border-border-default bg-canvas px-3 py-2 text-[14px] leading-relaxed text-primary outline-none focus:border-accent"
        />
      </label>
      <div className="flex items-center gap-3">
        <button
          type="submit"
          data-testid="workout-preferences-save"
          disabled={pending || !dirty}
          className="cursor-pointer rounded-md bg-accent px-4 py-1.5 text-[13px] font-medium text-inverse disabled:cursor-default disabled:opacity-60"
        >
          {pending ? "Saving…" : "Save"}
        </button>
        {showSaved && (
          <span
            data-testid="workout-preferences-saved"
            className="font-mono text-[11px] text-tertiary"
          >
            Saved
          </span>
        )}
        {error && (
          <span className="font-mono text-[11px] text-red-600">{error}</span>
        )}
      </div>
    </form>
  );
}
