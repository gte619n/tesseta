// Hand-computed fixtures for the Overview functional tests
// (IMPL-WEB-WORKOUT-01 §7.2). See ./workout-stats.README.md for the derivation
// of every expected number. `user-alpha` is a rich account; `user-empty` is a
// brand-new user. These drive the RTL component tests that stand in for the
// spec's Playwright e2e (decision IL-9) — same fixtures, same expected values.

import type {
  WorkoutStats,
  E1rmHistory,
  SessionDetailResponse,
} from "@/lib/types/workout-stats";
import type { ScheduledWorkoutResponse } from "@/lib/types/workout-program";

// Current week Monday for the fixture "today" of 2026-09-16 (a Wednesday).
export const ALPHA_WEEK_START = "2026-09-14";
export const ALPHA_TODAY = "2026-09-16";

// 26-week volume series ending with the current week. All zero except four
// spot weeks whose tonnage the tests assert exactly (E2E-2).
function alphaSeries(): WorkoutStats["weeklySeries"] {
  const known: Record<string, { sessions: number; tonnageLbs: number }> = {
    "2026-07-06": { sessions: 5, tonnageLbs: 51000 },
    "2026-08-31": { sessions: 4, tonnageLbs: 38000 },
    "2026-09-07": { sessions: 4, tonnageLbs: 42150 },
    "2026-09-14": { sessions: 2, tonnageLbs: 21075 }, // current (in-progress) week
  };
  const series: WorkoutStats["weeklySeries"] = [];
  // Walk back 25 weeks from the current Monday, oldest first.
  const start = new Date(`${ALPHA_WEEK_START}T00:00:00Z`);
  start.setUTCDate(start.getUTCDate() - 25 * 7);
  for (let i = 0; i < 26; i++) {
    const d = new Date(start);
    d.setUTCDate(start.getUTCDate() + i * 7);
    const weekStart = d.toISOString().slice(0, 10);
    const hit = known[weekStart];
    series.push({
      weekStart,
      sessions: hit?.sessions ?? 0,
      tonnageLbs: hit?.tonnageLbs ?? 0,
    });
  }
  return series;
}

export const alphaStats: WorkoutStats = {
  streak: {
    current: 7,
    longest: 9,
    weeklyTarget: 4,
    thisWeekCompleted: 2,
    weekStart: ALPHA_WEEK_START,
  },
  weeklySeries: alphaSeries(),
  heatmap: [
    // A known workout day (linked) and its neighbor days stay empty (inert).
    {
      date: "2026-09-12",
      sessionCount: 1,
      first: { programId: "wp_alpha", scheduledId: "2026-09-12_d1" },
    },
    {
      date: "2026-08-30",
      sessionCount: 1,
      first: { programId: "wp_alpha", scheduledId: "2026-08-30_d3" },
    },
  ],
  recentPrs: [
    {
      exerciseId: "ex_bench",
      exerciseName: "Barbell Bench Press",
      e1rmLbs: 262.5, // 225 × (1 + 5/30)
      weightLbs: 225,
      reps: 5,
      date: "2026-09-12",
      programId: "wp_alpha",
      scheduledId: "2026-09-12_d1",
    },
    {
      exerciseId: "ex_rdl",
      exerciseName: "Romanian Deadlift",
      e1rmLbs: 367.5, // 315 × (1 + 5/30)
      weightLbs: 315,
      reps: 5,
      date: "2026-08-30",
      programId: "wp_alpha",
      scheduledId: "2026-08-30_d3",
    },
  ],
  chartDefaultLifts: [
    { exerciseId: "ex_bench", exerciseName: "Barbell Bench Press" },
    { exerciseId: "ex_squat", exerciseName: "Back Squat" },
  ],
  trackedExercises: [
    { exerciseId: "ex_bench", exerciseName: "Barbell Bench Press", lastPerformed: "2026-09-12" },
    { exerciseId: "ex_squat", exerciseName: "Back Squat", lastPerformed: "2026-09-10" },
    { exerciseId: "ex_rdl", exerciseName: "Romanian Deadlift", lastPerformed: "2026-08-30" },
  ],
};

// Bench e1RM curve: 6 points, oldest→newest, one weight-only (low confidence).
export const alphaBenchHistory: E1rmHistory = {
  exerciseId: "ex_bench",
  exerciseName: "Barbell Bench Press",
  points: [
    { date: "2026-05-04", e1rmLbs: 205, weightLbs: 205, reps: null, lowConfidence: true },
    { date: "2026-06-01", e1rmLbs: 227.5, weightLbs: 195, reps: 5, lowConfidence: false },
    { date: "2026-07-06", e1rmLbs: 233.3, weightLbs: 200, reps: 5, lowConfidence: false },
    { date: "2026-08-03", e1rmLbs: 245.0, weightLbs: 210, reps: 5, lowConfidence: false },
    { date: "2026-08-30", e1rmLbs: 250.8, weightLbs: 215, reps: 5, lowConfidence: false },
    { date: "2026-09-12", e1rmLbs: 262.5, weightLbs: 225, reps: 5, lowConfidence: false },
  ],
  currentBelief: { e1rmLbs: 265, sigmaLbs: 6.2, confidence: "HIGH" },
};

// The squat curve returned by the (mocked) picker fetch when the user switches
// lifts (E2E-6). Its current belief is 405 — the value the test asserts.
export const alphaSquatHistory: E1rmHistory = {
  exerciseId: "ex_squat",
  exerciseName: "Back Squat",
  points: [
    { date: "2026-07-06", e1rmLbs: 370, weightLbs: 315, reps: 5, lowConfidence: false },
    { date: "2026-09-10", e1rmLbs: 402, weightLbs: 345, reps: 5, lowConfidence: false },
  ],
  currentBelief: { e1rmLbs: 405, sigmaLbs: 8, confidence: "HIGH" },
};

// The 2026-09-12 bench session in full: 185×5 then 225×5 (the PR set at index 1).
export const alphaSessionDetail: SessionDetailResponse = {
  session: {
    scheduledId: "2026-09-12_d1",
    date: "2026-09-12",
    programId: "wp_alpha",
    programTitle: "Hypertrophy Block",
    phaseId: "ph2",
    phaseTitle: "Phase 2",
    dayId: "d1",
    dayLabel: "Push A",
    weekIndexInPhase: 3,
    isDeload: false,
    locationId: "gym-1",
    locationName: "Home Gym",
    status: "COMPLETED",
    completedAt: "2026-09-12T18:30:00Z",
    durationSeconds: 3600,
    feeling: 4,
    session: {
      dayId: "d1",
      label: "Push A",
      dayOfWeek: "SAT",
      locationId: "gym-1",
      locationName: "Home Gym",
      orderIndex: 0,
      blocks: [
        {
          blockId: "b1",
          type: "MAIN",
          title: "Main",
          orderIndex: 0,
          prescriptions: [
            {
              exerciseId: "ex_bench",
              orderIndex: 0,
              sets: 3,
              repsMin: 5,
              repsMax: 5,
              durationSeconds: null,
              intensity: null,
              targetWeightLbs: 215,
              loadBasis: "e1RM 250 from 215×5",
              restSeconds: 180,
              tempo: null,
              notes: null,
              deloadModifier: null,
              exercise: {
                exerciseId: "ex_bench",
                name: "Barbell Bench Press",
                primaryMuscles: ["chest"],
                formCues: [],
                demoFrames: [],
              },
              loggedSets: [
                { weightLbs: 185, reps: 5, rir: 3, rirSource: "REPORTED", rpe: null, restSeconds: 180, completedAt: null },
                { weightLbs: 225, reps: 5, rir: 1, rirSource: "REPORTED", rpe: null, restSeconds: 180, completedAt: null },
              ],
            },
          ],
        },
      ],
    },
  },
  prSetKeys: ["b1:0:1"],
  prev: { programId: "wp_alpha", scheduledId: "2026-08-30_d3", date: "2026-08-30" },
  next: null,
};

// Five most-recent sessions, newest first, for the LatestWorkouts list.
export const alphaLatest: ScheduledWorkoutResponse[] = [
  makeRow("2026-09-12_d1", "2026-09-12", "Push A", 2, 3600, 4),
  makeRow("2026-09-10_d2", "2026-09-10", "Legs", 3, 4200, 5),
  makeRow("2026-09-08_d3", "2026-09-08", "Pull A", 4, 3300, 3),
  makeRow("2026-09-05_d1", "2026-09-05", "Push B", 2, 3000, 4),
  makeRow("2026-08-30_d3", "2026-08-30", "Pull B", 5, 3900, 4),
];

function makeRow(
  scheduledId: string,
  date: string,
  dayLabel: string,
  setCount: number,
  durationSeconds: number,
  feeling: number,
): ScheduledWorkoutResponse {
  return {
    scheduledId,
    date,
    programId: "wp_alpha",
    programTitle: "Hypertrophy Block",
    phaseId: "ph2",
    phaseTitle: "Phase 2",
    dayId: scheduledId.split("_")[1] ?? "d1",
    dayLabel,
    weekIndexInPhase: 3,
    isDeload: false,
    locationId: "gym-1",
    locationName: "Home Gym",
    status: "COMPLETED",
    completedAt: `${date}T18:30:00Z`,
    durationSeconds,
    feeling,
    session: {
      dayId: scheduledId.split("_")[1] ?? "d1",
      label: dayLabel,
      dayOfWeek: "MON",
      locationId: "gym-1",
      locationName: "Home Gym",
      orderIndex: 0,
      blocks: [
        {
          blockId: "b1",
          type: "MAIN",
          title: "Main",
          orderIndex: 0,
          prescriptions: [
            {
              exerciseId: "ex_bench",
              orderIndex: 0,
              sets: setCount,
              repsMin: 5,
              repsMax: 5,
              durationSeconds: null,
              intensity: null,
              targetWeightLbs: 200,
              loadBasis: null,
              restSeconds: 180,
              tempo: null,
              notes: null,
              deloadModifier: null,
              exercise: null,
              loggedSets: Array.from({ length: setCount }, () => ({
                weightLbs: 200,
                reps: 5,
                rir: 2,
                rirSource: "REPORTED",
                rpe: null,
                restSeconds: 180,
                completedAt: null,
              })),
            },
          ],
        },
      ],
    },
  };
}

export const emptyStats: WorkoutStats = {
  streak: {
    current: 0,
    longest: 0,
    weeklyTarget: 4,
    thisWeekCompleted: 0,
    weekStart: ALPHA_WEEK_START,
  },
  weeklySeries: [],
  heatmap: [],
  recentPrs: [],
  chartDefaultLifts: [],
  trackedExercises: [],
};
