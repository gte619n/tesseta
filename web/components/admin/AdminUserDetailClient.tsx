"use client";

import { useState, useTransition } from "react";
import Link from "next/link";
import type { Route } from "next";
import { useToast } from "@/components/ui/Toast";
import { UserStatusBadge } from "@/components/admin/UserStatusBadge";
import type {
  AdminUserDetail,
  AllowlistEntry,
  UserStatus,
} from "@/lib/types/user-admin";

interface Props {
  user: AdminUserDetail;
  allowlist: AllowlistEntry[];
  setStatus: (status: UserStatus) => Promise<void>;
  setAdmin: (grant: boolean) => Promise<void>;
  approve: () => Promise<void>;
  addAllowlist: (email: string) => Promise<void>;
  removeAllowlist: (email: string) => Promise<void>;
}

const inputClass =
  "w-full rounded-md border border-border-default bg-canvas px-3 py-2 text-sm text-primary placeholder:text-tertiary focus:border-accent focus:outline-none";

function fmt(iso: string): string {
  try {
    return new Date(iso).toLocaleString();
  } catch {
    return iso;
  }
}

export function AdminUserDetailClient({
  user,
  allowlist,
  setStatus,
  setAdmin,
  approve,
  addAllowlist,
  removeAllowlist,
}: Props) {
  const toast = useToast();
  const [busy, startBusy] = useTransition();
  const [newEmail, setNewEmail] = useState("");

  const isAdmin = user.roles.includes("ADMIN");

  function run(fn: () => Promise<void>, ok: string) {
    startBusy(async () => {
      try {
        await fn();
        toast.success(ok);
      } catch (err) {
        toast.error("Action failed", {
          description: err instanceof Error ? err.message : String(err),
        });
      }
    });
  }

  return (
    <div className="space-y-6">
      <div>
        <Link
          href={"/admin/users" as Route}
          className="text-xs text-tertiary hover:text-secondary"
        >
          ← All users
        </Link>
        <div className="mt-2 flex items-center justify-between gap-4">
          <div className="min-w-0">
            <h1 className="truncate text-2xl font-semibold text-primary">
              {user.displayName || user.email}
            </h1>
            <p className="truncate text-sm text-tertiary">{user.email}</p>
          </div>
          <UserStatusBadge status={user.status} />
        </div>
      </div>

      {/* Profile / metadata */}
      <section className="rounded-lg border border-border-default bg-surface p-5">
        <h2 className="mb-3 text-base font-semibold text-primary">Profile</h2>
        <dl className="grid grid-cols-1 gap-x-6 gap-y-2 text-sm sm:grid-cols-2">
          <Field label="User ID" value={user.userId} mono />
          <Field label="Roles" value={user.roles.join(", ") || "USER"} />
          <Field label="Created" value={fmt(user.createdAt)} />
          <Field label="Updated" value={fmt(user.updatedAt)} />
        </dl>
        <div className="mt-4 flex gap-2">
          <ConnBadge
            label="Google Health"
            connected={user.googleHealthConnected}
          />
          <ConnBadge label="Withings" connected={user.withingsConnected} />
        </div>
      </section>

      {/* Status control */}
      <section className="rounded-lg border border-border-default bg-surface p-5">
        <h2 className="mb-1 text-base font-semibold text-primary">
          Account status
        </h2>
        <p className="mb-4 text-xs text-tertiary">
          Suspended and disabled users are locked out on their next request.
          You cannot change your own account to a non-active status (last-admin
          protection is enforced by the backend).
        </p>
        <div className="flex flex-wrap gap-2">
          {user.status === "PENDING_APPROVAL" && (
            <button
              type="button"
              disabled={busy}
              onClick={() => run(approve, "User approved")}
              className="rounded-full bg-accent px-4 py-1.5 text-sm font-medium text-inverse hover:bg-accent-dim disabled:opacity-50"
            >
              Approve
            </button>
          )}
          <StatusButton
            label="Activate"
            disabled={busy || user.status === "ACTIVE"}
            onClick={() => run(() => setStatus("ACTIVE"), "User activated")}
          />
          <StatusButton
            label="Suspend"
            disabled={busy || user.status === "SUSPENDED"}
            onClick={() => run(() => setStatus("SUSPENDED"), "User suspended")}
          />
          <StatusButton
            label="Disable"
            disabled={busy || user.status === "DISABLED"}
            danger
            onClick={() => run(() => setStatus("DISABLED"), "User disabled")}
          />
        </div>
      </section>

      {/* Role control */}
      <section className="rounded-lg border border-border-default bg-surface p-5">
        <h2 className="mb-1 text-base font-semibold text-primary">Admin role</h2>
        <p className="mb-4 text-xs text-tertiary">
          Admins can reach this console. The backend refuses to revoke the last
          remaining admin.
        </p>
        {isAdmin ? (
          <StatusButton
            label="Revoke admin"
            disabled={busy}
            danger
            onClick={() => run(() => setAdmin(false), "Admin revoked")}
          />
        ) : (
          <StatusButton
            label="Grant admin"
            disabled={busy}
            onClick={() => run(() => setAdmin(true), "Admin granted")}
          />
        )}
      </section>

      {/* Invite allowlist */}
      <section className="rounded-lg border border-border-default bg-surface p-5">
        <h2 className="mb-1 text-base font-semibold text-primary">
          Invite allowlist
        </h2>
        <p className="mb-4 text-xs text-tertiary">
          Emails here can sign in without landing in the pending-approval queue.
        </p>
        <div className="mb-4 flex gap-2">
          <input
            className={inputClass}
            type="email"
            value={newEmail}
            onChange={(e) => setNewEmail(e.target.value)}
            placeholder="person@example.com"
          />
          <button
            type="button"
            disabled={busy || !newEmail.trim()}
            onClick={() =>
              run(async () => {
                await addAllowlist(newEmail.trim());
                setNewEmail("");
              }, "Added to allowlist")
            }
            className="shrink-0 rounded-md border border-border-default bg-canvas px-4 py-2 text-sm font-medium text-primary hover:bg-canvas-muted disabled:opacity-50"
          >
            Add
          </button>
        </div>
        {allowlist.length === 0 ? (
          <p className="text-sm text-tertiary">No allowlisted emails.</p>
        ) : (
          <ul className="divide-y divide-border-default">
            {allowlist.map((e) => (
              <li
                key={e.email}
                className="flex items-center justify-between gap-4 py-2"
              >
                <div className="min-w-0">
                  <p className="truncate text-sm text-primary">{e.email}</p>
                  <p className="truncate text-xs text-tertiary">
                    added {fmt(e.addedAt)}
                  </p>
                </div>
                <button
                  type="button"
                  disabled={busy}
                  onClick={() =>
                    run(() => removeAllowlist(e.email), "Removed from allowlist")
                  }
                  className="shrink-0 text-sm text-danger hover:underline disabled:opacity-50"
                >
                  Remove
                </button>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}

function Field({
  label,
  value,
  mono,
}: {
  label: string;
  value: string;
  mono?: boolean;
}) {
  return (
    <div>
      <dt className="text-xs text-tertiary">{label}</dt>
      <dd className={`text-primary ${mono ? "font-mono text-xs" : ""}`}>
        {value}
      </dd>
    </div>
  );
}

function ConnBadge({ label, connected }: { label: string; connected: boolean }) {
  return (
    <span
      className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-medium ${
        connected
          ? "bg-success-muted text-success"
          : "bg-canvas-muted text-tertiary"
      }`}
    >
      <i
        className={`ti ti-${connected ? "plug-connected" : "plug-off"} text-[13px]`}
        aria-hidden
      />
      {label}
    </span>
  );
}

function StatusButton({
  label,
  onClick,
  disabled,
  danger,
}: {
  label: string;
  onClick: () => void;
  disabled?: boolean;
  danger?: boolean;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      className={`rounded-full border px-4 py-1.5 text-sm font-medium disabled:opacity-40 ${
        danger
          ? "border-danger text-danger hover:bg-danger-muted"
          : "border-border-default text-primary hover:bg-canvas-muted"
      }`}
    >
      {label}
    </button>
  );
}
