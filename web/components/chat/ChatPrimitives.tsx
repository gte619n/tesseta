"use client";

import { useMemo } from "react";

// Presentational primitives shared by the Goals chat and Workout-program chat
// surfaces (GoalsChat / WorkoutProgramChat). Extracted verbatim from those two
// components, which carried identical copies.

export function ChatComposer({
  value,
  onChange,
  onSend,
  streaming,
  placeholder,
}: {
  value: string;
  onChange: (v: string) => void;
  onSend: () => void;
  streaming: boolean;
  placeholder: string;
}) {
  function onKeyDown(e: React.KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      onSend();
    }
  }
  return (
    <div className="shrink-0 border-t-[0.5px] border-border-subtle p-3">
      <div className="flex items-end gap-2">
        <textarea
          value={value}
          onChange={(e) => onChange(e.target.value)}
          onKeyDown={onKeyDown}
          rows={2}
          placeholder={placeholder}
          className="min-h-[44px] flex-1 resize-none rounded-md border-[0.5px] border-border-default bg-surface px-3 py-2 text-[13px] text-primary outline-none focus:border-accent"
        />
        <button
          type="button"
          onClick={onSend}
          disabled={streaming || !value.trim()}
          className="cursor-pointer rounded-md bg-accent px-4 py-2.5 text-[12px] font-medium text-inverse disabled:opacity-50"
        >
          {streaming ? "…" : "Send"}
        </button>
      </div>
    </div>
  );
}

export function TypingIndicator() {
  // Three pulsing dots while the stream is open.
  const dots = useMemo(() => [0, 1, 2], []);
  return (
    <span
      className="inline-flex items-center gap-1"
      aria-label="Assistant is typing"
    >
      {dots.map((d) => (
        <span
          key={d}
          className="h-1.5 w-1.5 animate-pulse rounded-full bg-tertiary"
          style={{ animationDelay: `${d * 150}ms` }}
        />
      ))}
    </span>
  );
}

export function TrashIcon() {
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      viewBox="0 0 16 16"
      fill="currentColor"
      className="h-3 w-3"
      aria-hidden
    >
      <path
        fillRule="evenodd"
        d="M5 3.25V4H2.75a.75.75 0 0 0 0 1.5h.3l.815 6.527A1.75 1.75 0 0 0 5.605 13.5h4.79a1.75 1.75 0 0 0 1.74-1.473L13.95 5.5h.3a.75.75 0 0 0 0-1.5H12v-.75A2.25 2.25 0 0 0 9.75 1h-3.5A2.25 2.25 0 0 0 4 3.25V4h1V3.25A1.25 1.25 0 0 1 6.25 2h3.5A1.25 1.25 0 0 1 11 3.25V4H5ZM6.5 7a.5.5 0 0 1 .5.5v4a.5.5 0 0 1-1 0v-4a.5.5 0 0 1 .5-.5Zm3 0a.5.5 0 0 1 .5.5v4a.5.5 0 0 1-1 0v-4a.5.5 0 0 1 .5-.5Z"
        clipRule="evenodd"
      />
    </svg>
  );
}
