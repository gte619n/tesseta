import type { Metadata } from "next";
import Link from "next/link";

export const metadata: Metadata = {
  title: "Profile",
};
import { revalidatePath } from "next/cache";
import { signIn } from "@/auth";
import { apiFetch, apiJson } from "@/lib/api";
import { HeightForm } from "@/components/profile/HeightForm";
import { BodyDetailsForm } from "@/components/profile/BodyDetailsForm";
import { UnitsSection } from "@/components/profile/UnitsSection";
import type { WhoAmI } from "@/lib/types/profile";

type Status = {
  connected: boolean;
  connectedAt: string | null;
  // True when the connection exists but its refresh token has died (revoked or
  // expired) — the user must reconnect to resume syncing.
  needsReconnect: boolean;
};

// Google Health read scopes. Each data-type family needs its own scope:
// resting HR / HRV / body composition fall under health_metrics_and_
// measurements; steps under activity_and_fitness; sleep under sleep.
// Without all three, the daily-metric sync 403s on the ungranted families.
const GOOGLE_HEALTH_SCOPE = [
  "https://www.googleapis.com/auth/googlehealth.health_metrics_and_measurements.readonly",
  "https://www.googleapis.com/auth/googlehealth.activity_and_fitness.readonly",
  "https://www.googleapis.com/auth/googlehealth.sleep.readonly",
].join(" ");

export const dynamic = "force-dynamic";

export default async function ProfilePage({
  searchParams,
}: {
  // The Withings callback redirects here with ?withings=connected|error.
  searchParams: Promise<{ withings?: string }>;
}) {
  const [{ withings: withingsResult }, me, status, withingsStatus] =
    await Promise.all([
      searchParams,
      apiJson<WhoAmI>("/api/me"),
      apiJson<Status>("/api/me/google-health/status"),
      apiJson<Status>("/api/me/withings/status"),
    ]);

  async function connect() {
    "use server";
    await signIn(
      "google",
      { redirectTo: "/me/profile" },
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
    if (!res.ok) throw new Error(`Disconnect failed: ${res.status}`);
    revalidatePath("/me/profile");
  }

  async function disconnectWithings() {
    "use server";
    const res = await apiFetch("/api/me/withings/connect", { method: "DELETE" });
    if (!res.ok) throw new Error(`Disconnect failed: ${res.status}`);
    revalidatePath("/me/profile");
  }

  async function saveHeight(heightCm: number | null) {
    "use server";
    const res = await apiFetch("/api/me", {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ heightCm }),
    });
    if (!res.ok) {
      const text = await res.text().catch(() => "");
      throw new Error(text || `Save failed: ${res.status}`);
    }
    revalidatePath("/me/profile");
  }

  async function saveBodyDetails(
    biologicalSex: string | null,
    dateOfBirth: string | null,
  ) {
    "use server";
    const res = await apiFetch("/api/me", {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ biologicalSex, dateOfBirth }),
    });
    if (!res.ok) {
      const text = await res.text().catch(() => "");
      throw new Error(text || `Save failed: ${res.status}`);
    }
    revalidatePath("/me/profile");
  }

  return (
    <main className="min-h-screen bg-canvas p-8">
      <div className="mx-auto max-w-[720px] space-y-6">
        <Link
          href="/"
          className="inline-flex items-center gap-1.5 font-mono text-[11px] uppercase tracking-[0.04em] text-tertiary hover:text-secondary"
        >
          ← Dashboard
        </Link>

        <header>
          <h1 className="m-0 text-[22px] font-medium tracking-[-0.015em] text-primary">
            Profile
          </h1>
        </header>

        <section className="rounded-[14px] border-[0.5px] border-border-default bg-surface px-6 py-5">
          <h2 className="m-0 caps-mono text-[10px] tracking-[0.08em] text-tertiary">
            Account
          </h2>
          <div className="mt-3 grid grid-cols-[120px_1fr] gap-y-2 font-mono text-[13px]">
            <span className="text-tertiary">Name</span>
            <span className="text-primary">{me.displayName ?? "—"}</span>
            <span className="text-tertiary">Email</span>
            <span className="text-primary">{me.email ?? "—"}</span>
          </div>
        </section>

        <section className="rounded-[14px] border-[0.5px] border-border-default bg-surface px-6 py-5">
          <h2 className="m-0 caps-mono text-[10px] tracking-[0.08em] text-tertiary">
            Body
          </h2>
          <p className="mt-2 text-[13px] leading-[1.5] text-secondary">
            Your height is used to compute BMI from each weigh-in. Biological sex
            and date of birth power your daily calorie estimate.
          </p>
          <div className="mt-4">
            <HeightForm heightCm={me.heightCm} saveAction={saveHeight} />
          </div>
          <div className="mt-4">
            <BodyDetailsForm
              biologicalSex={me.biologicalSex}
              dateOfBirth={me.dateOfBirth}
              saveAction={saveBodyDetails}
            />
          </div>
        </section>

        <section className="rounded-[14px] border-[0.5px] border-border-default bg-surface px-6 py-5">
          <h2 className="m-0 caps-mono text-[10px] tracking-[0.08em] text-tertiary">
            Units
          </h2>
          <p className="mt-2 text-[13px] leading-[1.5] text-secondary">
            Choose how weight, height, and temperature are displayed. Saved
            in this browser.
          </p>
          <div className="mt-4">
            <UnitsSection />
          </div>
        </section>

        <section className="rounded-[14px] border-[0.5px] border-border-default bg-surface px-6 py-5">
          <div className="flex items-baseline justify-between">
            <h2 className="m-0 caps-mono text-[10px] tracking-[0.08em] text-tertiary">
              Google Health
            </h2>
            {status.connected &&
              (status.needsReconnect ? (
                <span className="font-mono text-[11px] uppercase tracking-[0.04em] text-alert">
                  Reconnect needed
                </span>
              ) : (
                status.connectedAt && (
                  <span className="font-mono text-[11px] uppercase tracking-[0.04em] text-tertiary">
                    Connected
                  </span>
                )
              ))}
          </div>
          <p className="mt-2 text-[13px] leading-[1.5] text-secondary">
            Sync weight and body fat from your scale or any device that
            writes to Google Health.
          </p>
          {status.connected && status.needsReconnect && (
            <p className="mt-2 text-[13px] leading-[1.5] text-alert">
              Your connection expired and data has stopped syncing. Reconnect to
              resume.
            </p>
          )}
          <div className="mt-4">
            {!status.connected ? (
              <form action={connect}>
                <button
                  type="submit"
                  className="cursor-pointer rounded-md bg-accent px-4 py-2 text-[13px] font-medium text-inverse"
                >
                  Connect Google Health
                </button>
              </form>
            ) : status.needsReconnect ? (
              <form action={connect}>
                <button
                  type="submit"
                  className="cursor-pointer rounded-md bg-accent px-4 py-2 text-[13px] font-medium text-inverse"
                >
                  Reconnect Google Health
                </button>
              </form>
            ) : (
              <form action={disconnect}>
                <button
                  type="submit"
                  className="cursor-pointer rounded-md border-[0.5px] border-border-default bg-canvas px-4 py-2 text-[13px] font-medium text-primary"
                >
                  Disconnect Google Health
                </button>
              </form>
            )}
          </div>
        </section>

        <section className="rounded-[14px] border-[0.5px] border-border-default bg-surface px-6 py-5">
          <div className="flex items-baseline justify-between">
            <h2 className="m-0 caps-mono text-[10px] tracking-[0.08em] text-tertiary">
              Withings
            </h2>
            {withingsStatus.connected &&
              (withingsStatus.needsReconnect ? (
                <span className="font-mono text-[11px] uppercase tracking-[0.04em] text-alert">
                  Reconnect needed
                </span>
              ) : (
                withingsStatus.connectedAt && (
                  <span className="font-mono text-[11px] uppercase tracking-[0.04em] text-tertiary">
                    Connected
                  </span>
                )
              ))}
          </div>
          <p className="mt-2 text-[13px] leading-[1.5] text-secondary">
            Sync sleep from your Sleep Analyzer pad, plus weight and body fat
            from your Withings scale.
          </p>
          {withingsResult === "connected" && (
            <p className="mt-2 text-[13px] leading-[1.5] text-secondary">
              Withings connected — your history is importing now.
            </p>
          )}
          {withingsResult === "error" && (
            <p className="mt-2 text-[13px] leading-[1.5] text-alert">
              Couldn’t complete the Withings connection. Please try again.
            </p>
          )}
          {withingsStatus.connected && withingsStatus.needsReconnect && (
            <p className="mt-2 text-[13px] leading-[1.5] text-alert">
              Your connection expired and data has stopped syncing. Reconnect to
              resume.
            </p>
          )}
          <div className="mt-4">
            {!withingsStatus.connected ? (
              <a
                href="/api/withings/start"
                className="inline-flex cursor-pointer rounded-md bg-accent px-4 py-2 text-[13px] font-medium text-inverse"
              >
                Connect Withings
              </a>
            ) : withingsStatus.needsReconnect ? (
              <a
                href="/api/withings/start"
                className="inline-flex cursor-pointer rounded-md bg-accent px-4 py-2 text-[13px] font-medium text-inverse"
              >
                Reconnect Withings
              </a>
            ) : (
              <form action={disconnectWithings}>
                <button
                  type="submit"
                  className="cursor-pointer rounded-md border-[0.5px] border-border-default bg-canvas px-4 py-2 text-[13px] font-medium text-primary"
                >
                  Disconnect Withings
                </button>
              </form>
            )}
          </div>
        </section>

        <section className="rounded-[14px] border-[0.5px] border-border-default bg-surface px-6 py-5">
          <h2 className="m-0 caps-mono text-[10px] tracking-[0.08em] text-tertiary">
            Connected apps
          </h2>
          <p className="mt-2 text-[13px] leading-[1.5] text-secondary">
            Review and revoke third-party apps you’ve given read-only access to
            your data.
          </p>
          <div className="mt-4">
            <Link
              href="/me/connected-apps"
              className="inline-flex cursor-pointer rounded-md border-[0.5px] border-border-default bg-canvas px-4 py-2 text-[13px] font-medium text-primary"
            >
              Manage connected apps
            </Link>
          </div>
        </section>
      </div>
    </main>
  );
}
