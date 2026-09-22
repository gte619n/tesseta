// Wire types for the web Overview read-model (IMPL-WEB-WORKOUT-01 §5). Mirrors
// the backend com.gte619n.healthfitness.core.workoutstats records + the
// SessionDetailResponse. Import-safe from client and server.

import type { ScheduledWorkoutResponse } from "./workout-program";

export type WorkoutStreak = {
  current: number;
  longest: number;
  weeklyTarget: number;
  thisWeekCompleted: number;
  weekStart: string; // ISO date (Monday)
};

export type WeekPoint = {
  weekStart: string; // ISO date (Monday)
  sessions: number;
  tonnageLbs: number;
};

export type SessionRef = {
  programId: string;
  scheduledId: string;
};

export type HeatmapDay = {
  date: string; // ISO date
  sessionCount: number;
  first: SessionRef;
  // IMPL-DELOAD-01 (D4): the day's representative session was a scheduled deload.
  isDeload: boolean;
};

export type PrPoint = {
  exerciseId: string;
  exerciseName: string;
  e1rmLbs: number;
  weightLbs: number;
  reps: number | null;
  date: string; // ISO date
  programId: string;
  scheduledId: string;
  // IMPL-PROG-LOAD-01 (D3/D8): 1 or 2, and pre-doubled TOTAL values for per-hand
  // lifts (dumbbell / dual-cable). Web renders the totals.
  loadFactor: number;
  e1rmTotalLbs: number;
  weightTotalLbs: number;
};

export type LiftRef = {
  exerciseId: string;
  exerciseName: string;
};

export type TrackedExercise = {
  exerciseId: string;
  exerciseName: string;
  lastPerformed: string | null;
};

export type WorkoutStats = {
  streak: WorkoutStreak;
  weeklySeries: WeekPoint[];
  heatmap: HeatmapDay[];
  recentPrs: PrPoint[];
  chartDefaultLifts: LiftRef[];
  trackedExercises: TrackedExercise[];
};

export type E1rmPoint = {
  date: string; // ISO date
  e1rmLbs: number;
  weightLbs: number | null;
  reps: number | null;
  lowConfidence: boolean;
  // IMPL-PROG-LOAD-01: pre-doubled TOTAL values for per-hand lifts.
  e1rmTotalLbs: number;
  weightTotalLbs: number | null;
};

export type E1rmBelief = {
  e1rmLbs: number;
  sigmaLbs: number;
  confidence: string; // HIGH | MEDIUM | LOW
};

export type E1rmHistory = {
  exerciseId: string;
  exerciseName: string;
  points: E1rmPoint[];
  currentBelief: E1rmBelief | null;
  // IMPL-PROG-LOAD-01: 1 for total-load lifts, 2 for per-hand. Multiply the
  // belief by this; the points already carry pre-doubled totals.
  loadFactor: number;
};

// A pointer to an adjacent session for prev/next navigation on the detail page.
export type NeighborRef = {
  programId: string;
  scheduledId: string;
  date: string;
};

// GET /api/me/workout-programs/{programId}/sessions/{scheduledId} — the full
// performed session plus the PR set keys to badge and the neighbors to page to.
export type SessionDetailResponse = {
  session: ScheduledWorkoutResponse;
  prSetKeys: string[]; // "blockId:orderIndex:setIndex"
  prev: NeighborRef | null;
  next: NeighborRef | null;
};
