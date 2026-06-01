import type { Metadata } from "next";
import Link from "next/link";

export const metadata: Metadata = {
  title: "Profile",
};
import { revalidatePath } from "next/cache";
import { signIn } from "@/auth";
import { apiFetch, apiJson } from "@/lib/api";
import { HeightForm } from "@/components/profile/HeightForm";
import { UnitsSection } from "@/components/profile/UnitsSection";

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

const GOOGLE_HEALTH_SCOPE =
  "https://www.googleapis.com/auth/googlehealth.health_metrics_and_measurements.readonly";

export const dynamic = "force-dynamic";

export default async function ProfilePage() {
  const [me, status] = await Promise.all([
    apiJson<WhoAmI>("/api/me"),
    apiJson<Status>("/api/me/google-health/status"),
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
            Your height is used to compute BMI from each weigh-in.
          </p>
          <div className="mt-4">
            <HeightForm heightCm={me.heightCm} saveAction={saveHeight} />
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
            {status.connectedAt && (
              <span className="font-mono text-[11px] uppercase tracking-[0.04em] text-tertiary">
                Connected
              </span>
            )}
          </div>
          <p className="mt-2 text-[13px] leading-[1.5] text-secondary">
            Sync weight and body fat from your scale or any device that
            writes to Google Health.
          </p>
          <div className="mt-4">
            {status.connected ? (
              <form action={disconnect}>
                <button
                  type="submit"
                  className="cursor-pointer rounded-md border-[0.5px] border-border-default bg-canvas px-4 py-2 text-[13px] font-medium text-primary"
                >
                  Disconnect Google Health
                </button>
              </form>
            ) : (
              <form action={connect}>
                <button
                  type="submit"
                  className="cursor-pointer rounded-md bg-accent px-4 py-2 text-[13px] font-medium text-inverse"
                >
                  Connect Google Health
                </button>
              </form>
            )}
          </div>
        </section>
      </div>
    </main>
  );
}
