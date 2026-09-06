import { notFound } from "next/navigation";
import { ProgressionConsole } from "@/components/workouts/ProgressionConsole";
import type {
  PatternReview,
  BlockParameters,
  ExerciseStrength,
} from "@/lib/types/progression";
import type { EnergyBalance } from "@/lib/types/plan";
import { pageMetadata } from "@/lib/page-metadata";

export const metadata = pageMetadata("Progression Engine preview");

// Dev-only UI harness for the progression console. Lives under /auth/* so the
// middleware leaves it public (no Auth.js session or backend required), letting
// us iterate on the styling live — e.g. bound to a Tailscale IP. It 404s in a
// production build. The real page is /me/workouts/progression.

const SAMPLE_WEEK: PatternReview[] = [
  {
    pattern: "PUSH_HORIZONTAL",
    trend: "RISING",
    weeklySlopePct: 2.4,
    fatigueIndex: 0.3,
    currentTarget: 12,
    proposedTarget: 14,
    deload: false,
    reasoning: "Volume trending up with low fatigue — add a set.",
  },
  {
    pattern: "PULL_VERTICAL",
    trend: "FALLING",
    weeklySlopePct: -1.1,
    fatigueIndex: 0.8,
    currentTarget: 14,
    proposedTarget: 10,
    deload: true,
    reasoning: "Fatigue high and volume slipping — deload this pattern.",
  },
  {
    pattern: "SQUAT",
    trend: "FLAT",
    weeklySlopePct: 0.2,
    fatigueIndex: 0.5,
    currentTarget: 10,
    proposedTarget: 10,
    deload: false,
    reasoning: "Holding steady — keep the current volume for another week.",
  },
];

// Pinned to GAINING while the measured balance is RECOMP → shows the divergence.
const SAMPLE_BLOCK: BlockParameters = {
  mode: "GAINING",
  expectedDriftPerDay: 0.1, // lb/day of e1RM → +0.7 lb/week
  successCriterion: "ADD_LOAD",
  manualOverride: true,
  repRanges: {
    PUSH_HORIZONTAL: [6, 10],
    PULL_VERTICAL: [8, 12],
    SQUAT: [5, 8],
  },
  rirCaps: { COMPOUND: 2, ISOLATION: 1 },
  weeklyCeiling: { PUSH_HORIZONTAL: 18, PULL_VERTICAL: 20, SQUAT: 16 },
};

const SAMPLE_STRENGTH: ExerciseStrength[] = [
  { exerciseId: "e1", name: "Conventional Deadlift", movementPattern: "HINGE", e1rmLbs: 405, confidence: "HIGH", observationCount: 11 },
  { exerciseId: "e2", name: "Barbell Back Squat", movementPattern: "SQUAT", e1rmLbs: 315, confidence: "HIGH", observationCount: 14 },
  { exerciseId: "e3", name: "Barbell Bench Press", movementPattern: "PUSH_HORIZONTAL", e1rmLbs: 245, confidence: "MEDIUM", observationCount: 6 },
  { exerciseId: "e4", name: "Overhead Press", movementPattern: "PUSH_VERTICAL", e1rmLbs: 150, confidence: "MEDIUM", observationCount: 7 },
  { exerciseId: "e5", name: "Weighted Pull-Up", movementPattern: "PULL_VERTICAL", e1rmLbs: 90, confidence: "LOW", observationCount: 3 },
];

const SAMPLE_GOAL = { title: "Drop to 12% body fat", domain: "BODY_COMPOSITION" };

const SAMPLE_ENERGY: EnergyBalance = {
  maintenanceKcal: 2680,
  meanIntakeKcal: 2740,
  balanceKcal: 60,
  mode: "RECOMP",
  hasIntakeData: true,
};

export default function ProgressionPreviewPage() {
  if (process.env.NODE_ENV === "production") notFound();

  return (
    <main className="min-h-screen bg-canvas p-8">
      <div className="mx-auto max-w-[720px] space-y-6">
        <span className="inline-block rounded-full bg-warn-bg px-2 py-0.5 caps-mono text-[9px] tracking-[0.06em] text-warn">
          Preview · sample data
        </span>

        <header>
          <h1 className="m-0 text-[22px] font-medium tracking-[-0.015em] text-primary">
            Progression Engine
          </h1>
          <p className="mt-1 max-w-[600px] text-[13px] leading-relaxed text-secondary">
            The engine tracks your true strength per exercise and adjusts your
            weights, reps, and sets after every workout. These trends and
            settings are tied to <em>you</em>, not to any one program — so they
            carry across every program you run. Your program decides{" "}
            <em>which</em> exercises; the engine decides the <em>numbers</em>.
          </p>
        </header>

        <ProgressionConsole
          weekReview={SAMPLE_WEEK}
          block={SAMPLE_BLOCK}
          strength={SAMPLE_STRENGTH}
          goal={SAMPLE_GOAL}
          energy={SAMPLE_ENERGY}
        />
      </div>
    </main>
  );
}
