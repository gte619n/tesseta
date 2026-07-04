import { Suspense } from "react";
import { cache } from "react";
import type { Session } from "next-auth";
import { revalidatePath } from "next/cache";
import { absoluteTitle } from "@/lib/page-metadata";
import { auth } from "@/auth";
import { BloodPanel } from "@/components/dashboard/BloodPanel";
import { BodyCompositionCard } from "@/components/dashboard/BodyCompositionCard";
import { RecentFeed } from "@/components/dashboard/RecentFeed";
import { Sidebar, type SidebarUser } from "@/components/dashboard/Sidebar";
import { StatCard } from "@/components/dashboard/StatCard";
import { TodaysDosesCard } from "@/components/dashboard/TodaysDosesCard";
import { WeightStatCard } from "@/components/dashboard/WeightStatCard";
import { LiveDateline } from "@/components/dashboard/LiveDateline";
import { isAdmin } from "@/lib/admin";
import { apiFetch, apiJson } from "@/lib/api";
import { loadRecentFeed } from "@/lib/recent-feed";
import { loadBloodPanel } from "@/lib/blood-panel";
import { loadBodyComposition } from "@/lib/body-composition-dashboard";
import { loadDailyMetrics, emptyVital } from "@/lib/dashboard-vitals";
import type { TodaysDose, TimeWindow } from "@/lib/types/medication";

export const metadata = absoluteTitle("tesseta");

export const dynamic = "force-dynamic";

export default async function DashboardPage() {
  // The session is needed for the shell identity (sidebar) and is resolved
  // once per render (React cache() in lib/api). Resolving it here lets the
  // shell + skeletons paint immediately; each data-heavy section below is its
  // own async Server Component behind a <Suspense> boundary, so a slow
  // endpoint only delays its own card instead of blocking the whole page.
  const session = await auth();
  const sidebarUser = toSidebarUser(session);

  return (
    <div className="flex min-h-screen items-start justify-center p-8">
      <div className="grid w-[1200px] max-w-full grid-cols-[220px_1fr] overflow-hidden rounded-[14px] border-[0.5px] border-border-default bg-canvas shadow-[0_24px_64px_rgba(0,0,0,0.08)]">
        <Suspense fallback={<Sidebar user={sidebarUser} isAdmin={false} />}>
          <SidebarSection user={sidebarUser} />
        </Suspense>
        <main className="overflow-hidden px-7 pb-7 pt-[22px]">
          <TopBar />

          <section className="mb-3 grid grid-cols-5 gap-2.5">
            <Suspense fallback={<StatCardSkeleton />}>
              <WeightStatSection />
            </Suspense>
            <Suspense fallback={<DailyVitalsSkeleton />}>
              <DailyVitalsSection />
            </Suspense>
          </section>

          <Suspense fallback={<BodyCompositionSkeleton />}>
            <BodyCompositionSection />
          </Suspense>

          <section className="mb-3 grid grid-cols-2 gap-2.5">
            <Suspense fallback={<BloodPanelSkeleton />}>
              <BloodPanelSection />
            </Suspense>
            <Suspense fallback={<TodaysDosesSkeleton />}>
              <TodaysDosesSection />
            </Suspense>
          </section>

          <Suspense fallback={<RecentFeedSkeleton />}>
            <RecentFeedSection />
          </Suspense>
        </main>
      </div>
    </div>
  );
}

// ── Async section components (each its own Suspense boundary) ─────────────

async function SidebarSection({ user }: { user: SidebarUser }) {
  const admin = await isAdmin();
  return <Sidebar user={user} isAdmin={admin} />;
}

// Body composition feeds both the top-row Weight StatCard and the
// BodyCompositionCard. Those live in separate Suspense boundaries, so the
// loader is memoized with React cache() to coalesce them into one backend
// request per render.
const loadBodyCompositionCached = cache(loadBodyComposition);

async function WeightStatSection() {
  const view = await loadBodyCompositionCached();
  if (view?.weightStat) {
    return <WeightStatCard stat={view.weightStat} />;
  }
  return <StatCard stat={emptyVital("Weight", "scale")} />;
}

async function DailyVitalsSection() {
  const dailyVitals = await loadDailyMetrics();
  return (
    <>
      <StatCard stat={dailyVitals.restingHr} />
      <StatCard stat={dailyVitals.hrv} />
      <StatCard stat={dailyVitals.sleep} />
      <StatCard stat={dailyVitals.steps} />
    </>
  );
}

async function BodyCompositionSection() {
  const view = await loadBodyCompositionCached();
  return <BodyCompositionCard view={view} />;
}

async function BloodPanelSection() {
  const bloodPanel = await loadBloodPanel();
  return <BloodPanel data={bloodPanel} compact />;
}

async function TodaysDosesSection() {
  const todaysDoses = await loadTodaysDoses();

  async function logDose(medicationId: string, window: TimeWindow) {
    "use server";
    const res = await apiFetch(`/api/me/medications/${medicationId}/adherence`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ window }),
    });
    if (!res.ok) {
      throw new Error(`Failed to log dose: ${res.status}`);
    }
    revalidatePath("/");
  }

  return <TodaysDosesCard doses={todaysDoses} logDose={logDose} compact />;
}

async function RecentFeedSection() {
  const entries = await loadRecentFeed();
  return <RecentFeed entries={entries} variant="desktop" />;
}

// Load today's scheduled medication doses.
async function loadTodaysDoses(): Promise<TodaysDose[]> {
  try {
    return await apiJson<TodaysDose[]>("/api/me/medications/today");
  } catch {
    return [];
  }
}

// ── Skeleton fallbacks (sized to match each card to avoid layout shift) ───

// One StatCard cell. The real StatCard renders inside the 5-col grid; the
// skeleton mirrors its border/padding/height footprint.
function StatCardSkeleton() {
  return (
    <div className="h-[92px] animate-pulse rounded-[9px] border-[0.5px] border-border-default bg-surface" />
  );
}

// The four daily-vitals cells occupy columns 2–5 of the same 5-col grid.
function DailyVitalsSkeleton() {
  return (
    <>
      <StatCardSkeleton />
      <StatCardSkeleton />
      <StatCardSkeleton />
      <StatCardSkeleton />
    </>
  );
}

function BodyCompositionSkeleton() {
  return (
    <div className="mb-3 h-[260px] animate-pulse rounded-[10px] border-[0.5px] border-border-default bg-surface" />
  );
}

function BloodPanelSkeleton() {
  return (
    <div className="h-[220px] animate-pulse rounded-[10px] border-[0.5px] border-border-default bg-surface" />
  );
}

function RecentFeedSkeleton() {
  return (
    <div className="h-[220px] animate-pulse rounded-[10px] border-[0.5px] border-border-default bg-surface" />
  );
}

function TodaysDosesSkeleton() {
  return (
    <div className="h-[220px] animate-pulse rounded-[10px] border-[0.5px] border-border-default bg-surface" />
  );
}

// ── Shell chrome ──────────────────────────────────────────────────────────

function toSidebarUser(session: Session | null): SidebarUser {
  const name = session?.user?.name ?? session?.user?.email ?? "—";
  const email = session?.user?.email ?? null;
  return { name, email, initials: initialsFor(name), image: session?.user?.image ?? null };
}

function initialsFor(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return "—";
  if (parts.length === 1) return (parts[0] ?? "").slice(0, 2).toUpperCase();
  const first = parts[0] ?? "";
  const last = parts[parts.length - 1] ?? "";
  return ((first[0] ?? "") + (last[0] ?? "")).toUpperCase();
}

function TopBar() {
  return (
    <div className="mb-5 flex items-center justify-between">
      <div>
        <h1 className="m-0 text-[22px] font-medium tracking-[-0.015em] text-primary">
          Dashboard
        </h1>
        <div className="mt-[3px] font-mono text-[11px] tracking-[0.04em] text-tertiary tabular">
          <LiveDateline />
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
