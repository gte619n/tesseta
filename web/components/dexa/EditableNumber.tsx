"use client";

import { useRef, useState, useTransition } from "react";

// Click-to-edit numeric field. Click the value → it becomes a small
// input → Enter or blur saves via the passed Server Action, Escape
// cancels. The component is layout-stable: input and read-mode share
// the same font / size / alignment, so clicking doesn't shift the page.
export function EditableNumber({
  value,
  fractionDigits = 1,
  unit,
  fontClassName = "font-mono text-[20px] font-medium tabular text-primary",
  unitClassName = "ml-1 text-[12px] text-secondary",
  onSave,
}: {
  value: number | null;
  fractionDigits?: number;
  unit?: string;
  fontClassName?: string;
  unitClassName?: string;
  onSave: (next: number | null) => Promise<void>;
}) {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState<string>("");
  const [error, setError] = useState(false);
  const [pending, startTransition] = useTransition();
  const inputRef = useRef<HTMLInputElement>(null);

  function startEdit() {
    setDraft(value === null ? "" : value.toFixed(fractionDigits));
    setError(false);
    setEditing(true);
    // Focus + select on next paint so the user can immediately type.
    setTimeout(() => {
      inputRef.current?.focus();
      inputRef.current?.select();
    }, 0);
  }

  function commit() {
    if (!editing) return;
    const trimmed = draft.trim();
    let next: number | null;
    if (trimmed === "") {
      next = null;
    } else {
      const parsed = Number(trimmed);
      if (!Number.isFinite(parsed)) {
        setError(true);
        return;
      }
      next = parsed;
    }
    // Skip the round trip if nothing changed.
    if (next === value) {
      setEditing(false);
      return;
    }
    setEditing(false);
    startTransition(async () => {
      try {
        await onSave(next);
      } catch {
        setError(true);
        // Drop back into edit mode so the user can retry.
        setEditing(true);
      }
    });
  }

  function cancel() {
    setEditing(false);
    setError(false);
  }

  if (editing) {
    return (
      <span className="inline-flex items-baseline">
        <input
          ref={inputRef}
          type="text"
          inputMode="decimal"
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          onBlur={commit}
          onKeyDown={(e) => {
            if (e.key === "Enter") {
              e.preventDefault();
              commit();
            } else if (e.key === "Escape") {
              e.preventDefault();
              cancel();
            }
          }}
          className={`${fontClassName} ${
            error ? "text-red-600" : ""
          } w-[88px] rounded-[4px] border border-accent bg-canvas px-1 outline-none`}
        />
        {unit && <span className={unitClassName}>{unit}</span>}
      </span>
    );
  }

  return (
    <button
      type="button"
      onClick={startEdit}
      disabled={pending}
      title={pending ? "Saving…" : "Click to edit"}
      className={`${fontClassName} ${
        error ? "text-red-600" : ""
      } rounded-[4px] border border-transparent px-1 hover:border-border-default hover:bg-canvas/40 disabled:opacity-60`}
    >
      {value !== null ? value.toFixed(fractionDigits) : "—"}
      {unit && value !== null && <span className={unitClassName}>{unit}</span>}
    </button>
  );
}
