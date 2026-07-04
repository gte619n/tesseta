"use client";

import { useEffect, useRef } from "react";

type ModalBackdropProps = {
  /** Called when the backdrop (not the dialog) is clicked, or on a true
   *  backdrop mousedown+mouseup, or when Escape is pressed. */
  onClose: () => void;
  /** Dialog content. Rendered inside the inner box that stops propagation. */
  children: React.ReactNode;
  /** Backdrop classes. Defaults to the standard centered, blurred overlay. */
  className?: string;
  /** Inner dialog-box classes (width, padding, surface, etc.). */
  contentClassName?: string;
  /** id of the element labelling the dialog (preferred over `label`). */
  labelledBy?: string;
  /** Accessible name when there's no visible heading to reference. */
  label?: string;
};

const DEFAULT_BACKDROP =
  "fixed inset-0 z-[200] flex items-center justify-center bg-canvas/75 backdrop-blur-sm";

const FOCUSABLE_SELECTOR = [
  "a[href]",
  "button:not([disabled])",
  "textarea:not([disabled])",
  "input:not([disabled])",
  "select:not([disabled])",
  '[tabindex]:not([tabindex="-1"])',
].join(",");

/**
 * Accessible modal backdrop.
 *
 * Closes only on a *true* backdrop click: a mouse gesture that starts inside the
 * dialog (e.g. selecting text) and releases over the backdrop must NOT close it,
 * so we require both the mousedown and the click to land on the backdrop itself.
 * The inner box stops propagation so interactions inside never bubble.
 *
 * Accessibility (fixes every modal built on this primitive at once):
 * `role="dialog"` + `aria-modal`, Escape-to-close, a focus trap that keeps Tab
 * inside the dialog, initial focus moved into the dialog, and focus restored to
 * the trigger on close. Provide `labelledBy` (id of your heading) or `label`.
 *
 * See web/CLAUDE.md "Modals".
 */
export function ModalBackdrop({
  onClose,
  children,
  className,
  contentClassName,
  labelledBy,
  label,
}: ModalBackdropProps) {
  const downOnBackdropRef = useRef(false);
  const boxRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const previouslyFocused =
      document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const box = boxRef.current;

    // Move focus into the dialog: first focusable element, else the box itself.
    const initial = box?.querySelector<HTMLElement>(FOCUSABLE_SELECTOR) ?? box;
    initial?.focus();

    function onKeyDown(e: KeyboardEvent) {
      if (!box) return;
      if (e.key === "Escape") {
        e.stopPropagation();
        onClose();
        return;
      }
      if (e.key !== "Tab") return;
      const items = Array.from(
        box.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR),
      ).filter((el) => el.offsetParent !== null || el === document.activeElement);
      const first = items[0];
      const last = items[items.length - 1];
      if (!first || !last) {
        // Nothing tabbable — keep focus on the dialog box.
        e.preventDefault();
        box.focus();
        return;
      }
      const active = document.activeElement;
      if (e.shiftKey && (active === first || active === box)) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && active === last) {
        e.preventDefault();
        first.focus();
      }
    }

    document.addEventListener("keydown", onKeyDown, true);
    return () => {
      document.removeEventListener("keydown", onKeyDown, true);
      previouslyFocused?.focus?.();
    };
  }, [onClose]);

  function handleBackdropMouseDown(e: React.MouseEvent) {
    downOnBackdropRef.current = e.target === e.currentTarget;
  }

  function handleBackdropClick(e: React.MouseEvent) {
    const downOnBackdrop = downOnBackdropRef.current;
    downOnBackdropRef.current = false;
    if (downOnBackdrop && e.target === e.currentTarget) {
      onClose();
    }
  }

  return (
    <div
      className={className ?? DEFAULT_BACKDROP}
      onMouseDown={handleBackdropMouseDown}
      onClick={handleBackdropClick}
    >
      <div
        ref={boxRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={labelledBy}
        aria-label={labelledBy ? undefined : label}
        tabIndex={-1}
        className={contentClassName}
        onMouseDown={(e) => e.stopPropagation()}
        onClick={(e) => e.stopPropagation()}
      >
        {children}
      </div>
    </div>
  );
}
