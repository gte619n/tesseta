"use client";

import { useState, useRef, useEffect } from "react";

type Props = {
  children: React.ReactNode;
  content: React.ReactNode;
};

export function MarkerTooltip({ children, content }: Props) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    function handleClickOutside(e: MouseEvent) {
      if (ref.current && !ref.current.contains(e.target as Node)) {
        setOpen(false);
      }
    }
    document.addEventListener("mousedown", handleClickOutside);
    return () => document.removeEventListener("mousedown", handleClickOutside);
  }, [open]);

  return (
    <div className="relative inline-block" ref={ref}>
      <button
        type="button"
        onClick={() => setOpen(!open)}
        className="cursor-pointer text-left"
      >
        {children}
      </button>
      {open && (
        <div className="absolute left-0 top-full z-50 mt-1 w-72 rounded-lg border-[0.5px] border-border-default bg-surface p-3 shadow-[0_8px_24px_rgba(0,0,0,0.12)]">
          {content}
        </div>
      )}
    </div>
  );
}
