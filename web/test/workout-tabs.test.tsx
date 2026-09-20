import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

// E2E-9 · the persistent tab bar (IMPL-WEB-WORKOUT-01 D1/D11): five tabs + a
// preferences gear, with active state driven by the current path. usePathname is
// mocked per render.

let mockPathname = "/me/workouts";
vi.mock("next/navigation", () => ({
  usePathname: () => mockPathname,
}));

import { WorkoutTabs } from "@/components/workouts/WorkoutTabs";

function renderAt(path: string) {
  mockPathname = path;
  return render(<WorkoutTabs />);
}

describe("E2E-9 · WorkoutTabs", () => {
  it("renders all five tabs plus the preferences gear", () => {
    renderAt("/me/workouts");
    for (const label of ["Overview", "History", "Programs", "Progression", "Gyms"]) {
      expect(screen.getByRole("link", { name: label })).toBeInTheDocument();
    }
    expect(
      screen.getByRole("link", { name: /workout preferences/i }),
    ).toHaveAttribute("href", "/me/workouts/preferences");
  });

  it("marks Overview active only on the exact section root", () => {
    renderAt("/me/workouts");
    expect(screen.getByRole("link", { name: "Overview" })).toHaveAttribute(
      "aria-current",
      "page",
    );
    expect(screen.getByRole("link", { name: "History" })).not.toHaveAttribute(
      "aria-current",
    );
  });

  it("marks a sub-section active by prefix (and not Overview)", () => {
    renderAt("/me/workouts/history/wp_alpha/2026-09-12_d1");
    expect(screen.getByRole("link", { name: "History" })).toHaveAttribute(
      "aria-current",
      "page",
    );
    expect(screen.getByRole("link", { name: "Overview" })).not.toHaveAttribute(
      "aria-current",
    );
  });

  it("marks the gear active on the preferences route", () => {
    renderAt("/me/workouts/preferences");
    expect(
      screen.getByRole("link", { name: /workout preferences/i }),
    ).toHaveAttribute("aria-current", "page");
  });
});
