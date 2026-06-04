"use client";

import { useRef } from "react";

type ModalBackdropProps = {
  /** Called when the backdrop (not the dialog) is clicked, or on a true
   *  backdrop mousedown+mouseup. Wire this to your close handler. */
  onClose: () => void;
  /** Dialog content. Rendered inside the inner box that stops propagation. */
  children: React.ReactNode;
  /** Backdrop classes. Defaults to the standard centered, blurred overlay. */
  className?: string;
  /** Inner dialog-box classes (width, padding, surface, etc.). */
  contentClassName?: string;
};

const DEFAULT_BACKDROP =
  "fixed inset-0 z-[200] flex items-center justify-center bg-canvas/75 backdrop-blur-sm";

/**
 * Modal backdrop that closes only on a *true* backdrop click.
 *
 * A plain `onClick={onClose}` has a subtle bug: a mouse gesture that starts
 * inside the dialog (e.g. selecting text in an input) and releases over the
 * backdrop fires a backdrop click and closes the modal, losing the selection or
 * in-progress input. We track whether the mousedown landed on the backdrop and
 * only close when both the mousedown AND the click target are the backdrop
 * itself. The inner box stops propagation so interactions inside never bubble.
 *
 * Pass-through `className`/`contentClassName` so each modal keeps its exact
 * styling. See web/CLAUDE.md "Modals".
 */
export function ModalBackdrop({
  onClose,
  children,
  className,
  contentClassName,
}: ModalBackdropProps) {
  const downOnBackdropRef = useRef(false);

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
        className={contentClassName}
        onMouseDown={(e) => e.stopPropagation()}
        onClick={(e) => e.stopPropagation()}
      >
        {children}
      </div>
    </div>
  );
}
