"use client";

import { useEffect, useRef, useState, useTransition } from "react";

type Marker = string;

type Props = {
  addReading: (formData: FormData) => Promise<void>;
  markers: Marker[];
  markerLabels: Record<Marker, string>;
};

export function AddReadingButton({ addReading, markers, markerLabels }: Props) {
  const [open, setOpen] = useState(false);
  const [isPending, startTransition] = useTransition();
  const dialogRef = useRef<HTMLDivElement>(null);
  const firstInputRef = useRef<HTMLSelectElement>(null);
  // Track whether the mousedown landed on the backdrop so we only close on a
  // true backdrop click. Without this, a text-selection drag that starts
  // inside the dialog and releases over the backdrop would close the modal.
  const downOnBackdropRef = useRef(false);

  function handleBackdropMouseDown(e: React.MouseEvent) {
    downOnBackdropRef.current = e.target === e.currentTarget;
  }

  function handleBackdropClick(e: React.MouseEvent) {
    const downOnBackdrop = downOnBackdropRef.current;
    downOnBackdropRef.current = false;
    if (downOnBackdrop && e.target === e.currentTarget) {
      setOpen(false);
    }
  }

  useEffect(() => {
    if (!open) return;
    firstInputRef.current?.focus();
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") setOpen(false);
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open]);

  function handleSubmit(formData: FormData) {
    startTransition(async () => {
      await addReading(formData);
      setOpen(false);
    });
  }

  return (
    <>
      <button
        type="button"
        data-testid="add-reading-btn"
        onClick={() => setOpen(true)}
        className="cursor-pointer rounded-md bg-accent px-4 py-2 text-[13px] font-medium text-inverse"
      >
        Add reading
      </button>

      {open && (
        <div
          className="fixed inset-0 z-[200] flex items-center justify-center bg-canvas/75 backdrop-blur-sm"
          onMouseDown={handleBackdropMouseDown}
          onClick={handleBackdropClick}
        >
          <div
            ref={dialogRef}
            role="dialog"
            aria-modal
            aria-labelledby="add-reading-title"
            className="w-[480px] rounded-[14px] border-[0.5px] border-border-default bg-surface px-6 py-5 shadow-[0_24px_64px_rgba(0,0,0,0.16)]"
            onMouseDown={(e) => e.stopPropagation()}
            onClick={(e) => e.stopPropagation()}
          >
            <h2
              id="add-reading-title"
              className="m-0 text-[16px] font-medium tracking-[-0.01em] text-primary"
            >
              Add a reading
            </h2>
            <p className="mt-1 text-[13px] text-secondary">
              Enter a single marker value from your lab results.
            </p>

            <form action={handleSubmit} className="mt-5 space-y-4">
              <label className="flex flex-col gap-1">
                <span className="font-mono text-[10px] uppercase tracking-[0.06em] text-tertiary">
                  Marker
                </span>
                <select
                  ref={firstInputRef}
                  name="marker"
                  required
                  defaultValue={markers[0]}
                  className="rounded-md border-[0.5px] border-border-default bg-canvas px-2.5 py-2 text-[13px] text-primary"
                >
                  {markers.map((m) => (
                    <option key={m} value={m}>
                      {markerLabels[m]}
                    </option>
                  ))}
                </select>
              </label>

              <div className="grid grid-cols-2 gap-3">
                <label className="flex flex-col gap-1">
                  <span className="font-mono text-[10px] uppercase tracking-[0.06em] text-tertiary">
                    Value
                  </span>
                  <input
                    name="value"
                    data-testid="reading-value"
                    type="number"
                    step="0.01"
                    required
                    className="rounded-md border-[0.5px] border-border-default bg-canvas px-2.5 py-2 text-[13px] text-primary"
                  />
                </label>
                <label className="flex flex-col gap-1">
                  <span className="font-mono text-[10px] uppercase tracking-[0.06em] text-tertiary">
                    Date
                  </span>
                  <input
                    name="sampleDate"
                    type="date"
                    required
                    defaultValue={new Date().toISOString().slice(0, 10)}
                    className="rounded-md border-[0.5px] border-border-default bg-canvas px-2.5 py-2 text-[13px] text-primary"
                  />
                </label>
              </div>

              <label className="flex flex-col gap-1">
                <span className="font-mono text-[10px] uppercase tracking-[0.06em] text-tertiary">
                  Lab (optional)
                </span>
                <input
                  name="labSource"
                  type="text"
                  placeholder="Quest, LabCorp…"
                  className="rounded-md border-[0.5px] border-border-default bg-canvas px-2.5 py-2 text-[13px] text-primary"
                />
              </label>

              <div className="flex justify-end gap-2 pt-2">
                <button
                  type="button"
                  onClick={() => setOpen(false)}
                  className="cursor-pointer rounded-md border-[0.5px] border-border-default bg-canvas px-3 py-1.5 text-[12px] font-medium text-primary"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  data-testid="add-reading-submit"
                  disabled={isPending}
                  className="cursor-pointer rounded-md bg-accent px-3 py-1.5 text-[12px] font-medium text-inverse disabled:opacity-50"
                >
                  {isPending ? "Adding…" : "Add"}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </>
  );
}
