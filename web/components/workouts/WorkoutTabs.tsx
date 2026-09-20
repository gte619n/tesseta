"use client";

import Link from "next/link";
import type { Route } from "next";
import { usePathname } from "next/navigation";

// Persistent tab bar for the Workouts section (IMPL-WEB-WORKOUT-01 D1/D11).
// Overview | History | Programs | Progression | Gyms, with Preferences behind a
// gear icon so it doesn't spend a tab slot. Active state comes from the current
// path: Overview matches the section root exactly; the rest match by prefix.
// Horizontally scrollable at narrow widths (D20).

type Tab = { href: string; label: string; exact?: boolean };

const TABS: Tab[] = [
  { href: "/me/workouts", label: "Overview", exact: true },
  { href: "/me/workouts/history", label: "History" },
  { href: "/me/workouts/programs", label: "Programs" },
  { href: "/me/workouts/progression", label: "Progression" },
  { href: "/me/workouts/gyms", label: "Gyms" },
];

function isActive(
  pathname: string,
  tab: { href: string; exact?: boolean },
): boolean {
  if (tab.exact) return pathname === tab.href;
  return pathname === tab.href || pathname.startsWith(`${tab.href}/`);
}

export function WorkoutTabs() {
  const pathname = usePathname() ?? "";
  const preferencesActive = isActive(pathname, {
    href: "/me/workouts/preferences",
  });

  return (
    <nav
      aria-label="Workout sections"
      className="flex items-center gap-1 overflow-x-auto"
    >
      <div className="flex flex-1 items-center gap-1">
        {TABS.map((tab) => {
          const active = isActive(pathname, tab);
          return (
            <Link
              key={tab.href}
              href={tab.href as Route}
              aria-current={active ? "page" : undefined}
              data-active={active ? "true" : "false"}
              className={
                "whitespace-nowrap rounded-[8px] px-3 py-1.5 text-[13px] font-medium transition-colors " +
                (active
                  ? "bg-accent/12 text-accent-dim"
                  : "text-secondary hover:bg-surface hover:text-primary")
              }
            >
              {tab.label}
            </Link>
          );
        })}
      </div>
      <Link
        href="/me/workouts/preferences"
        aria-label="Workout preferences"
        aria-current={preferencesActive ? "page" : undefined}
        data-active={preferencesActive ? "true" : "false"}
        className={
          "ml-1 inline-flex shrink-0 items-center justify-center rounded-[8px] p-1.5 transition-colors " +
          (preferencesActive
            ? "bg-accent/12 text-accent-dim"
            : "text-tertiary hover:bg-surface hover:text-primary")
        }
        title="Preferences"
      >
        <svg
          width="18"
          height="18"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.5"
          strokeLinecap="round"
          strokeLinejoin="round"
          aria-hidden
        >
          <circle cx="12" cy="12" r="3" />
          <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z" />
        </svg>
      </Link>
    </nav>
  );
}
