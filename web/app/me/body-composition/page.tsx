import { revalidatePath } from "next/cache";
import { signIn } from "@/auth";
import { apiFetch, apiJson } from "@/lib/api";

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

// Backend stores everything in canonical units (kg for masses). The page
// converts to imperial at render time. Keep storage canonical so we can
// add a per-user unit preference later without migrating data.
const KG_TO_LB = 2.20462;
const SESSION_WINDOW_MS = 5 * 60 * 1000;

const METRIC_LABELS: Record<Metric, { name: string; unit: string }> = {
  WEIGHT_KG: { name: "Weight", unit: "lb" },
  BODY_FAT_PERCENT: { name: "Body fat", unit: "%" },
  LEAN_MASS_KG: { name: "Lean mass", unit: "lb" },
  BMI: { name: "BMI", unit: "" },
};

const GOOGLE_HEALTH_SCOPE =
  "https://www.googleapis.com/auth/googlehealth.health_metrics_and_measurements.readonly";

export const dynamic = "force-dynamic";

export default async function BodyCompositionPage() {
  const status = await apiJson<Status>("/api/me/google-health/status");

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

  async function disconnect() {
    "use server";
    const res = await apiFetch("/api/me/google-health/connect", {
      method: "DELETE",
    });
    if (!res.ok) {
      throw new Error(`Disconnect failed: ${res.status}`);
    }
    // Force the server component to re-fetch so the page flips back to
    // the Connect CTA. Without this, Next.js holds the cached connected
    // state and the user sees the same screen.
    revalidatePath("/me/body-composition");
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

  const readings = await apiJson<Reading[]>("/api/me/body-composition");
  const sessions = groupIntoSessions(readings);

  const latestSession = sessions[0];
  const latestWeight = sessions.find((s) => s.weight)?.weight;
  const latestBodyFat = sessions.find((s) => s.bodyFat)?.bodyFat;
  const leanMassLb = computeLeanMass(sessions);

  return (
    <main className="min-h-screen bg-canvas p-8">
      <div className="mx-auto max-w-[920px] space-y-6">
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
            value={latestWeight ? (latestWeight.value * KG_TO_LB).toFixed(1) : "—"}
            unit="lb"
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
            value={leanMassLb !== null ? leanMassLb.toFixed(1) : "—"}
            unit="lb"
            // Lean mass reflects the latest paired weigh-in.
            sampleTime={latestSession?.sampleTime ?? null}
            footer={leanMassLb === null ? "needs paired weigh-in" : null}
          />
          <MetricCard
            label="BMI"
            value="—"
            unit=""
            sampleTime={null}
            footer="needs height — coming later"
          />
        </section>

        <section className="rounded-[14px] border-[0.5px] border-border-default bg-surface">
          <div className="border-b-[0.5px] border-border-subtle px-5 py-3">
            <h2 className="m-0 text-[14px] font-medium text-primary">
              Recent readings
            </h2>
          </div>
          {sessions.length === 0 ? (
            <div className="px-5 py-8 text-center text-[13px] text-secondary">
              No measurements yet. Once your scale logs one, it&apos;ll
              appear here within ~30 seconds.
            </div>
          ) : (
            <table className="w-full font-mono text-[12px] tabular">
              <thead>
                <tr className="text-left text-tertiary">
                  <th className="px-5 py-2 font-normal">Time</th>
                  <th className="px-5 py-2 font-normal text-right">Weight</th>
                  <th className="px-5 py-2 font-normal text-right">Body fat</th>
                  <th className="px-5 py-2 font-normal text-right">Lean mass</th>
                  <th className="px-5 py-2 font-normal">Source</th>
                </tr>
              </thead>
              <tbody>
                {sessions.slice(0, 50).map((s) => {
                  const sessionLean = sessionLeanMassLb(s);
                  return (
                    <tr
                      key={s.sampleTime}
                      className="border-t-[0.5px] border-border-subtle"
                    >
                      <td className="px-5 py-2 text-secondary">
                        {formatDateTime(s.sampleTime)}
                      </td>
                      <td className="px-5 py-2 text-right text-primary">
                        {s.weight ? (
                          <>
                            {(s.weight.value * KG_TO_LB).toFixed(1)}
                            <span className="ml-1 text-tertiary">lb</span>
                          </>
                        ) : (
                          <span className="text-tertiary">—</span>
                        )}
                      </td>
                      <td className="px-5 py-2 text-right text-primary">
                        {s.bodyFat ? (
                          <>
                            {s.bodyFat.value.toFixed(1)}
                            <span className="ml-1 text-tertiary">%</span>
                          </>
                        ) : (
                          <span className="text-tertiary">—</span>
                        )}
                      </td>
                      <td className="px-5 py-2 text-right text-primary">
                        {sessionLean !== null ? (
                          <>
                            {sessionLean.toFixed(1)}
                            <span className="ml-1 text-tertiary">lb</span>
                          </>
                        ) : (
                          <span className="text-tertiary">—</span>
                        )}
                      </td>
                      <td className="px-5 py-2 text-tertiary">
                        {s.sourcePlatform ?? "—"}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          )}
        </section>

        <form action={disconnect}>
          <button
            type="submit"
            className="cursor-pointer rounded-md border-[0.5px] border-border-default bg-canvas px-4 py-2 text-[13px] font-medium text-primary"
          >
            Disconnect Google Health
          </button>
        </form>
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
  value: string;
  unit: string;
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

// Cluster readings into per-weigh-in sessions. Smart scales emit weight
// and body-fat seconds apart, so any readings within SESSION_WINDOW_MS of
// each other are treated as the same session. Returns sessions sorted by
// time, most-recent first.
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
  // Keep the earliest sample time within a session (sessions are sorted
  // newest-first; later iterations within the same session push earlier).
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
