import type { Metadata } from "next";
import Link from "next/link";
import { revalidatePath } from "next/cache";
import { apiFetch, apiJson } from "@/lib/api";
import type { ConnectedApp } from "@/lib/types/oauth";

export const metadata: Metadata = {
  title: "Connected apps",
};

export const dynamic = "force-dynamic";

// First-party "Connected Apps" management (ADR-0020). Lists the third-party apps
// the user has authorized and lets them disconnect one — which deletes the grant
// and burns that app's refresh tokens on the backend, so its background access
// stops immediately.
export default async function ConnectedAppsPage() {
  const apps = await apiJson<ConnectedApp[]>("/api/me/connected-apps");

  async function disconnect(clientId: string) {
    "use server";
    const res = await apiFetch(
      `/api/me/connected-apps/${encodeURIComponent(clientId)}`,
      { method: "DELETE" },
    );
    if (!res.ok) throw new Error(`Disconnect failed: ${res.status}`);
    revalidatePath("/me/connected-apps");
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
            Connected apps
          </h1>
          <p className="mt-2 text-[13px] leading-[1.5] text-secondary">
            Apps you’ve given read-only access to your Tesseta data. Disconnect
            any of them to immediately revoke that access.
          </p>
        </header>

        {apps.length === 0 ? (
          <section className="rounded-[14px] border-[0.5px] border-border-default bg-surface px-6 py-8 text-center">
            <p className="m-0 text-[13px] text-secondary">
              You haven’t connected any apps yet.
            </p>
          </section>
        ) : (
          <div className="space-y-4">
            {apps.map((app) => {
              const disconnectApp = disconnect.bind(null, app.clientId);
              return (
                <section
                  key={app.clientId}
                  className="rounded-[14px] border-[0.5px] border-border-default bg-surface px-6 py-5"
                >
                  <div className="flex items-start justify-between gap-4">
                    <div>
                      <h2 className="m-0 text-[15px] font-medium text-primary">
                        {app.name}
                      </h2>
                      {app.grantedAt && (
                        <p className="mt-1 font-mono text-[11px] text-tertiary">
                          Connected {formatDate(app.grantedAt)}
                        </p>
                      )}
                    </div>
                    <form action={disconnectApp}>
                      <button
                        type="submit"
                        className="cursor-pointer rounded-md border-[0.5px] border-border-default bg-canvas px-4 py-2 text-[13px] font-medium text-primary"
                      >
                        Disconnect
                      </button>
                    </form>
                  </div>
                  <div className="mt-4">
                    <h3 className="m-0 caps-mono text-[10px] tracking-[0.08em] text-tertiary">
                      Can read
                    </h3>
                    <ul className="mt-2 space-y-1.5">
                      {app.scopes.map((s) => (
                        <li
                          key={s.scope}
                          className="flex gap-2 text-[13px] text-secondary"
                        >
                          <span aria-hidden className="text-accent">
                            ✓
                          </span>
                          {s.description}
                        </li>
                      ))}
                    </ul>
                  </div>
                </section>
              );
            })}
          </div>
        )}
      </div>
    </main>
  );
}

function formatDate(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleDateString(undefined, {
    year: "numeric",
    month: "short",
    day: "numeric",
  });
}
