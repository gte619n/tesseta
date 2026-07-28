import type { Metadata, Route } from "next";
import { redirect } from "next/navigation";
import { apiFetch, send } from "@/lib/api";
import type { ConsentMetadata, ConsentResult } from "@/lib/types/oauth";

export const metadata: Metadata = {
  title: "Authorize application",
};

export const dynamic = "force-dynamic";

type SearchParams = Record<string, string | string[] | undefined>;

// OAuth consent screen (ADR-0020). The third party sends the browser here with
// the standard authorize params; this page (behind the first-party session)
// fetches consent metadata from the backend, shows the user exactly what the app
// is asking for, and on Approve mints a code and redirects back to the app.
export default async function AuthorizePage({
  searchParams,
}: {
  searchParams: Promise<SearchParams>;
}) {
  const sp = await searchParams;
  const one = (k: string) => {
    const v = sp[k];
    return (Array.isArray(v) ? v[0] : v) ?? "";
  };

  const params = {
    responseType: one("response_type") || "code",
    clientId: one("client_id"),
    redirectUri: one("redirect_uri"),
    scope: one("scope"),
    state: one("state"),
    codeChallenge: one("code_challenge"),
    codeChallengeMethod: one("code_challenge_method") || "S256",
  };

  const query = new URLSearchParams({
    response_type: params.responseType,
    client_id: params.clientId,
    redirect_uri: params.redirectUri,
    scope: params.scope,
    code_challenge: params.codeChallenge,
    code_challenge_method: params.codeChallengeMethod,
    ...(params.state ? { state: params.state } : {}),
  });

  const res = await apiFetch(`/oauth/authorize?${query.toString()}`);
  if (!res.ok) {
    const body = (await res.json().catch(() => null)) as
      | { error?: string; error_description?: string }
      | null;
    return (
      <ErrorView
        message={
          body?.error_description ??
          body?.error ??
          `The authorization request was rejected (${res.status}).`
        }
      />
    );
  }
  const meta = (await res.json()) as ConsentMetadata;

  // Approve/deny both re-post the exact request params; the backend re-validates
  // them, then returns the redirect (with ?code=… or ?error=access_denied).
  async function decide(approve: boolean) {
    "use server";
    const result = await send<ConsentResult>(
      "/oauth/authorize/consent",
      "POST",
      {
        approve,
        clientId: params.clientId,
        redirectUri: params.redirectUri,
        scope: params.scope,
        state: params.state || null,
        codeChallenge: params.codeChallenge,
        codeChallengeMethod: params.codeChallengeMethod,
      },
    );
    // The third party's redirect_uri is an arbitrary external URL, so the typed-
    // routes RouteImpl constraint doesn't apply — cast to satisfy the checker.
    redirect(result.redirectUri as Route);
  }
  async function approve() {
    "use server";
    await decide(true);
  }
  async function deny() {
    "use server";
    await decide(false);
  }

  let redirectHost = meta.redirectUri;
  try {
    redirectHost = new URL(meta.redirectUri).host;
  } catch {
    // leave as-is if it isn't a parseable URL
  }

  return (
    <main className="min-h-screen bg-canvas p-8">
      <div className="mx-auto max-w-[480px] space-y-6 pt-8">
        <header className="text-center">
          <p className="m-0 caps-mono text-[10px] tracking-[0.08em] text-tertiary">
            Authorize application
          </p>
          <h1 className="mt-2 text-[22px] font-medium tracking-[-0.015em] text-primary">
            {meta.clientName}
          </h1>
          <p className="mt-2 text-[13px] leading-[1.5] text-secondary">
            <span className="font-medium text-primary">{meta.clientName}</span>{" "}
            wants to read the following from your Tesseta account.
            {meta.previouslyGranted && " You've connected this app before."}
          </p>
        </header>

        <section className="rounded-[14px] border-[0.5px] border-border-default bg-surface px-6 py-5">
          <h2 className="m-0 caps-mono text-[10px] tracking-[0.08em] text-tertiary">
            Read access
          </h2>
          <ul className="mt-3 space-y-3">
            {meta.scopes.map((s) => (
              <li key={s.scope} className="flex gap-3">
                <span aria-hidden className="mt-[2px] text-accent">
                  ✓
                </span>
                <div>
                  <div className="text-[13px] text-primary">{s.description}</div>
                  <div className="font-mono text-[11px] text-tertiary">
                    {s.scope}
                  </div>
                </div>
              </li>
            ))}
          </ul>
        </section>

        <p className="text-center font-mono text-[11px] text-tertiary">
          After you approve, you’ll return to{" "}
          <span className="text-secondary">{redirectHost}</span>. This is
          read-only — the app can never change your data. You can revoke access
          anytime in Connected Apps.
        </p>

        <div className="flex gap-3">
          <form action={deny} className="flex-1">
            <button
              type="submit"
              className="w-full cursor-pointer rounded-md border-[0.5px] border-border-default bg-canvas px-4 py-2.5 text-[13px] font-medium text-primary"
            >
              Deny
            </button>
          </form>
          <form action={approve} className="flex-1">
            <button
              type="submit"
              className="w-full cursor-pointer rounded-md bg-accent px-4 py-2.5 text-[13px] font-medium text-inverse"
            >
              Allow access
            </button>
          </form>
        </div>
      </div>
    </main>
  );
}

function ErrorView({ message }: { message: string }) {
  return (
    <main className="min-h-screen bg-canvas p-8">
      <div className="mx-auto max-w-[480px] space-y-4 pt-16 text-center">
        <h1 className="m-0 text-[22px] font-medium tracking-[-0.015em] text-primary">
          Can’t authorize this app
        </h1>
        <p className="text-[13px] leading-[1.5] text-secondary">{message}</p>
        <p className="font-mono text-[11px] text-tertiary">
          Nothing was shared. You can close this window.
        </p>
      </div>
    </main>
  );
}
