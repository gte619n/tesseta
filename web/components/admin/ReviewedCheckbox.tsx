"use client";

// IMPL-20: the whole-exercise human sign-off toggle, shared by the list rows
// and grid tiles. Optimistic — the parent flips local state immediately and
// reverts on failure. Disabled while a request is in flight.
export function ReviewedCheckbox({
  reviewed,
  onChange,
  busy,
}: {
  reviewed: boolean;
  onChange: (next: boolean) => void;
  busy?: boolean;
}) {
  return (
    <label className="inline-flex cursor-pointer items-center gap-1.5 text-xs text-secondary">
      <input
        type="checkbox"
        checked={reviewed}
        disabled={busy}
        onChange={(e) => onChange(e.target.checked)}
        className="h-4 w-4 cursor-pointer accent-accent disabled:cursor-not-allowed"
      />
      <span className="select-none">Reviewed</span>
    </label>
  );
}
