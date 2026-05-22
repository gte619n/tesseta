import Link from "next/link";
import { notFound } from "next/navigation";
import { apiFetch, apiJson, BackendError } from "@/lib/api";

type DexaRegion = {
  totalMassLb: number | null;
  leanTissueLb: number | null;
  fatTissueLb: number | null;
  regionFatPercent: number | null;
};

type DexaScan = {
  scanId: string;
  measuredOn: string | null;
  sourceFacility: string | null;
  totalMassLb: number | null;
  leanTissueLb: number | null;
  fatTissueLb: number | null;
  totalBodyFatPercent: number | null;
  visceralFatLb: number | null;
  androidGynoidRatio: number | null;
  trunk: DexaRegion | null;
  android: DexaRegion | null;
  gynoid: DexaRegion | null;
  armsTotal: DexaRegion | null;
  armsRight: DexaRegion | null;
  armsLeft: DexaRegion | null;
  legsTotal: DexaRegion | null;
  legsRight: DexaRegion | null;
  legsLeft: DexaRegion | null;
  bmdTScore: number | null;
  bmdZScore: number | null;
  restingMetabolicRateKcal: number | null;
};

export const dynamic = "force-dynamic";

export default async function DexaScanDetailPage({
  params,
}: {
  params: Promise<{ scanId: string }>;
}) {
  const { scanId } = await params;

  let scan: DexaScan;
  try {
    scan = await apiJson<DexaScan>(`/api/me/dexa/scans/${scanId}`);
  } catch (e) {
    if (e instanceof BackendError && e.status === 404) {
      notFound();
    }
    throw e;
  }

  return (
    <main className="min-h-screen bg-canvas p-8">
      <div className="mx-auto max-w-[920px] space-y-6">
        <Link
          href="/me/body-composition"
          className="inline-flex items-center gap-1.5 font-mono text-[11px] uppercase tracking-[0.04em] text-tertiary hover:text-secondary"
        >
          ← Body composition
        </Link>
        <header className="flex items-baseline justify-between">
          <div>
            <h1 className="m-0 text-[22px] font-medium tracking-[-0.015em] text-primary">
              DEXA scan
            </h1>
            <div className="mt-1 font-mono text-[12px] text-tertiary">
              {scan.measuredOn ?? "Date unknown"}
              {scan.sourceFacility && <span> · {scan.sourceFacility}</span>}
            </div>
          </div>
          <a
            href={`/api/dexa/${scan.scanId}/pdf`}
            className="cursor-pointer rounded-md border-[0.5px] border-border-default bg-canvas px-3 py-1.5 text-[12px] font-medium text-primary"
          >
            Download original PDF
          </a>
        </header>

        <Section title="Whole body">
          <div className="grid grid-cols-4 gap-3">
            <Stat label="Total mass" value={scan.totalMassLb} unit="lb" />
            <Stat label="Lean tissue" value={scan.leanTissueLb} unit="lb" />
            <Stat label="Fat tissue" value={scan.fatTissueLb} unit="lb" />
            <Stat label="Body fat" value={scan.totalBodyFatPercent} unit="%" />
          </div>
        </Section>

        <Section title="Abdomen">
          <div className="grid grid-cols-2 gap-3">
            <Stat label="Visceral fat" value={scan.visceralFatLb} unit="lb" />
            <Stat
              label="A/G ratio"
              value={scan.androidGynoidRatio}
              unit=""
              fractionDigits={2}
              tooltip={
                <AgBreakdown
                  android={scan.android}
                  gynoid={scan.gynoid}
                />
              }
            />
          </div>
        </Section>

        <Section title="Regions">
          <RegionTable
            rows={[
              { label: "Trunk", region: scan.trunk },
              { label: "Arms (total)", region: scan.armsTotal },
              { label: "Arms — right", region: scan.armsRight, sub: true },
              { label: "Arms — left", region: scan.armsLeft, sub: true },
              { label: "Legs (total)", region: scan.legsTotal },
              { label: "Legs — right", region: scan.legsRight, sub: true },
              { label: "Legs — left", region: scan.legsLeft, sub: true },
            ]}
          />
        </Section>

        <Section title="Bone density">
          <div className="grid grid-cols-2 gap-3">
            <Stat label="T-score" value={scan.bmdTScore} unit="" fractionDigits={1} />
            <Stat label="Z-score" value={scan.bmdZScore} unit="" fractionDigits={1} />
          </div>
        </Section>

        <Section title="Metabolism">
          <div className="grid grid-cols-2 gap-3">
            <Stat
              label="RMR"
              value={scan.restingMetabolicRateKcal}
              unit="kcal/day"
              fractionDigits={0}
            />
          </div>
        </Section>
      </div>
    </main>
  );
}

function Section({
  title,
  children,
}: {
  title: string;
  children: React.ReactNode;
}) {
  return (
    <section className="rounded-[14px] border-[0.5px] border-border-default bg-surface p-5">
      <h2 className="m-0 mb-3 caps-mono text-[10px] tracking-[0.08em] text-tertiary">
        {title}
      </h2>
      {children}
    </section>
  );
}

function Stat({
  label,
  value,
  unit,
  fractionDigits = 1,
  tooltip,
}: {
  label: string;
  value: number | null;
  unit: string;
  fractionDigits?: number;
  tooltip?: React.ReactNode;
}) {
  const body = (
    <>
      <div className="flex items-center gap-1.5">
        <div className="font-mono text-[10px] uppercase tracking-[0.06em] text-tertiary">
          {label}
        </div>
        {tooltip && (
          <span
            className="font-mono text-[9px] text-quaternary"
            aria-hidden
          >
            ⓘ
          </span>
        )}
      </div>
      <div className="mt-1 font-mono text-[20px] font-medium tabular text-primary">
        {value !== null ? value.toFixed(fractionDigits) : "—"}
        {unit && value !== null && (
          <span className="ml-1 text-[12px] text-secondary">{unit}</span>
        )}
      </div>
    </>
  );

  if (!tooltip) return <div>{body}</div>;

  // Hover popover. Pure CSS via group-hover keeps this a server
  // component — no useState, no client boundary.
  return (
    <div className="group relative cursor-help">
      {body}
      <div className="pointer-events-none absolute left-0 top-full z-20 mt-2 w-[280px] opacity-0 transition-opacity duration-100 group-hover:opacity-100">
        <div className="rounded-[10px] border-[0.5px] border-border-default bg-surface px-4 py-3 shadow-[0_12px_32px_rgba(0,0,0,0.12)]">
          {tooltip}
        </div>
      </div>
    </div>
  );
}

// Compact android + gynoid breakdown for the A/G ratio tooltip.
function AgBreakdown({
  android,
  gynoid,
}: {
  android: DexaRegion | null;
  gynoid: DexaRegion | null;
}) {
  return (
    <>
      <div className="caps-mono text-[9px] tracking-[0.08em] text-tertiary">
        Android / Gynoid regions
      </div>
      <table className="mt-2 w-full font-mono text-[11px] tabular">
        <thead>
          <tr className="text-tertiary">
            <th className="py-0.5 text-left font-normal" />
            <th className="py-0.5 text-right font-normal">Total</th>
            <th className="py-0.5 text-right font-normal">Lean</th>
            <th className="py-0.5 text-right font-normal">Fat</th>
            <th className="py-0.5 text-right font-normal">%</th>
          </tr>
        </thead>
        <tbody>
          <AgRow label="Android" region={android} />
          <AgRow label="Gynoid" region={gynoid} />
        </tbody>
      </table>
    </>
  );
}

function AgRow({
  label,
  region,
}: {
  label: string;
  region: DexaRegion | null;
}) {
  return (
    <tr className="border-t-[0.5px] border-border-subtle">
      <td className="py-1 text-secondary">{label}</td>
      <td className="py-1 text-right text-primary">
        {fmt(region?.totalMassLb, "")}
      </td>
      <td className="py-1 text-right text-primary">
        {fmt(region?.leanTissueLb, "")}
      </td>
      <td className="py-1 text-right text-primary">
        {fmt(region?.fatTissueLb, "")}
      </td>
      <td className="py-1 text-right text-primary">
        {fmt(region?.regionFatPercent, "%")}
      </td>
    </tr>
  );
}

function RegionTable({
  rows,
}: {
  rows: { label: string; region: DexaRegion | null; sub?: boolean }[];
}) {
  return (
    <table className="w-full font-mono text-[12px] tabular">
      <thead>
        <tr className="text-left text-tertiary">
          <th className="py-1.5 font-normal">Region</th>
          <th className="py-1.5 text-right font-normal">Total</th>
          <th className="py-1.5 text-right font-normal">Lean</th>
          <th className="py-1.5 text-right font-normal">Fat</th>
          <th className="py-1.5 text-right font-normal">Fat %</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((row) => (
          <tr
            key={row.label}
            className="border-t-[0.5px] border-border-subtle"
          >
            <td
              className={`py-1.5 ${row.sub ? "pl-4 text-tertiary" : "text-secondary"}`}
            >
              {row.label}
            </td>
            <td className="py-1.5 text-right text-primary">
              {fmt(row.region?.totalMassLb, "lb")}
            </td>
            <td className="py-1.5 text-right text-primary">
              {fmt(row.region?.leanTissueLb, "lb")}
            </td>
            <td className="py-1.5 text-right text-primary">
              {fmt(row.region?.fatTissueLb, "lb")}
            </td>
            <td className="py-1.5 text-right text-primary">
              {fmt(row.region?.regionFatPercent, "%")}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function fmt(value: number | null | undefined, unit: string): React.ReactNode {
  if (value === null || value === undefined) {
    return <span className="text-tertiary">—</span>;
  }
  return (
    <>
      {value.toFixed(1)}
      <span className="ml-1 text-tertiary">{unit}</span>
    </>
  );
}
