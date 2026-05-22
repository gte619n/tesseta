import Link from "next/link";
import { revalidatePath } from "next/cache";
import { apiFetch, apiJson } from "@/lib/api";

type Marker =
  | "TOTAL_CHOLESTEROL"
  | "LDL"
  | "HDL"
  | "TRIGLYCERIDES"
  | "APO_B"
  | "HBA1C"
  | "FASTING_GLUCOSE"
  | "HS_CRP";

type Reference = {
  unit: string;
  orientation: "LOWER_IS_BETTER" | "HIGHER_IS_BETTER";
  goodThreshold: number;
  displayMin: number;
  displayMax: number;
};

type BloodReading = {
  readingId: string;
  marker: Marker;
  value: number;
  unit: string;
  sampleDate: string; // YYYY-MM-DD
  labSource: string | null;
  notes: string | null;
  reference: Reference;
};

const MARKER_LABELS: Record<Marker, string> = {
  TOTAL_CHOLESTEROL: "Total cholesterol",
  LDL: "LDL",
  HDL: "HDL",
  TRIGLYCERIDES: "Triglycerides",
  APO_B: "ApoB",
  HBA1C: "HbA1c",
  FASTING_GLUCOSE: "Fasting glucose",
  HS_CRP: "hs-CRP",
};

const MARKER_ORDER: Marker[] = [
  "LDL",
  "APO_B",
  "HDL",
  "TRIGLYCERIDES",
  "TOTAL_CHOLESTEROL",
  "HBA1C",
  "FASTING_GLUCOSE",
  "HS_CRP",
];

export const dynamic = "force-dynamic";

export default async function BloodPage() {
  const readings = await apiJson<BloodReading[]>("/api/me/blood");

  async function addReading(formData: FormData) {
    "use server";
    const marker = formData.get("marker") as Marker | null;
    const valueRaw = formData.get("value");
    const sampleDate = formData.get("sampleDate");
    const labSource = formData.get("labSource");
    const notes = formData.get("notes");

    if (!marker || !valueRaw || !sampleDate) {
      throw new Error("Missing required fields");
    }
    const value = Number(valueRaw);
    if (!Number.isFinite(value)) {
      throw new Error("Invalid value");
    }

    const res = await apiFetch("/api/me/blood", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        marker,
        value,
        sampleDate: String(sampleDate),
        labSource: labSource ? String(labSource) : null,
        notes: notes ? String(notes) : null,
      }),
    });
    if (!res.ok) {
      throw new Error(`Add failed: ${res.status}`);
    }
    revalidatePath("/me/blood");
  }

  const latestByMarker = pickLatestPerMarker(readings);

  return (
    <main className="min-h-screen bg-canvas p-8">
      <div className="mx-auto max-w-[920px] space-y-6">
        <Link
          href="/"
          className="inline-flex items-center gap-1.5 font-mono text-[11px] uppercase tracking-[0.04em] text-tertiary hover:text-secondary"
        >
          ← Dashboard
        </Link>

        <header>
          <h1 className="m-0 text-[22px] font-medium tracking-[-0.015em] text-primary">
            Blood markers
          </h1>
          <p className="mt-1 text-[13px] text-secondary">
            Latest readings across your lipid, glycemic, and inflammation panels.
            Add a new reading below as soon as new labs come back.
          </p>
        </header>

        <section className="rounded-[14px] border-[0.5px] border-border-default bg-surface px-5 py-4">
          <h2 className="m-0 text-[14px] font-medium text-primary">
            Add a reading
          </h2>
          <form
            action={addReading}
            className="mt-3 grid grid-cols-[1fr_140px_140px_140px_auto] items-end gap-3"
          >
            <label className="flex flex-col gap-1">
              <span className="font-mono text-[10px] uppercase tracking-[0.06em] text-tertiary">
                Marker
              </span>
              <select
                name="marker"
                required
                defaultValue="LDL"
                className="rounded-md border-[0.5px] border-border-default bg-canvas px-2.5 py-2 text-[13px] text-primary"
              >
                {MARKER_ORDER.map((m) => (
                  <option key={m} value={m}>
                    {MARKER_LABELS[m]}
                  </option>
                ))}
              </select>
            </label>
            <label className="flex flex-col gap-1">
              <span className="font-mono text-[10px] uppercase tracking-[0.06em] text-tertiary">
                Value
              </span>
              <input
                name="value"
                type="number"
                step="0.01"
                required
                className="rounded-md border-[0.5px] border-border-default bg-canvas px-2.5 py-2 text-[13px] text-primary"
              />
            </label>
            <label className="flex flex-col gap-1">
              <span className="font-mono text-[10px] uppercase tracking-[0.06em] text-tertiary">
                Date
              </span>
              <input
                name="sampleDate"
                type="date"
                required
                defaultValue={new Date().toISOString().slice(0, 10)}
                className="rounded-md border-[0.5px] border-border-default bg-canvas px-2.5 py-2 text-[13px] text-primary"
              />
            </label>
            <label className="flex flex-col gap-1">
              <span className="font-mono text-[10px] uppercase tracking-[0.06em] text-tertiary">
                Lab
              </span>
              <input
                name="labSource"
                type="text"
                placeholder="Quest, LabCorp…"
                className="rounded-md border-[0.5px] border-border-default bg-canvas px-2.5 py-2 text-[13px] text-primary"
              />
            </label>
            <button
              type="submit"
              className="cursor-pointer rounded-md bg-accent px-4 py-2 text-[13px] font-medium text-inverse"
            >
              Add
            </button>
          </form>
        </section>

        <section>
          <h2 className="mb-3 text-[14px] font-medium text-primary">Latest</h2>
          <div className="grid grid-cols-4 gap-3">
            {MARKER_ORDER.map((m) => (
              <MarkerCard
                key={m}
                label={MARKER_LABELS[m]}
                reading={latestByMarker[m]}
              />
            ))}
          </div>
        </section>

        <section className="rounded-[14px] border-[0.5px] border-border-default bg-surface">
          <div className="border-b-[0.5px] border-border-subtle px-5 py-3">
            <h2 className="m-0 text-[14px] font-medium text-primary">
              Recent readings
            </h2>
          </div>
          {readings.length === 0 ? (
            <div className="px-5 py-8 text-center text-[13px] text-secondary">
              No readings yet. Add one above.
            </div>
          ) : (
            <table className="w-full font-mono text-[12px] tabular">
              <thead>
                <tr className="text-left text-tertiary">
                  <th className="px-5 py-2 font-normal">Date</th>
                  <th className="px-5 py-2 font-normal">Marker</th>
                  <th className="px-5 py-2 font-normal text-right">Value</th>
                  <th className="px-5 py-2 font-normal">Lab</th>
                  <th className="px-5 py-2 font-normal">Notes</th>
                </tr>
              </thead>
              <tbody>
                {readings.slice(0, 100).map((r) => (
                  <tr
                    key={r.readingId}
                    className="border-t-[0.5px] border-border-subtle"
                  >
                    <td className="px-5 py-2 text-secondary">{r.sampleDate}</td>
                    <td className="px-5 py-2 text-primary">
                      {MARKER_LABELS[r.marker]}
                    </td>
                    <td className="px-5 py-2 text-right text-primary">
                      <span className={toneClass(toneFor(r))}>
                        {r.value.toFixed(2)}
                      </span>
                      <span className="ml-1 text-tertiary">{r.unit}</span>
                    </td>
                    <td className="px-5 py-2 text-tertiary">
                      {r.labSource ?? "—"}
                    </td>
                    <td className="px-5 py-2 text-tertiary">{r.notes ?? "—"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>
      </div>
    </main>
  );
}

function MarkerCard({
  label,
  reading,
}: {
  label: string;
  reading: BloodReading | undefined;
}) {
  if (!reading) {
    return (
      <div className="rounded-[14px] border-[0.5px] border-border-default bg-surface px-5 py-4">
        <div className="font-mono text-[10px] uppercase tracking-[0.06em] text-tertiary">
          {label}
        </div>
        <div className="mt-2 font-mono text-[26px] font-medium tabular text-primary">
          —
        </div>
        <div className="mt-1 font-mono text-[11px] text-tertiary">
          no reading
        </div>
      </div>
    );
  }
  const tone = toneFor(reading);
  const { displayMin, displayMax, goodThreshold } = reading.reference;
  const span = displayMax - displayMin;
  const goodPct =
    reading.reference.orientation === "LOWER_IS_BETTER"
      ? ((goodThreshold - displayMin) / span) * 100
      : 100 - ((goodThreshold - displayMin) / span) * 100;
  const goodLeft =
    reading.reference.orientation === "LOWER_IS_BETTER"
      ? 0
      : ((goodThreshold - displayMin) / span) * 100;
  const tickPct = clamp(((reading.value - displayMin) / span) * 100, 0, 100);
  return (
    <div className="rounded-[14px] border-[0.5px] border-border-default bg-surface px-5 py-4">
      <div className="font-mono text-[10px] uppercase tracking-[0.06em] text-tertiary">
        {label}
      </div>
      <div className={`mt-2 font-mono text-[26px] font-medium tabular ${toneClass(tone)}`}>
        {reading.value.toFixed(2)}
        <span className="ml-1 text-[14px] font-normal text-secondary">
          {reading.unit}
        </span>
      </div>
      <div className="relative mt-2 h-1 bg-canvas-sunken">
        <div
          className="absolute h-full bg-accent-bg"
          style={{ left: `${goodLeft}%`, width: `${goodPct}%` }}
        />
        <div
          className="absolute h-1 w-0.5 bg-primary"
          style={{ left: `${tickPct}%` }}
        />
      </div>
      <div className="mt-1 font-mono text-[10px] text-tertiary">
        {reading.sampleDate}
      </div>
    </div>
  );
}

function pickLatestPerMarker(
  readings: BloodReading[],
): Partial<Record<Marker, BloodReading>> {
  const latest: Partial<Record<Marker, BloodReading>> = {};
  for (const r of readings) {
    const existing = latest[r.marker];
    if (!existing || existing.sampleDate < r.sampleDate) {
      latest[r.marker] = r;
    }
  }
  return latest;
}

function toneFor(r: BloodReading): "good" | "warn" | "alert" {
  const { orientation, goodThreshold } = r.reference;
  const onGoodSide =
    orientation === "LOWER_IS_BETTER"
      ? r.value <= goodThreshold
      : r.value >= goodThreshold;
  if (onGoodSide) return "good";
  // Within 10% of threshold → warn; further out → alert.
  const dist = Math.abs(r.value - goodThreshold) / goodThreshold;
  return dist < 0.15 ? "warn" : "alert";
}

function toneClass(tone: "good" | "warn" | "alert"): string {
  return tone === "good"
    ? "text-good"
    : tone === "warn"
      ? "text-warn"
      : "text-alert";
}

function clamp(v: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, v));
}
