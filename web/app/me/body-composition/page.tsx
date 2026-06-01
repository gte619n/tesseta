import type { Metadata } from "next";
import Link from "next/link";

export const metadata: Metadata = {
  title: "Body Composition",
};
import type { Route } from "next";
import { signIn } from "@/auth";
import { apiJson } from "@/lib/api";
import { DexaUploadButton } from "@/components/dexa/DexaUploadButton";
import {
  BodyWeightCell,
  BodyWeightMetricValue,
} from "@/components/dashboard/BodyWeight";

type WhoAmI = {
  userId: string;
  email: string | null;
  displayName: string | null;
  heightCm: number | null;
};

type Status = {
  connected: boolean;
  connectedAt: string | null;
};

type Metric = "WEIGHT_KG" | "BODY_FAT_PERCENT" | "LEAN_MASS_KG" | "BMI";

type Reading = {
  recordId: string;
  metric: Metric;
  value: number;
  sampleTime: string;
  sourcePlatform: string | null;
  recordingMethod: string | null;
};

type Session = {
  sampleTime: string;
  sourcePlatform: string | null;
  // Only metrics Google Health actually returns are keyed here.
  weight?: Reading;
  bodyFat?: Reading;
};

type DexaScan = {
  scanId: string;
  measuredOn: string | null;
  sourceFacility: string | null;
  totalMassLb: number | null;
  leanTissueLb: number | null;
  fatTissueLb: number | null;
  totalBodyFatPercent: number | null;
};

type Row = {
  key: string;
  sortIso: string;
  // Display label for the Time column.
  displayTime: string;
  weightLb: number | null;
  bodyFatPercent: number | null;
  leanMassLb: number | null;
  source: string;
  // DEXA rows link to detail; Google Health rows don't.
  detailHref: string | null;
  isDexa: boolean;
};

// Backend stores masses in kg for Google Health readings, in lbs for DEXA.
// The page converts everything to imperial at render time.
const KG_TO_LB = 2.20462;
const SESSION_WINDOW_MS = 5 * 60 * 1000;

const GOOGLE_HEALTH_SCOPE =
  "https://www.googleapis.com/auth/googlehealth.health_metrics_and_measurements.readonly";

export const dynamic = "force-dynamic";

export default async function BodyCompositionPage() {
  const [status, me] = await Promise.all([
    apiJson<Status>("/api/me/google-health/status"),
    apiJson<WhoAmI>("/api/me"),
  ]);

  async function connect() {
    "use server";
    await signIn(
      "google",
      { redirectTo: "/me/body-composition" },
      {
        scope: `openid email profile ${GOOGLE_HEALTH_SCOPE}`,
        access_type: "offline",
        prompt: "consent",
      },
    );
  }

  if (!status.connected) {
    return (
      <main className="flex min-h-screen items-center justify-center bg-canvas p-8">
        <div className="w-[480px] rounded-[14px] border-[0.5px] border-border-default bg-surface px-7 py-8 shadow-[0_24px_64px_rgba(0,0,0,0.08)]">
          <h1 className="m-0 text-[22px] font-medium tracking-[-0.015em] text-primary">
            Body composition
          </h1>
          <p className="mt-4 text-[14px] leading-[1.55] text-secondary">
            Connect your Google Health account to sync weight and body fat
            from your scale or other connected devices.
          </p>
          <form action={connect} className="mt-6">
            <button
              type="submit"
              className="cursor-pointer rounded-md bg-accent px-4 py-2 text-[13px] font-medium text-inverse"
            >
              Connect Google Health
            </button>
          </form>
        </div>
      </main>
    );
  }

  const [readings, dexaScans] = await Promise.all([
    apiJson<Reading[]>("/api/me/body-composition"),
    apiJson<DexaScan[]>("/api/me/dexa/scans"),
  ]);
  const sessions = groupIntoSessions(readings);
  const rows = mergeRows(sessions, dexaScans);

  const latestSession = sessions[0];
  const latestWeight = sessions.find((s) => s.weight)?.weight;
  const latestBodyFat = sessions.find((s) => s.bodyFat)?.bodyFat;
  const leanMassLb = computeLeanMass(sessions);
  const bmi =
    latestWeight && me.heightCm
      ? latestWeight.value / Math.pow(me.heightCm / 100, 2)
      : null;

  return (
    <main className="min-h-screen bg-canvas p-8">
      <div className="mx-auto max-w-[920px] space-y-6">
        <Link
          href="/"
          className="inline-flex items-center gap-1.5 font-mono text-[11px] uppercase tracking-[0.04em] text-tertiary hover:text-secondary"
        >
          ← Dashboard
        </Link>
        <header className="flex items-baseline justify-between">
          <h1 className="m-0 text-[22px] font-medium tracking-[-0.015em] text-primary">
            Body composition
          </h1>
          {status.connectedAt && (
            <span className="font-mono text-[11px] uppercase tracking-[0.04em] text-tertiary">
              Connected {formatRelative(status.connectedAt)}
            </span>
          )}
        </header>

        <section className="grid grid-cols-4 gap-3">
          <MetricCard
            label="Weight"
            value={
              <BodyWeightMetricValue
                lb={latestWeight ? latestWeight.value * KG_TO_LB : null}
              />
            }
            sampleTime={latestWeight?.sampleTime ?? null}
          />
          <MetricCard
            label="Body fat"
            value={latestBodyFat ? latestBodyFat.value.toFixed(1) : "—"}
            unit="%"
            sampleTime={latestBodyFat?.sampleTime ?? null}
          />
          <MetricCard
            label="Lean mass"
            value={<BodyWeightMetricValue lb={leanMassLb} />}
            sampleTime={latestSession?.sampleTime ?? null}
            footer={leanMassLb === null ? "needs paired weigh-in" : null}
          />
          <MetricCard
            label="BMI"
            value={bmi !== null ? bmi.toFixed(1) : "—"}
            unit=""
            sampleTime={bmi !== null ? latestWeight?.sampleTime ?? null : null}
            footer={
              bmi === null
                ? me.heightCm
                  ? "needs a weight reading"
                  : "set your height to compute BMI"
                : null
            }
          />
        </section>

        <section className="rounded-[14px] border-[0.5px] border-border-default bg-surface">
          <div className="flex items-center justify-between border-b-[0.5px] border-border-subtle px-5 py-3">
            <h2 className="m-0 text-[14px] font-medium text-primary">
              Recent readings
            </h2>
            <DexaUploadButton />
          </div>
          {rows.length === 0 ? (
            <div className="px-5 py-8 text-center text-[13px] text-secondary">
              No measurements yet. Once your scale logs one — or you upload
              a DEXA report — it&apos;ll appear here.
            </div>
          ) : (
            <div className="max-h-[380px] overflow-y-auto">
              <table className="w-full font-mono text-[12px] tabular">
                <thead className="sticky top-0 bg-surface">
                  <tr className="text-left text-tertiary">
                    <th className="px-5 py-2 font-normal">Time</th>
                    <th className="px-5 py-2 font-normal text-right">Weight</th>
                    <th className="px-5 py-2 font-normal text-right">Body fat</th>
                    <th className="px-5 py-2 font-normal text-right">Lean mass</th>
                    <th className="px-5 py-2 font-normal">Source</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.slice(0, 100).map((r) => (
                    <tr
                      key={r.key}
                      className={`border-t-[0.5px] border-border-subtle ${
                        r.isDexa ? "bg-canvas/40" : ""
                      }`}
                    >
                      <td className="px-5 py-2 text-secondary">
                        {r.detailHref ? (
                          <Link
                            href={r.detailHref as Route}
                            className="hover:text-primary"
                          >
                            {r.displayTime}
                          </Link>
                        ) : (
                          r.displayTime
                        )}
                      </td>
                      <td className="px-5 py-2 text-right text-primary">
                        <BodyWeightCell lb={r.weightLb} />
                      </td>
                      <td className="px-5 py-2 text-right text-primary">
                        {r.bodyFatPercent !== null ? (
                          <>
                            {r.bodyFatPercent.toFixed(1)}
                            <span className="ml-1 text-tertiary">%</span>
                          </>
                        ) : (
                          <span className="text-tertiary">—</span>
                        )}
                      </td>
                      <td className="px-5 py-2 text-right text-primary">
                        <BodyWeightCell lb={r.leanMassLb} />
                      </td>
                      <td className="px-5 py-2">
                        {r.isDexa ? (
                          <span className="rounded-[4px] bg-accent px-1.5 py-px text-[10px] font-medium uppercase tracking-[0.06em] text-inverse">
                            DEXA
                          </span>
                        ) : (
                          <span className="text-tertiary">{r.source}</span>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>

        <div className="flex gap-6 caps-mono text-[10px] tracking-[0.06em] text-tertiary">
          <span>
            <span className="text-secondary">{dexaScans.length}</span> DEXA
            scan{dexaScans.length === 1 ? "" : "s"}
          </span>
          <span>
            <span className="text-secondary">{sessions.length}</span> Fitbit
            session{sessions.length === 1 ? "" : "s"}
          </span>
        </div>
      </div>
    </main>
  );
}

function MetricCard({
  label,
  value,
  unit,
  sampleTime,
  footer,
}: {
  label: string;
  // A preformatted string (with unit handled separately) or a fully
  // rendered node (used for unit-aware weight values).
  value: React.ReactNode;
  unit?: string;
  sampleTime: string | null;
  footer?: string | null;
}) {
  return (
    <div className="rounded-[14px] border-[0.5px] border-border-default bg-surface px-5 py-4">
      <div className="font-mono text-[10px] uppercase tracking-[0.06em] text-tertiary">
        {label}
      </div>
      <div className="mt-2 font-mono text-[26px] font-medium tabular text-primary">
        {value}
        {unit && (
          <span className="ml-1 text-[14px] text-secondary">{unit}</span>
        )}
      </div>
      {sampleTime ? (
        <div className="mt-1 font-mono text-[11px] text-tertiary">
          {formatRelative(sampleTime)}
        </div>
      ) : footer ? (
        <div className="mt-1 font-mono text-[11px] text-tertiary">{footer}</div>
      ) : null}
    </div>
  );
}

function groupIntoSessions(readings: Reading[]): Session[] {
  if (readings.length === 0) return [];
  const sorted = [...readings].sort((a, b) =>
    b.sampleTime.localeCompare(a.sampleTime),
  );
  const sessions: Session[] = [];
  for (const r of sorted) {
    const rMs = new Date(r.sampleTime).getTime();
    const last = sessions[sessions.length - 1];
    const lastMs = last ? new Date(last.sampleTime).getTime() : null;
    if (last && lastMs !== null && Math.abs(lastMs - rMs) <= SESSION_WINDOW_MS) {
      mergeIntoSession(last, r);
    } else {
      const session: Session = {
        sampleTime: r.sampleTime,
        sourcePlatform: r.sourcePlatform,
      };
      mergeIntoSession(session, r);
      sessions.push(session);
    }
  }
  return sessions;
}

function mergeIntoSession(session: Session, r: Reading) {
  if (r.metric === "WEIGHT_KG" && !session.weight) session.weight = r;
  if (r.metric === "BODY_FAT_PERCENT" && !session.bodyFat) session.bodyFat = r;
  if (r.sampleTime < session.sampleTime) {
    session.sampleTime = r.sampleTime;
  }
}

function sessionLeanMassLb(session: Session): number | null {
  if (!session.weight || !session.bodyFat) return null;
  const weightLb = session.weight.value * KG_TO_LB;
  return weightLb * (1 - session.bodyFat.value / 100);
}

function computeLeanMass(sessions: Session[]): number | null {
  for (const s of sessions) {
    const v = sessionLeanMassLb(s);
    if (v !== null) return v;
  }
  return null;
}

// Merge Google Health sessions and DEXA scans into a single sorted list.
// DEXA scans use the date-only measuredOn; we sort them as end-of-day so
// they appear after any Google Health session from the same day.
function mergeRows(sessions: Session[], scans: DexaScan[]): Row[] {
  const sessionRows: Row[] = sessions.map((s) => ({
    key: `session:${s.sampleTime}`,
    sortIso: s.sampleTime,
    displayTime: formatDateTime(s.sampleTime),
    weightLb: s.weight ? s.weight.value * KG_TO_LB : null,
    bodyFatPercent: s.bodyFat ? s.bodyFat.value : null,
    leanMassLb: sessionLeanMassLb(s),
    source: s.sourcePlatform ?? "—",
    detailHref: null,
    isDexa: false,
  }));
  const dexaRows: Row[] = scans.map((d) => ({
    key: `dexa:${d.scanId}`,
    sortIso: d.measuredOn ? `${d.measuredOn}T23:59:59Z` : "",
    displayTime: d.measuredOn ?? "—",
    weightLb: d.totalMassLb,
    bodyFatPercent: d.totalBodyFatPercent,
    leanMassLb: d.leanTissueLb,
    source: d.sourceFacility ?? "DEXA",
    detailHref: `/me/body-composition/dexa/${d.scanId}`,
    isDexa: true,
  }));
  return [...sessionRows, ...dexaRows].sort((a, b) =>
    b.sortIso.localeCompare(a.sortIso),
  );
}

function formatRelative(iso: string): string {
  const then = new Date(iso).getTime();
  const diffSec = Math.round((Date.now() - then) / 1000);
  if (diffSec < 60) return `${diffSec}s ago`;
  const min = Math.round(diffSec / 60);
  if (min < 60) return `${min}m ago`;
  const hr = Math.round(min / 60);
  if (hr < 48) return `${hr}h ago`;
  const days = Math.round(hr / 24);
  return `${days}d ago`;
}

function formatDateTime(iso: string): string {
  const d = new Date(iso);
  return d.toISOString().slice(0, 16).replace("T", " ");
}
