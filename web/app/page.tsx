import type { Session } from "next-auth";
import { auth } from "@/auth";
import { BloodPanel } from "@/components/dashboard/BloodPanel";
import { RecentFeed } from "@/components/dashboard/RecentFeed";
import { SectionTitle } from "@/components/dashboard/SectionTitle";
import { Sidebar, type SidebarUser } from "@/components/dashboard/Sidebar";
import { StatCard } from "@/components/dashboard/StatCard";
import { TodayCard } from "@/components/dashboard/TodayCard";
import { WeightChart } from "@/components/dashboard/WeightChart";
import { apiJson } from "@/lib/api";
import { recent, todayHeader, vitals } from "@/lib/fixtures/dashboard";

// IMPL-04 wires the Sidebar identity and the BodyCompositionCard to real
// data. The other cards (StatCard row, BloodPanel, TodayCard, RecentFeed)
// remain on fixtures pending their own data sources — they'll get wired
// up as separate work lands.

const KG_TO_LB = 2.20462;
const NINETY_DAYS_MS = 90 * 24 * 60 * 60 * 1000;

type Metric = "WEIGHT_KG" | "BODY_FAT_PERCENT" | "LEAN_MASS_KG" | "BMI";

type Reading = {
  recordId: string;
  metric: Metric;
  value: number;
  sampleTime: string;
  sourcePlatform: string | null;
  recordingMethod: string | null;
};

type BodyCompositionView = {
  primary: { value: string; unit: string; delta: string | null };
  secondary: { value: string; unit: string; delta: string | null }[];
  series: number[];
  yMin: number;
  yMax: number;
  xLabels: { x: number; label: string }[];
};

export const dynamic = "force-dynamic";

export default async function DashboardPage() {
  const session = await auth();
  const sidebarUser = toSidebarUser(session);
  const view = await loadBodyComposition();

  return (
    <div className="flex min-h-screen items-start justify-center p-8">
      <div className="grid w-[1200px] max-w-full grid-cols-[220px_1fr] overflow-hidden rounded-[14px] border-[0.5px] border-border-default bg-canvas shadow-[0_24px_64px_rgba(0,0,0,0.08)]">
        <Sidebar user={sidebarUser} />
        <main className="overflow-hidden px-7 pb-7 pt-[22px]">
          <TopBar />

          <section className="mb-3 grid grid-cols-4 gap-2.5">
            {vitals.map((v) => (
              <StatCard key={v.label} stat={v} />
            ))}
          </section>

          <BodyCompositionCard view={view} />

          <section className="mb-3 grid grid-cols-2 gap-2.5">
            <BloodPanel compact />
            <TodayCard compact />
          </section>

          <RecentFeed entries={recent} variant="desktop" />
        </main>
      </div>
    </div>
  );
}

function toSidebarUser(session: Session | null): SidebarUser {
  const name = session?.user?.name ?? session?.user?.email ?? "—";
  const email = session?.user?.email ?? null;
  return { name, email, initials: initialsFor(name) };
}

function initialsFor(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return "—";
  if (parts.length === 1) return (parts[0] ?? "").slice(0, 2).toUpperCase();
  const first = parts[0] ?? "";
  const last = parts[parts.length - 1] ?? "";
  return ((first[0] ?? "") + (last[0] ?? "")).toUpperCase();
}

async function loadBodyComposition(): Promise<BodyCompositionView | null> {
  let readings: Reading[];
  try {
    readings = await apiJson<Reading[]>("/api/me/body-composition");
  } catch {
    return null;
  }
  if (readings.length === 0) return null;

  const now = Date.now();
  const ninetyDaysAgo = now - NINETY_DAYS_MS;

  const weightsAll = readings
    .filter((r) => r.metric === "WEIGHT_KG")
    .sort((a, b) => a.sampleTime.localeCompare(b.sampleTime));
  if (weightsAll.length === 0) return null;

  const weights90 = weightsAll.filter(
    (r) => new Date(r.sampleTime).getTime() >= ninetyDaysAgo,
  );
  const window = weights90.length >= 2 ? weights90 : weightsAll;

  const series = window.map((r) => r.value * KG_TO_LB);
  const latestWeight = series[series.length - 1] ?? 0;
  const avg90 = series.reduce((a, b) => a + b, 0) / series.length;
  const delta = latestWeight - avg90;
  const deltaStr = formatDelta(delta, "lb", "vs 90d avg");

  const latestBodyFat = readings
    .filter((r) => r.metric === "BODY_FAT_PERCENT")
    .sort((a, b) => b.sampleTime.localeCompare(a.sampleTime))[0];

  // Lean mass isn't sourced from Google Health; derive it from the most
  // recent weight + body-fat pair when both exist within a few hours.
  const leanMassLb = computeLeanMass(weightsAll, latestBodyFat);

  const yPadding = Math.max(1, (Math.max(...series) - Math.min(...series)) * 0.15);
  const yMin = Math.floor(Math.min(...series) - yPadding);
  const yMax = Math.ceil(Math.max(...series) + yPadding);

  const xLabels = buildXLabels(window);

  return {
    primary: {
      value: latestWeight.toFixed(1),
      unit: "lb",
      delta: deltaStr,
    },
    secondary: [
      {
        value: latestBodyFat ? latestBodyFat.value.toFixed(1) : "—",
        unit: "% fat",
        delta: null,
      },
      {
        value: leanMassLb !== null ? leanMassLb.toFixed(1) : "—",
        unit: "lb lean",
        delta: null,
      },
    ],
    series,
    yMin,
    yMax,
    xLabels,
  };
}

function formatDelta(delta: number, unit: string, suffix: string): string {
  if (Math.abs(delta) < 0.05) return `unchanged ${suffix}`;
  const arrow = delta < 0 ? "↓" : "↑";
  return `${arrow} ${Math.abs(delta).toFixed(1)} ${unit} ${suffix}`;
}

function computeLeanMass(
  weights: Reading[],
  bodyFat: Reading | undefined,
): number | null {
  if (!bodyFat) return null;
  const bfTime = new Date(bodyFat.sampleTime).getTime();
  const sixHours = 6 * 60 * 60 * 1000;
  // Find the weight reading closest in time to the body-fat reading,
  // within 6 hours, so we don't mix readings from different weigh-ins.
  let best: { reading: Reading; diff: number } | null = null;
  for (const w of weights) {
    const diff = Math.abs(new Date(w.sampleTime).getTime() - bfTime);
    if (diff <= sixHours && (best === null || diff < best.diff)) {
      best = { reading: w, diff };
    }
  }
  if (!best) return null;
  const weightLb = best.reading.value * KG_TO_LB;
  return weightLb * (1 - bodyFat.value / 100);
}

function buildXLabels(
  window: Reading[],
): { x: number; label: string }[] {
  if (window.length < 2) return [];
  const first = window[0];
  const last = window[window.length - 1];
  if (!first || !last) return [];
  // Four labels at fixed pixel positions matching the chart's 600px viewBox.
  // Pick four roughly-evenly spaced samples and format their sample dates.
  const ticks = [0, Math.floor(window.length / 3), Math.floor((2 * window.length) / 3), window.length - 1];
  const xs = [32, 180, 335, 500];
  return ticks.map((idx, i) => {
    const reading = window[Math.min(idx, window.length - 1)] ?? last;
    return { x: xs[i] ?? 0, label: shortDate(reading.sampleTime) };
  });
}

function shortDate(iso: string): string {
  const d = new Date(iso);
  const month = d.toLocaleString("en-US", { month: "short", timeZone: "UTC" }).toUpperCase();
  const day = String(d.getUTCDate()).padStart(2, "0");
  return `${month} ${day}`;
}

function TopBar() {
  return (
    <div className="mb-5 flex items-center justify-between">
      <div>
        <h1 className="m-0 text-[22px] font-medium tracking-[-0.015em] text-primary">
          Dashboard
        </h1>
        <div className="mt-[3px] font-mono text-[11px] tracking-[0.04em] text-tertiary tabular">
          {todayHeader.weekday} · {todayHeader.date} · {todayHeader.time} ·{" "}
          {todayHeader.tz}
        </div>
      </div>
      <div className="flex items-center gap-[7px]">
        <div className="flex cursor-pointer items-center gap-[7px] rounded-md border-[0.5px] border-border-default bg-surface px-3 py-[7px] font-mono text-[11px] tracking-[0.04em] text-secondary tabular">
          <i
            className="ti ti-calendar text-[13px] text-tertiary"
            aria-hidden
          />
          LAST 90 DAYS
          <i
            className="ti ti-chevron-down text-[12px] text-tertiary"
            aria-hidden
          />
        </div>
        <button
          type="button"
          aria-label="Search"
          className="flex h-[34px] w-[34px] cursor-pointer items-center justify-center rounded-md border-[0.5px] border-border-default bg-surface text-secondary"
        >
          <i className="ti ti-search text-[14px]" aria-hidden />
        </button>
        <button
          type="button"
          aria-label="Notifications"
          className="relative flex h-[34px] w-[34px] cursor-pointer items-center justify-center rounded-md border-[0.5px] border-border-default bg-surface text-secondary"
        >
          <i className="ti ti-bell text-[14px]" aria-hidden />
          <span
            aria-hidden
            className="absolute right-2 top-[7px] h-1.5 w-1.5 rounded-full border-[1.5px] border-surface bg-accent"
          />
        </button>
      </div>
    </div>
  );
}

function BodyCompositionCard({ view }: { view: BodyCompositionView | null }) {
  if (!view) {
    return (
      <div className="mb-3 rounded-[10px] border-[0.5px] border-border-default bg-surface px-5 py-[18px]">
        <SectionTitle>Body composition</SectionTitle>
        <p className="mt-3 text-[13px] leading-[1.55] text-secondary">
          No body-comp data yet.{" "}
          <a
            className="font-medium text-accent-dim underline-offset-2 hover:underline"
            href="/me/body-composition"
          >
            Connect Google Health
          </a>{" "}
          to start syncing weight and body fat from your scale.
        </p>
      </div>
    );
  }

  return (
    <div className="mb-3 rounded-[10px] border-[0.5px] border-border-default bg-surface px-5 py-[18px]">
      <div className="mb-3.5 flex items-start justify-between">
        <div>
          <SectionTitle>Body composition</SectionTitle>
          <div className="mt-3 flex items-baseline gap-[18px]">
            <div>
              <div className="font-mono text-[36px] font-medium leading-none tracking-[-0.03em] text-primary tabular">
                {view.primary.value}
                <span className="ml-1 text-[13px] font-normal text-tertiary">
                  {view.primary.unit}
                </span>
              </div>
              {view.primary.delta ? (
                <div className="caps-mono mt-[5px] text-[10px] text-good">
                  {view.primary.delta}
                </div>
              ) : null}
            </div>
            <div className="h-[42px] w-px bg-border-default" aria-hidden />
            {view.secondary.map((s, i) => (
              <div key={`${s.unit}-${i}`}>
                <div className="font-mono text-[18px] font-medium leading-none tracking-[-0.01em] text-primary tabular">
                  {s.value}
                  <span className="ml-[3px] text-[10px] font-normal text-tertiary">
                    {s.unit}
                  </span>
                </div>
                {s.delta ? (
                  <div className="caps-mono mt-[5px] text-[10px] text-good">
                    {s.delta}
                  </div>
                ) : null}
              </div>
            ))}
          </div>
        </div>
      </div>
      <WeightChart
        variant="desktop"
        series={view.series}
        yMin={view.yMin}
        yMax={view.yMax}
        xLabels={view.xLabels}
      />
      <div className="mt-2.5 flex gap-3.5 border-t-[0.5px] border-border-subtle pt-2.5">
        <div className="caps-mono flex items-center gap-1.5 text-[10px] tracking-[0.04em] text-secondary">
          <span
            aria-hidden
            className="inline-block h-[2px] w-2.5 rounded-[1px] bg-accent"
          />
          DAILY
        </div>
        <div className="caps-mono flex items-center gap-1.5 text-[10px] tracking-[0.04em] text-secondary">
          <span
            aria-hidden
            className="inline-block h-0 w-2.5 border-t border-dashed border-primary opacity-40"
          />
          7-DAY AVG
        </div>
      </div>
    </div>
  );
}
