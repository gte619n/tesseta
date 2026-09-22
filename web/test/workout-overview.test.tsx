import { render, screen, fireEvent, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import { StreakHero } from "@/components/workouts/StreakHero";
import { ConsistencyHeatmap } from "@/components/workouts/ConsistencyHeatmap";
import { CurrentProgramCard } from "@/components/workouts/CurrentProgramCard";
import { LatestWorkouts } from "@/components/workouts/LatestWorkouts";
import { WeeklyVolumeChart } from "@/components/workouts/WeeklyVolumeChart";
import { StrengthTrendChart } from "@/components/workouts/StrengthTrendChart";
import { RecentPrsCard } from "@/components/workouts/RecentPrsCard";
import { SessionDetail } from "@/components/workouts/SessionDetail";
import { WorkoutCard } from "@/components/dashboard/WorkoutCard";

import {
  alphaStats,
  alphaBenchHistory,
  alphaSquatHistory,
  alphaDumbbellHistory,
  perHandPrList,
  alphaSessionDetail,
  alphaLatest,
  emptyStats,
  ALPHA_TODAY,
} from "./fixtures/workout-stats";

// Functional gate for the Overview (IMPL-WEB-WORKOUT-01 §7.2 / IL-9): the real
// presentational components rendered with the hand-computed fixtures, asserting
// the exact rendered values. Test names carry the spec's E2E-n IDs.

describe("E2E-1 · StreakHero shows the exact streak numbers", () => {
  it("renders current / longest / this-week from user-alpha", () => {
    render(<StreakHero streak={alphaStats.streak} />);
    expect(screen.getByTestId("streak-current")).toHaveTextContent("7");
    expect(screen.getByTestId("streak-longest")).toHaveTextContent("9");
    expect(screen.getByTestId("streak-this-week")).toHaveTextContent("2/4");
  });
});

describe("E2E-2 · WeeklyVolumeChart tooltip shows exact tonnage", () => {
  it.each([
    ["2026-07-06", "51,000 lb"],
    ["2026-08-31", "38,000 lb"],
    ["2026-09-07", "42,150 lb"],
  ])("week %s hovers to %s", (week, expected) => {
    const { container } = render(
      <WeeklyVolumeChart series={alphaStats.weeklySeries} weeklyTarget={4} />,
    );
    const bar = container.querySelector(`[data-week="${week}"]`);
    expect(bar).not.toBeNull();
    fireEvent.mouseEnter(bar!);
    expect(screen.getByTestId("volume-tooltip")).toHaveTextContent(expected);
  });
});

describe("E2E-3 · ConsistencyHeatmap links workout days and leaves rest days inert", () => {
  it("a workout day deep-links to its session; a rest day is inert", () => {
    const { container } = render(
      <ConsistencyHeatmap days={alphaStats.heatmap} today={ALPHA_TODAY} />,
    );
    const workout = container.querySelector('a[data-date="2026-09-12"]');
    expect(workout).not.toBeNull();
    expect(workout).toHaveAttribute(
      "href",
      "/me/workouts/history/wp_alpha/2026-09-12_d1",
    );
    // A neighboring rest day exists in the grid but is not a link.
    const rest = container.querySelector('[data-date="2026-09-11"]');
    expect(rest).not.toBeNull();
    expect(rest!.tagName).toBe("DIV");
  });
});

describe("E2E-4 · LatestWorkouts lists recent sessions newest-first with links", () => {
  it("renders 5 rows, the newest first, linking to session detail", () => {
    render(<LatestWorkouts sessions={alphaLatest} />);
    const rows = screen.getAllByTestId("latest-workout-row");
    expect(rows).toHaveLength(5);
    const first = within(rows[0]!);
    expect(first.getByText("Push A")).toBeInTheDocument();
    expect(first.getByText("SEP 12, 2026")).toBeInTheDocument();
    expect(first.getByText("2 sets")).toBeInTheDocument();
    expect(rows[0]!.querySelector("a")).toHaveAttribute(
      "href",
      "/me/workouts/history/wp_alpha/2026-09-12_d1",
    );
  });
});

describe("E2E-5 · SessionDetail shows logged vs prescribed and badges the PR set", () => {
  it("renders prescribed + logged sets, PR badge, and prev/next", () => {
    render(<SessionDetail detail={alphaSessionDetail} />);
    // Prescribed label is "3 × 5 @ 215 lb" in one span.
    expect(screen.getByText(/3 × 5 @ 215 lb/)).toBeInTheDocument();
    const sets = screen.getAllByTestId("logged-set");
    expect(sets).toHaveLength(2);
    // The 225×5 set (index 1) is the PR.
    expect(sets[0]).toHaveAttribute("data-pr", "false");
    expect(sets[1]).toHaveAttribute("data-pr", "true");
    expect(within(sets[1]!).getByTestId("pr-badge")).toBeInTheDocument();
    expect(sets[1]).toHaveTextContent("225 lb × 5");

    // Prev links to the neighbor; there is no next.
    expect(screen.getByTestId("session-prev")).toHaveAttribute(
      "href",
      "/me/workouts/history/wp_alpha/2026-08-30_d3",
    );
    expect(screen.queryByTestId("session-next")).toBeNull();
  });
});

describe("E2E-6 · StrengthTrendChart defaults then fetches on lift switch", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("defaults to the first lift, then loads the picked lift's series", async () => {
    render(
      <StrengthTrendChart
        lifts={alphaStats.trackedExercises}
        defaultLifts={alphaStats.chartDefaultLifts}
        initialHistory={alphaBenchHistory}
      />,
    );
    // Bench current belief = 265.
    expect(screen.getByTestId("strength-latest")).toHaveTextContent("265");

    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => alphaSquatHistory,
    });
    vi.stubGlobal("fetch", fetchMock);

    // Open the searchable picker, narrow with the search box, then pick.
    await userEvent.click(screen.getByTestId("lift-picker"));
    await userEvent.type(screen.getByTestId("lift-search"), "squat");
    await userEvent.click(screen.getByText("Back Squat"));

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/workout-stats/e1rm-history?exerciseId=ex_squat",
    );
    // Squat current belief = 405.
    expect(await screen.findByText("405")).toBeInTheDocument();
  });
});

describe("E2E-7 · RecentPrsCard lists exactly the PRs with values and links", () => {
  it("shows the two fixture PRs", () => {
    render(<RecentPrsCard prs={alphaStats.recentPrs} />);
    const rows = screen.getAllByTestId("pr-row");
    expect(rows).toHaveLength(2);
    const bench = within(rows[0]!);
    expect(bench.getByText("Barbell Bench Press")).toBeInTheDocument();
    expect(bench.getByText("262.5 lb")).toBeInTheDocument();
    expect(bench.getByText("225 × 5")).toBeInTheDocument();
    expect(rows[0]!.querySelector("a")).toHaveAttribute(
      "href",
      "/me/workouts/history/wp_alpha/2026-09-12_d1",
    );
    expect(within(rows[1]!).getByText("Romanian Deadlift")).toBeInTheDocument();
    expect(within(rows[1]!).getByText("367.5 lb")).toBeInTheDocument();
  });
});

// IMPL-PROG-LOAD-01 P2 functional gate: per-hand lifts show TOTAL as the primary
// value with a per-hand secondary; barbell (total-load) lifts show a plain number.
describe("PROG-LOAD P2 · per-hand → total display", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("RecentPrsCard shows total primary + per-hand secondary for a dumbbell PR", () => {
    render(<RecentPrsCard prs={perHandPrList} />);
    const rows = screen.getAllByTestId("pr-row");
    // Dumbbell row: total e1RM 210, total weight 180, per-hand 90.
    const db = within(rows[0]!);
    expect(db.getByText("Dumbbell Bench Press")).toBeInTheDocument();
    expect(rows[0]!).toHaveTextContent("210 lb"); // total e1RM, not 105
    expect(rows[0]!).toHaveTextContent("180 × 5"); // total weight
    expect(rows[0]!).toHaveTextContent("90/hand"); // per-hand secondary
    // Barbell row: plain total, no per-hand suffix.
    expect(rows[1]!).toHaveTextContent("262.5 lb");
    expect(rows[1]!).not.toHaveTextContent("/hand");
  });

  it("StrengthTrendChart headline is total load with a per-hand secondary", () => {
    render(
      <StrengthTrendChart
        lifts={alphaStats.trackedExercises}
        defaultLifts={alphaStats.chartDefaultLifts}
        initialHistory={alphaDumbbellHistory}
      />,
    );
    // Belief 106/hand → 212 total is the headline; 106/hand is the secondary.
    expect(screen.getByTestId("strength-latest")).toHaveTextContent("212");
    expect(screen.getByText(/106\/hand/)).toBeInTheDocument();
  });

  it("StrengthTrendChart stays plain for a barbell lift (no per-hand)", () => {
    render(
      <StrengthTrendChart
        lifts={alphaStats.trackedExercises}
        defaultLifts={alphaStats.chartDefaultLifts}
        initialHistory={alphaBenchHistory}
      />,
    );
    expect(screen.getByTestId("strength-latest")).toHaveTextContent("265");
    expect(screen.queryByText(/\/hand/)).toBeNull();
  });
});

describe("E2E-8 · empty states never blank the page", () => {
  it("streak shows 0", () => {
    render(<StreakHero streak={emptyStats.streak} />);
    expect(screen.getByTestId("streak-current")).toHaveTextContent("0");
  });

  it("no active program → Design a program CTA", () => {
    render(<CurrentProgramCard program={null} />);
    const cta = screen.getByTestId("design-program-cta");
    expect(cta).toHaveAttribute("href", "/me/workouts/programs/chat");
  });

  it("charts and lists show friendly empties", () => {
    const { rerender } = render(
      <ConsistencyHeatmap days={[]} today={ALPHA_TODAY} />,
    );
    expect(screen.getByText(/No workouts logged yet/i)).toBeInTheDocument();

    rerender(<WeeklyVolumeChart series={[]} weeklyTarget={4} />);
    expect(screen.getByText(/No volume yet/i)).toBeInTheDocument();

    rerender(<RecentPrsCard prs={[]} />);
    expect(screen.getByText(/No personal records yet/i)).toBeInTheDocument();

    rerender(<LatestWorkouts sessions={[]} />);
    expect(screen.getByText(/No workouts logged yet/i)).toBeInTheDocument();
  });
});

describe("E2E-12 · dashboard WorkoutCard shows streak and degrades without it", () => {
  const base = {
    totalCount: 42,
    lastWorkoutDate: "2026-09-12",
    activeProgramTitle: "Hypertrophy Block",
    activeProgramCount: 1,
  };

  it("renders the streak line for user-alpha", () => {
    render(
      <WorkoutCard
        summary={{
          ...base,
          streak: { current: 7, thisWeekCompleted: 2, weeklyTarget: 4 },
        }}
      />,
    );
    const streak = screen.getByTestId("workout-card-streak");
    expect(streak).toHaveTextContent("7 wk");
    expect(streak).toHaveTextContent("2/4 this week");
  });

  it("drops the streak line but keeps legacy content when stats are absent", () => {
    render(<WorkoutCard summary={{ ...base, streak: null }} />);
    expect(screen.queryByTestId("workout-card-streak")).toBeNull();
    expect(screen.getByText("Hypertrophy Block")).toBeInTheDocument();
    expect(screen.getByText("42")).toBeInTheDocument();
  });
});
