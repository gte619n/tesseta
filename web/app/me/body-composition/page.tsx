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

const METRIC_LABELS: Record<Metric, { name: string; unit: string }> = {
  WEIGHT_KG: { name: "Weight", unit: "kg" },
  BODY_FAT_PERCENT: { name: "Body fat", unit: "%" },
  LEAN_MASS_KG: { name: "Lean mass", unit: "kg" },
  BMI: { name: "BMI", unit: "" },
};

const METRIC_ORDER: Metric[] = [
  "WEIGHT_KG",
  "BODY_FAT_PERCENT",
  "LEAN_MASS_KG",
  "BMI",
];

const GOOGLE_HEALTH_SCOPE =
  "https://www.googleapis.com/auth/googlehealth.health_metrics_and_measurements.readonly";

export const dynamic = "force-dynamic";

export default async function BodyCompositionPage() {
  const status = await apiJson<Status>("/api/me/google-health/status");

  async function connect() {
    "use server";
    // Auth.js v5 signIn signature: (provider, options, authorizationParams).
    // Re-requesting openid/email/profile alongside the new health scope
    // makes Google merge them into a single consent screen rather than
    // showing two.
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
  }

  if (!status.connected) {
    return (
      <main className="flex min-h-screen items-center justify-center bg-canvas p-8">
        <div className="w-[480px] rounded-[14px] border-[0.5px] border-border-default bg-surface px-7 py-8 shadow-[0_24px_64px_rgba(0,0,0,0.08)]">
          <h1 className="m-0 text-[22px] font-medium tracking-[-0.015em] text-primary">
            Body composition
          </h1>
          <p className="mt-4 text-[14px] leading-[1.55] text-secondary">
            Connect your Google Health account to sync weight, body fat,
            lean mass, and BMI from your scale or other connected devices.
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
  const latestByMetric = pickLatestPerMetric(readings);

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
          {METRIC_ORDER.map((metric) => {
            const reading = latestByMetric[metric];
            const label = METRIC_LABELS[metric];
            return (
              <div
                key={metric}
                className="rounded-[14px] border-[0.5px] border-border-default bg-surface px-5 py-4"
              >
                <div className="font-mono text-[10px] uppercase tracking-[0.06em] text-tertiary">
                  {label.name}
                </div>
                <div className="mt-2 font-mono text-[26px] font-medium tabular text-primary">
                  {reading ? formatValue(reading.value, metric) : "—"}
                  {label.unit && (
                    <span className="ml-1 text-[14px] text-secondary">
                      {label.unit}
                    </span>
                  )}
                </div>
                {reading && (
                  <div className="mt-1 font-mono text-[11px] text-tertiary">
                    {formatRelative(reading.sampleTime)}
                  </div>
                )}
              </div>
            );
          })}
        </section>

        <section className="rounded-[14px] border-[0.5px] border-border-default bg-surface">
          <div className="border-b-[0.5px] border-border-subtle px-5 py-3">
            <h2 className="m-0 text-[14px] font-medium text-primary">
              Recent readings
            </h2>
          </div>
          {readings.length === 0 ? (
            <div className="px-5 py-8 text-center text-[13px] text-secondary">
              No measurements yet. Once your scale logs one, it&apos;ll
              appear here within ~30 seconds.
            </div>
          ) : (
            <table className="w-full font-mono text-[12px] tabular">
              <thead>
                <tr className="text-left text-tertiary">
                  <th className="px-5 py-2 font-normal">Time</th>
                  <th className="px-5 py-2 font-normal">Metric</th>
                  <th className="px-5 py-2 font-normal text-right">Value</th>
                  <th className="px-5 py-2 font-normal">Source</th>
                </tr>
              </thead>
              <tbody>
                {readings.slice(0, 50).map((r) => {
                  const label = METRIC_LABELS[r.metric];
                  return (
                    <tr
                      key={r.recordId}
                      className="border-t-[0.5px] border-border-subtle"
                    >
                      <td className="px-5 py-2 text-secondary">
                        {formatDateTime(r.sampleTime)}
                      </td>
                      <td className="px-5 py-2 text-primary">{label.name}</td>
                      <td className="px-5 py-2 text-right text-primary">
                        {formatValue(r.value, r.metric)}
                        {label.unit && (
                          <span className="ml-1 text-tertiary">
                            {label.unit}
                          </span>
                        )}
                      </td>
                      <td className="px-5 py-2 text-tertiary">
                        {r.sourcePlatform ?? "—"}
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

function pickLatestPerMetric(
  readings: Reading[],
): Partial<Record<Metric, Reading>> {
  const latest: Partial<Record<Metric, Reading>> = {};
  for (const r of readings) {
    const existing = latest[r.metric];
    if (!existing || existing.sampleTime < r.sampleTime) {
      latest[r.metric] = r;
    }
  }
  return latest;
}

function formatValue(value: number, metric: Metric): string {
  // Whole numbers get a trailing .0 so the column stays visually aligned.
  return metric === "BMI" || metric === "BODY_FAT_PERCENT"
    ? value.toFixed(1)
    : value.toFixed(1);
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
