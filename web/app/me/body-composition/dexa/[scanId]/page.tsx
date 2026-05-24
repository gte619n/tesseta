import type { Metadata } from "next";
import Link from "next/link";

export const metadata: Metadata = {
  title: "DEXA Scan",
};
import { notFound } from "next/navigation";
import { revalidatePath } from "next/cache";
import { apiFetch, apiJson, BackendError } from "@/lib/api";
import { EditableNumber } from "@/components/dexa/EditableNumber";
import { DeleteDexaButton } from "@/components/dexa/DeleteDexaButton";

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

  // Server action passed to every editable cell. Each click-to-edit
  // save round-trips through here; revalidatePath flushes the cached
  // Server Component output so the new value shows on next render.
  async function patchField(path: string, value: number | null) {
    "use server";
    const res = await apiFetch(`/api/me/dexa/scans/${scanId}/field`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ path, value }),
    });
    if (!res.ok) {
      const text = await res.text().catch(() => "");
      throw new Error(text || `Update failed: ${res.status}`);
    }
    revalidatePath(`/me/body-composition/dexa/${scanId}`);
  }

  async function deleteScan() {
    "use server";
    const res = await apiFetch(`/api/me/dexa/scans/${scanId}`, {
      method: "DELETE",
    });
    if (!res.ok) {
      const text = await res.text().catch(() => "");
      throw new Error(text || `Delete failed: ${res.status}`);
    }
    // Invalidate the list page so the deleted row disappears.
    revalidatePath("/me/body-composition");
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
            <Stat label="Total mass" value={scan.totalMassLb} unit="lb"
              onSave={patchField.bind(null, "totalMassLb")} />
            <Stat label="Lean tissue" value={scan.leanTissueLb} unit="lb"
              onSave={patchField.bind(null, "leanTissueLb")} />
            <Stat label="Fat tissue" value={scan.fatTissueLb} unit="lb"
              onSave={patchField.bind(null, "fatTissueLb")} />
            <Stat label="Body fat" value={scan.totalBodyFatPercent} unit="%"
              onSave={patchField.bind(null, "totalBodyFatPercent")} />
          </div>
        </Section>

        <Section title="Abdomen">
          <div className="grid grid-cols-2 gap-3">
            <Stat label="Visceral fat" value={scan.visceralFatLb} unit="lb"
              onSave={patchField.bind(null, "visceralFatLb")} />
            <Stat
              label="A/G ratio"
              value={scan.androidGynoidRatio}
              unit=""
              fractionDigits={2}
              onSave={patchField.bind(null, "androidGynoidRatio")}
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
              { label: "Trunk", region: scan.trunk, key: "trunk" },
              { label: "Arms (total)", region: scan.armsTotal, key: "armsTotal" },
              { label: "Arms — right", region: scan.armsRight, key: "armsRight", sub: true },
              { label: "Arms — left", region: scan.armsLeft, key: "armsLeft", sub: true },
              { label: "Legs (total)", region: scan.legsTotal, key: "legsTotal" },
              { label: "Legs — right", region: scan.legsRight, key: "legsRight", sub: true },
              { label: "Legs — left", region: scan.legsLeft, key: "legsLeft", sub: true },
            ]}
            patchField={patchField}
          />
        </Section>

        <Section title="Bone density">
          <div className="grid grid-cols-2 gap-3">
            <Stat label="T-score" value={scan.bmdTScore} unit="" fractionDigits={1}
              onSave={patchField.bind(null, "bmdTScore")} />
            <Stat label="Z-score" value={scan.bmdZScore} unit="" fractionDigits={1}
              onSave={patchField.bind(null, "bmdZScore")} />
          </div>
        </Section>

        <div className="flex justify-end pt-2">
          <DeleteDexaButton
            scanDate={scan.measuredOn}
            deleteAction={deleteScan}
          />
        </div>
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
  onSave,
}: {
  label: string;
  value: number | null;
  unit: string;
  fractionDigits?: number;
  tooltip?: React.ReactNode;
  onSave?: (next: number | null) => Promise<void>;
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
      <div className="mt-1 -ml-1">
        {onSave ? (
          <EditableNumber
            value={value}
            fractionDigits={fractionDigits}
            unit={unit}
            onSave={onSave}
          />
        ) : (
          <span className="font-mono text-[20px] font-medium tabular text-primary">
            {value !== null ? value.toFixed(fractionDigits) : "—"}
            {unit && value !== null && (
              <span className="ml-1 text-[12px] text-secondary">{unit}</span>
            )}
          </span>
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
  patchField,
}: {
  rows: {
    label: string;
    region: DexaRegion | null;
    key: string;
    sub?: boolean;
  }[];
  patchField: (path: string, value: number | null) => Promise<void>;
}) {
  return (
    <table className="w-full font-mono text-[12px] tabular">
      <thead>
        <tr className="text-left text-tertiary">
          <th className="py-1.5 font-normal">Region</th>
          <th className="py-1.5 text-right font-normal">Lean</th>
          <th className="py-1.5 text-right font-normal">Fat</th>
          <th className="py-1.5 text-right font-normal">Fat %</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((row) => (
          <tr
            key={row.key}
            className="border-t-[0.5px] border-border-subtle"
          >
            <td
              className={`py-1.5 ${row.sub ? "pl-4 text-tertiary" : "text-secondary"}`}
            >
              {row.label}
            </td>
            <RegionCell
              value={row.region?.leanTissueLb ?? null}
              unit="lb"
              onSave={patchField.bind(null, `${row.key}.leanTissueLb`)}
            />
            <RegionCell
              value={row.region?.fatTissueLb ?? null}
              unit="lb"
              onSave={patchField.bind(null, `${row.key}.fatTissueLb`)}
            />
            <RegionCell
              value={row.region?.regionFatPercent ?? null}
              unit="%"
              onSave={patchField.bind(null, `${row.key}.regionFatPercent`)}
            />
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function RegionCell({
  value,
  unit,
  onSave,
}: {
  value: number | null;
  unit: string;
  onSave: (next: number | null) => Promise<void>;
}) {
  return (
    <td className="py-1 text-right text-primary">
      <EditableNumber
        value={value}
        fractionDigits={1}
        unit={unit}
        fontClassName="font-mono text-[12px] tabular text-primary"
        unitClassName="ml-1 text-tertiary"
        onSave={onSave}
      />
    </td>
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
