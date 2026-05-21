import { BloodPanel } from "@/components/dashboard/BloodPanel";
import { RecentFeed } from "@/components/dashboard/RecentFeed";
import { SectionTitle } from "@/components/dashboard/SectionTitle";
import { Sidebar } from "@/components/dashboard/Sidebar";
import { StatCard } from "@/components/dashboard/StatCard";
import { TodayCard } from "@/components/dashboard/TodayCard";
import { WeightChart } from "@/components/dashboard/WeightChart";
import { bodyComp, recent, todayHeader, vitals } from "@/lib/fixtures/dashboard";

export const dynamic = "force-dynamic";

export default function DashboardPage() {
  return (
    <div className="flex min-h-screen items-start justify-center p-8">
      <div className="grid w-[1200px] max-w-full grid-cols-[220px_1fr] overflow-hidden rounded-[14px] border-[0.5px] border-border-default bg-canvas shadow-[0_24px_64px_rgba(0,0,0,0.08)]">
        <Sidebar />
        <main className="overflow-hidden px-7 pb-7 pt-[22px]">
          <TopBar />

          <section className="mb-3 grid grid-cols-4 gap-2.5">
            {vitals.map((v) => (
              <StatCard key={v.label} stat={v} />
            ))}
          </section>

          <BodyCompositionCard />

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

function BodyCompositionCard() {
  return (
    <div className="mb-3 rounded-[10px] border-[0.5px] border-border-default bg-surface px-5 py-[18px]">
      <div className="mb-3.5 flex items-start justify-between">
        <div>
          <SectionTitle>Body composition</SectionTitle>
          <div className="mt-3 flex items-baseline gap-[18px]">
            <div>
              <div className="font-mono text-[36px] font-medium leading-none tracking-[-0.03em] text-primary tabular">
                {bodyComp.primary.value}
                <span className="ml-1 text-[13px] font-normal text-tertiary">
                  {bodyComp.primary.unit}
                </span>
              </div>
              <div className="caps-mono mt-[5px] text-[10px] text-good">
                {bodyComp.primary.delta}
              </div>
            </div>
            <div className="h-[42px] w-px bg-border-default" aria-hidden />
            {bodyComp.secondary.map((s) => (
              <div key={s.unit}>
                <div className="font-mono text-[18px] font-medium leading-none tracking-[-0.01em] text-primary tabular">
                  {s.value}
                  <span className="ml-[3px] text-[10px] font-normal text-tertiary">
                    {s.unit}
                  </span>
                </div>
                <div className="caps-mono mt-[5px] text-[10px] text-good">
                  {s.delta}
                </div>
              </div>
            ))}
          </div>
        </div>
        <RangeSegment />
      </div>
      <WeightChart variant="desktop" />
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

function RangeSegment() {
  return (
    <div className="flex gap-px rounded-md bg-canvas p-0.5">
      {bodyComp.range.options.map((o) => {
        const active = o === bodyComp.range.active;
        return (
          <button
            type="button"
            key={o}
            className={`cursor-pointer rounded-[5px] border-0 px-2.5 py-[5px] text-[11px] font-medium ${
              active
                ? "border-[0.5px] border-border-default bg-surface text-primary"
                : "bg-transparent text-tertiary"
            }`}
          >
            {o}
          </button>
        );
      })}
    </div>
  );
}
