"use client";

import { createContext, useCallback, useContext, useEffect, useRef, useState } from "react";

// Promise-based confirm dialog. Replaces window.confirm() with a modal
// that matches the design tokens. Usage:
//
//   const confirm = useConfirm();
//   const ok = await confirm({
//     title: "Delete this scan?",
//     description: "This cannot be undone.",
//     confirmLabel: "Delete",
//     tone: "danger",
//   });
//   if (!ok) return;
//
// One <ConfirmProvider /> at the root manages the single active dialog;
// only one dialog can be open at a time (the imperative API queues
// nothing — callers should await before opening another).

type Tone = "default" | "danger";

type ConfirmOptions = {
  title: string;
  description?: string;
  confirmLabel?: string;
  cancelLabel?: string;
  tone?: Tone;
};

type Pending = ConfirmOptions & {
  resolve: (ok: boolean) => void;
};

type ConfirmFn = (opts: ConfirmOptions) => Promise<boolean>;

const ConfirmContext = createContext<ConfirmFn | null>(null);

export function useConfirm(): ConfirmFn {
  const ctx = useContext(ConfirmContext);
  if (!ctx) throw new Error("useConfirm must be used inside <ConfirmProvider>");
  return ctx;
}

export function ConfirmProvider({ children }: { children: React.ReactNode }) {
  const [pending, setPending] = useState<Pending | null>(null);
  const confirmButtonRef = useRef<HTMLButtonElement>(null);
  // Track whether the mousedown landed on the backdrop so we only dismiss on
  // a true backdrop click. Without this, a text-selection drag that starts
  // inside the dialog and releases over the backdrop would close it.
  const downOnBackdropRef = useRef(false);

  const confirm = useCallback<ConfirmFn>((opts) => {
    return new Promise((resolve) => setPending({ ...opts, resolve }));
  }, []);

  function answer(ok: boolean) {
    if (!pending) return;
    pending.resolve(ok);
    setPending(null);
  }

  function handleBackdropMouseDown(e: React.MouseEvent) {
    downOnBackdropRef.current = e.target === e.currentTarget;
  }

  function handleBackdropClick(e: React.MouseEvent) {
    const downOnBackdrop = downOnBackdropRef.current;
    downOnBackdropRef.current = false;
    if (downOnBackdrop && e.target === e.currentTarget) {
      answer(false);
    }
  }

  useEffect(() => {
    if (!pending) return;
    // Focus the confirm button on open so Enter triggers it; users
    // expect that behavior from native dialogs.
    confirmButtonRef.current?.focus();
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") answer(false);
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pending]);

  const dialog = pending ? (
    <div
      className="fixed inset-0 z-[200] flex items-center justify-center bg-canvas/75 backdrop-blur-sm"
      onMouseDown={handleBackdropMouseDown}
      onClick={handleBackdropClick}
    >
      <div
        role="alertdialog"
        aria-modal
        aria-labelledby="confirm-title"
        className="w-[420px] rounded-[14px] border-[0.5px] border-border-default bg-surface px-6 py-5 shadow-[0_24px_64px_rgba(0,0,0,0.16)]"
        onMouseDown={(e) => e.stopPropagation()}
        onClick={(e) => e.stopPropagation()}
      >
        <h2
          id="confirm-title"
          className="m-0 text-[16px] font-medium tracking-[-0.01em] text-primary"
        >
          {pending.title}
        </h2>
        {pending.description && (
          <p className="mt-2 text-[13px] leading-[1.5] text-secondary">
            {pending.description}
          </p>
        )}
        <div className="mt-5 flex justify-end gap-2">
          <button
            type="button"
            onClick={() => answer(false)}
            className="cursor-pointer rounded-md border-[0.5px] border-border-default bg-canvas px-3 py-1.5 text-[12px] font-medium text-primary"
          >
            {pending.cancelLabel ?? "Cancel"}
          </button>
          <button
            ref={confirmButtonRef}
            type="button"
            onClick={() => answer(true)}
            className={
              pending.tone === "danger"
                ? "cursor-pointer rounded-md bg-red-600 px-3 py-1.5 text-[12px] font-medium text-white"
                : "cursor-pointer rounded-md bg-accent px-3 py-1.5 text-[12px] font-medium text-inverse"
            }
          >
            {pending.confirmLabel ?? "Confirm"}
          </button>
        </div>
      </div>
    </div>
  ) : null;

  return (
    <ConfirmContext.Provider value={confirm}>
      {children}
      {dialog}
    </ConfirmContext.Provider>
  );
}
