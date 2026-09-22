// Hand-computed fixtures for the Overview functional tests
// (IMPL-WEB-WORKOUT-01 §7.2). See ./workout-stats.README.md for the derivation
// of every expected number. `user-alpha` is a rich account; `user-empty` is a
// brand-new user. These drive the RTL component tests that stand in for the
// spec's Playwright e2e (decision IL-9) — same fixtures, same expected values.

import type {
  WorkoutStats,
  E1rmHistory,
  PrPoint,
  SessionDetailResponse,
} from "@/lib/types/workout-stats";
import type { ScheduledWorkoutResponse } from "@/lib/types/workout-program";
import type { ProgressionLog } from "@/lib/types/progression";

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
      isDeload: false,
    },
    {
      // IMPL-DELOAD-01: a deload-week session day (badged in the tooltip).
      date: "2026-08-30",
      sessionCount: 1,
      first: { programId: "wp_alpha", scheduledId: "2026-08-30_d3" },
      isDeload: true,
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
      loadFactor: 1,
      e1rmTotalLbs: 262.5,
      weightTotalLbs: 225,
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
      loadFactor: 1,
      e1rmTotalLbs: 367.5,
      weightTotalLbs: 315,
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
    { date: "2026-05-04", e1rmLbs: 205, weightLbs: 205, reps: null, lowConfidence: true, e1rmTotalLbs: 205, weightTotalLbs: 205 },
    { date: "2026-06-01", e1rmLbs: 227.5, weightLbs: 195, reps: 5, lowConfidence: false, e1rmTotalLbs: 227.5, weightTotalLbs: 195 },
    { date: "2026-07-06", e1rmLbs: 233.3, weightLbs: 200, reps: 5, lowConfidence: false, e1rmTotalLbs: 233.3, weightTotalLbs: 200 },
    { date: "2026-08-03", e1rmLbs: 245.0, weightLbs: 210, reps: 5, lowConfidence: false, e1rmTotalLbs: 245.0, weightTotalLbs: 210 },
    { date: "2026-08-30", e1rmLbs: 250.8, weightLbs: 215, reps: 5, lowConfidence: false, e1rmTotalLbs: 250.8, weightTotalLbs: 215 },
    { date: "2026-09-12", e1rmLbs: 262.5, weightLbs: 225, reps: 5, lowConfidence: false, e1rmTotalLbs: 262.5, weightTotalLbs: 225 },
  ],
  currentBelief: { e1rmLbs: 265, sigmaLbs: 6.2, confidence: "HIGH" },
  loadFactor: 1,
};

// IMPL-PROG-LOAD-01 P2: a mixed PR list — one barbell (factor 1) and one
// dumbbell (factor 2, 90/hand → 180 total) — for the per-hand display test.
export const perHandPrList: PrPoint[] = [
  {
    exerciseId: "ex_db_press",
    exerciseName: "Dumbbell Bench Press",
    e1rmLbs: 105,
    weightLbs: 90,
    reps: 5,
    date: "2026-09-11",
    programId: "wp_alpha",
    scheduledId: "2026-09-11_d1",
    loadFactor: 2,
    e1rmTotalLbs: 210,
    weightTotalLbs: 180,
  },
  {
    exerciseId: "ex_bb_bench",
    exerciseName: "Barbell Bench Press",
    e1rmLbs: 262.5,
    weightLbs: 225,
    reps: 5,
    date: "2026-09-12",
    programId: "wp_alpha",
    scheduledId: "2026-09-12_d1",
    loadFactor: 1,
    e1rmTotalLbs: 262.5,
    weightTotalLbs: 225,
  },
];

// IMPL-PROG-LOAD-01: a per-hand dumbbell lift (loadFactor 2). Points carry
// pre-doubled totals; belief 105/hand → 210 total.
export const alphaDumbbellHistory: E1rmHistory = {
  exerciseId: "ex_db_press",
  exerciseName: "Dumbbell Bench Press",
  points: [
    { date: "2026-08-14", e1rmLbs: 93.3, weightLbs: 80, reps: 5, lowConfidence: false, e1rmTotalLbs: 186.6, weightTotalLbs: 160 },
    { date: "2026-09-11", e1rmLbs: 105, weightLbs: 90, reps: 5, lowConfidence: false, e1rmTotalLbs: 210, weightTotalLbs: 180 },
  ],
  currentBelief: { e1rmLbs: 106, sigmaLbs: 4, confidence: "HIGH" },
  loadFactor: 2,
};

// The squat curve returned by the (mocked) picker fetch when the user switches
// lifts (E2E-6). Its current belief is 405 — the value the test asserts.
export const alphaSquatHistory: E1rmHistory = {
  exerciseId: "ex_squat",
  exerciseName: "Back Squat",
  points: [
    { date: "2026-07-06", e1rmLbs: 370, weightLbs: 315, reps: 5, lowConfidence: false, e1rmTotalLbs: 370, weightTotalLbs: 315 },
    { date: "2026-09-10", e1rmLbs: 402, weightLbs: 345, reps: 5, lowConfidence: false, e1rmTotalLbs: 402, weightTotalLbs: 345 },
  ],
  currentBelief: { e1rmLbs: 405, sigmaLbs: 8, confidence: "HIGH" },
  loadFactor: 1,
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

// IMPL-DELOAD-01 P2: a completed DELOAD session — target 36 retained, athlete
// did 35 top set → session detail shows the Deload badge, the target delta, and
// the load basis.
export const alphaDeloadSessionDetail: SessionDetailResponse = {
  session: {
    ...alphaSessionDetail.session,
    scheduledId: "2026-09-19_d1",
    date: "2026-09-19",
    dayLabel: "Push (deload)",
    isDeload: true,
    session: {
      ...alphaSessionDetail.session.session,
      blocks: [
        {
          blockId: "b1",
          type: "MAIN",
          title: "Main",
          orderIndex: 0,
          prescriptions: [
            {
              ...alphaSessionDetail.session.session.blocks[0]!.prescriptions[0]!,
              sets: 2,
              targetWeightLbs: 36,
              loadBasis: "deload week · resumes 40 lb next week",
              loggedSets: [
                { weightLbs: 35, reps: 12, rir: 4, rirSource: "REPORTED", rpe: null, restSeconds: 120, completedAt: null },
                { weightLbs: 35, reps: 12, rir: 3, rirSource: "REPORTED", rpe: null, restSeconds: 120, completedAt: null },
              ],
            },
          ],
        },
      ],
    },
  },
  prSetKeys: [],
  prev: null,
  next: null,
};

// IMPL-DELOAD-01 P2: a progression-log fixture — a HOLD row, a DELOAD row, and
// a pre-retention row (null target), for a per-hand (×2) dumbbell lift.
export const dbPressProgressionLog: ProgressionLog = {
  exerciseId: "ex_db_press",
  exerciseName: "Dumbbell Overhead Press",
  loadFactor: 2,
  rows: [
    {
      date: "2026-09-21",
      path: "DELOAD",
      direction: "DOWN",
      targetWeightLbs: 35,
      loadBasis: "deload week",
      rationaleInputs: ["deload week → 10% lighter, sets ×0.5", "resumes 40 lb next week"],
      topSetWeightLbs: 35,
      topSetReps: 12,
      loggedSetCount: 2,
      isDeload: true,
      programId: "wp_alpha",
      scheduledId: "2026-09-21_d1",
      targetTotalLbs: 70,
      topSetTotalLbs: 70,
    },
    {
      date: "2026-09-14",
      path: "WARMUP",
      direction: "HOLD",
      targetWeightLbs: 40,
      loadBasis: "double progression",
      rationaleInputs: ["last: 40×8,8,8", "in range → add a rep toward 12"],
      topSetWeightLbs: 40,
      topSetReps: 8,
      loggedSetCount: 3,
      isDeload: false,
      programId: "wp_alpha",
      scheduledId: "2026-09-14_d1",
      targetTotalLbs: 80,
      topSetTotalLbs: 80,
    },
    {
      // Completed before target retention shipped: no target/rationale survives.
      date: "2026-09-07",
      path: null,
      direction: null,
      targetWeightLbs: null,
      loadBasis: null,
      rationaleInputs: [],
      topSetWeightLbs: 35,
      topSetReps: 12,
      loggedSetCount: 3,
      isDeload: false,
      programId: "wp_alpha",
      scheduledId: "2026-09-07_d1",
      targetTotalLbs: null,
      topSetTotalLbs: 70,
    },
  ],
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
