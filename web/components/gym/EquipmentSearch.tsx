"use client";

import { useState, useEffect } from "react";

interface EquipmentSearchProps {
  value: string;
  onChange: (value: string) => void;
}

export function EquipmentSearch({ value, onChange }: EquipmentSearchProps) {
  const [localValue, setLocalValue] = useState(value);

  useEffect(() => {
    setLocalValue(value);
  }, [value]);

  useEffect(() => {
    const timer = setTimeout(() => {
      onChange(localValue);
    }, 300);
    return () => clearTimeout(timer);
  }, [localValue, onChange]);

  return (
    <div className="relative">
      <div className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-tertiary">
        <svg
          width="16"
          height="16"
          viewBox="0 0 16 16"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.5"
          strokeLinecap="round"
          strokeLinejoin="round"
        >
          <circle cx="7" cy="7" r="4" />
          <path d="M11 11l3 3" />
        </svg>
      </div>
      <input
        type="text"
        value={localValue}
        onChange={(e) => setLocalValue(e.target.value)}
        placeholder="Search equipment..."
        className="w-full rounded-lg border border-border-default bg-canvas px-3 py-2 pl-9 text-[13px] text-primary placeholder:text-tertiary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent/20"
      />
      {localValue && (
        <button
          type="button"
          onClick={() => setLocalValue("")}
          className="absolute right-2 top-1/2 -translate-y-1/2 cursor-pointer rounded-md px-1.5 py-0.5 font-mono text-[14px] leading-none text-tertiary hover:text-primary"
          aria-label="Clear search"
        >
          ×
        </button>
      )}
    </div>
  );
}
