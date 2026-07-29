"use client";

import { useState, useTransition } from "react";
import { useToast } from "@/components/ui/Toast";
import {
  OAUTH_SCOPE_CATALOG,
  type OAuthClientSummary,
  type OAuthClientRegistration,
} from "@/lib/types/oauth";
import type { RegisterOAuthClientRequest } from "@/lib/oauth-admin-api";

interface Props {
  clients: OAuthClientSummary[];
  register: (
    data: RegisterOAuthClientRequest,
  ) => Promise<OAuthClientRegistration>;
}

const inputClass =
  "w-full rounded-md border border-border-default bg-canvas px-3 py-2 text-sm text-primary placeholder:text-tertiary focus:border-accent focus:outline-none";

export function AdminOAuthClient({ clients, register }: Props) {
  const toast = useToast();
  const [pending, startTransition] = useTransition();

  const [name, setName] = useState("");
  const [logoUrl, setLogoUrl] = useState("");
  const [redirectUris, setRedirectUris] = useState("");
  const [confidential, setConfidential] = useState(true);
  const [scopes, setScopes] = useState<string[]>(["profile:read"]);

  // Holds the just-registered client so we can show its secret ONCE. The
  // secret is never retrievable again after this panel is dismissed.
  const [justCreated, setJustCreated] =
    useState<OAuthClientRegistration | null>(null);

  function toggleScope(scope: string) {
    setScopes((prev) =>
      prev.includes(scope)
        ? prev.filter((s) => s !== scope)
        : [...prev, scope],
    );
  }

  function resetForm() {
    setName("");
    setLogoUrl("");
    setRedirectUris("");
    setConfidential(true);
    setScopes(["profile:read"]);
  }

  function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    const uris = redirectUris
      .split(/[\n,]/)
      .map((u) => u.trim())
      .filter(Boolean);

    if (!name.trim()) {
      toast.error("Name is required");
      return;
    }
    if (uris.length === 0) {
      toast.error("At least one redirect URI is required");
      return;
    }
    if (scopes.length === 0) {
      toast.error("Select at least one scope");
      return;
    }

    startTransition(async () => {
      try {
        const created = await register({
          name: name.trim(),
          logoUrl: logoUrl.trim() || null,
          redirectUris: uris,
          scopes,
          confidential,
        });
        setJustCreated(created);
        resetForm();
        toast.success("Client registered", { description: created.clientId });
      } catch (err) {
        toast.error("Registration failed", {
          description: err instanceof Error ? err.message : String(err),
        });
      }
    });
  }

  return (
    <div className="space-y-8">
      {justCreated && (
        <SecretReveal
          registration={justCreated}
          onDismiss={() => setJustCreated(null)}
        />
      )}

      {/* Register form */}
      <section className="rounded-lg border border-border-default bg-surface p-5">
        <h2 className="mb-4 text-base font-semibold text-primary">
          Register a new client
        </h2>
        <form onSubmit={onSubmit} className="space-y-4">
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <label className="block">
              <span className="mb-1 block text-xs font-medium text-secondary">
                Name
              </span>
              <input
                className={inputClass}
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder="Acme Health Dashboard"
              />
            </label>
            <label className="block">
              <span className="mb-1 block text-xs font-medium text-secondary">
                Logo URL <span className="text-tertiary">(optional)</span>
              </span>
              <input
                className={inputClass}
                value={logoUrl}
                onChange={(e) => setLogoUrl(e.target.value)}
                placeholder="https://acme.example/logo.png"
              />
            </label>
          </div>

          <label className="block">
            <span className="mb-1 block text-xs font-medium text-secondary">
              Redirect URIs
            </span>
            <textarea
              className={`${inputClass} min-h-[72px] font-mono`}
              value={redirectUris}
              onChange={(e) => setRedirectUris(e.target.value)}
              placeholder={"https://acme.example/callback\nhttps://acme.example/oauth/return"}
            />
            <span className="mt-1 block text-xs text-tertiary">
              One per line (or comma-separated).
            </span>
          </label>

          <fieldset>
            <legend className="mb-2 text-xs font-medium text-secondary">
              Scopes
            </legend>
            <div className="space-y-2">
              {OAUTH_SCOPE_CATALOG.map((s) => (
                <label
                  key={s.scope}
                  className="flex cursor-pointer items-start gap-2.5 rounded-md border border-border-default bg-canvas px-3 py-2 text-sm"
                >
                  <input
                    type="checkbox"
                    className="mt-0.5 accent-accent"
                    checked={scopes.includes(s.scope)}
                    onChange={() => toggleScope(s.scope)}
                  />
                  <span>
                    <code className="text-xs font-medium text-primary">
                      {s.scope}
                    </code>
                    <span className="ml-2 text-tertiary">{s.description}</span>
                  </span>
                </label>
              ))}
            </div>
          </fieldset>

          <label className="flex cursor-pointer items-start gap-2.5 text-sm">
            <input
              type="checkbox"
              className="mt-0.5 accent-accent"
              checked={confidential}
              onChange={(e) => setConfidential(e.target.checked)}
            />
            <span>
              <span className="font-medium text-primary">
                Confidential client
              </span>
              <span className="ml-2 text-tertiary">
                Issues a client secret (server-side apps). Leave unchecked for
                public PKCE-only clients (mobile / SPA).
              </span>
            </span>
          </label>

          <div className="flex justify-end">
            <button
              type="submit"
              disabled={pending}
              className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-inverse transition-opacity hover:opacity-90 disabled:opacity-50"
            >
              {pending ? "Registering…" : "Register client"}
            </button>
          </div>
        </form>
      </section>

      {/* Existing clients */}
      <section>
        <h2 className="mb-3 text-base font-semibold text-primary">
          Registered clients
        </h2>
        {clients.length === 0 ? (
          <p className="rounded-lg border border-border-default bg-surface px-5 py-8 text-center text-sm text-tertiary">
            No OAuth clients registered yet.
          </p>
        ) : (
          <div className="space-y-3">
            {clients.map((c) => (
              <ClientCard key={c.clientId} client={c} />
            ))}
          </div>
        )}
      </section>
    </div>
  );
}

function ClientCard({ client }: { client: OAuthClientSummary }) {
  return (
    <div className="rounded-lg border border-border-default bg-surface p-5">
      <div className="flex items-start justify-between gap-4">
        <div className="min-w-0">
          <h3 className="text-sm font-semibold text-primary">{client.name}</h3>
          <CopyableCode value={client.clientId} className="mt-1" />
        </div>
        <span
          className={`shrink-0 rounded-full px-2.5 py-0.5 text-xs font-medium ${
            client.confidential
              ? "bg-canvas-muted text-secondary"
              : "border border-border-default text-tertiary"
          }`}
        >
          {client.confidential ? "Confidential" : "Public (PKCE)"}
        </span>
      </div>

      <div className="mt-4 grid grid-cols-1 gap-4 sm:grid-cols-2">
        <div>
          <h4 className="caps-mono text-[10px] tracking-[0.08em] text-tertiary">
            Redirect URIs
          </h4>
          <ul className="mt-1.5 space-y-1">
            {client.redirectUris.map((u) => (
              <li key={u} className="break-all font-mono text-xs text-secondary">
                {u}
              </li>
            ))}
          </ul>
        </div>
        <div>
          <h4 className="caps-mono text-[10px] tracking-[0.08em] text-tertiary">
            Scopes
          </h4>
          <div className="mt-1.5 flex flex-wrap gap-1.5">
            {client.scopes.map((s) => (
              <code
                key={s}
                className="rounded bg-canvas-muted px-1.5 py-0.5 text-xs text-secondary"
              >
                {s}
              </code>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}

function SecretReveal({
  registration,
  onDismiss,
}: {
  registration: OAuthClientRegistration;
  onDismiss: () => void;
}) {
  return (
    <section className="rounded-lg border border-accent bg-surface p-5">
      <div className="flex items-start justify-between gap-4">
        <h2 className="text-base font-semibold text-primary">
          <i className="ti ti-circle-check mr-1.5 text-accent" aria-hidden />
          {registration.name} registered
        </h2>
        <button
          onClick={onDismiss}
          className="rounded-md border border-border-default px-3 py-1.5 text-xs font-medium text-secondary hover:text-primary"
        >
          Done
        </button>
      </div>

      <div className="mt-4 space-y-3">
        <div>
          <h4 className="caps-mono text-[10px] tracking-[0.08em] text-tertiary">
            Client ID
          </h4>
          <CopyableCode value={registration.clientId} className="mt-1" />
        </div>

        {registration.clientSecret ? (
          <div>
            <h4 className="caps-mono text-[10px] tracking-[0.08em] text-tertiary">
              Client secret
            </h4>
            <CopyableCode value={registration.clientSecret} className="mt-1" />
            <p className="mt-2 text-xs font-medium text-accent">
              Copy this now — it is hashed on the server and can never be shown
              again.
            </p>
          </div>
        ) : (
          <p className="text-xs text-tertiary">
            Public client — no secret is issued. It must use PKCE.
          </p>
        )}
      </div>
    </section>
  );
}

function CopyableCode({
  value,
  className = "",
}: {
  value: string;
  className?: string;
}) {
  const toast = useToast();
  const [copied, setCopied] = useState(false);

  async function copy() {
    try {
      await navigator.clipboard.writeText(value);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      toast.error("Couldn't copy to clipboard");
    }
  }

  return (
    <button
      onClick={copy}
      title="Copy"
      className={`inline-flex max-w-full items-center gap-2 rounded-md border border-border-default bg-canvas px-2.5 py-1.5 font-mono text-xs text-secondary hover:text-primary ${className}`}
    >
      <span className="truncate">{value}</span>
      <i
        className={`ti ${copied ? "ti-check text-accent" : "ti-copy text-tertiary"} shrink-0`}
        aria-hidden
      />
    </button>
  );
}
