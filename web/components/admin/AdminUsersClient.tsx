"use client";

import { useState, useTransition } from "react";
import Link from "next/link";
import type { Route } from "next";
import { useToast } from "@/components/ui/Toast";
import { UserStatusBadge } from "@/components/admin/UserStatusBadge";
import type { AdminUserSummary, UserStatus } from "@/lib/types/user-admin";

interface Props {
  initialUsers: AdminUserSummary[];
  pending: AdminUserSummary[];
  search: (query: string, status: UserStatus | "") => Promise<AdminUserSummary[]>;
  approve: (userId: string) => Promise<void>;
}

const inputClass =
  "w-full rounded-md border border-border-default bg-canvas px-3 py-2 text-sm text-primary placeholder:text-tertiary focus:border-accent focus:outline-none";

const STATUS_OPTIONS: { value: UserStatus | ""; label: string }[] = [
  { value: "", label: "All statuses" },
  { value: "ACTIVE", label: "Active" },
  { value: "PENDING_APPROVAL", label: "Pending" },
  { value: "SUSPENDED", label: "Suspended" },
  { value: "DISABLED", label: "Disabled" },
];

export function AdminUsersClient({ initialUsers, pending, search, approve }: Props) {
  const toast = useToast();
  const [searching, startSearch] = useTransition();
  const [approving, startApprove] = useTransition();

  const [query, setQuery] = useState("");
  const [status, setStatus] = useState<UserStatus | "">("");
  const [users, setUsers] = useState(initialUsers);
  const [approvedIds, setApprovedIds] = useState<Set<string>>(new Set());

  function onSearch(e: React.FormEvent) {
    e.preventDefault();
    startSearch(async () => {
      try {
        setUsers(await search(query.trim(), status));
      } catch (err) {
        toast.error("Search failed", {
          description: err instanceof Error ? err.message : String(err),
        });
      }
    });
  }

  function onApprove(userId: string) {
    startApprove(async () => {
      try {
        await approve(userId);
        setApprovedIds((prev) => new Set(prev).add(userId));
        toast.success("User approved");
      } catch (err) {
        toast.error("Approve failed", {
          description: err instanceof Error ? err.message : String(err),
        });
      }
    });
  }

  const visiblePending = pending.filter((u) => !approvedIds.has(u.userId));

  return (
    <div className="space-y-8">
      {visiblePending.length > 0 && (
        <section className="rounded-lg border border-border-default bg-surface p-5">
          <h2 className="mb-1 text-base font-semibold text-primary">
            Pending approval
          </h2>
          <p className="mb-4 text-xs text-tertiary">
            Signups awaiting review. Approving grants full access immediately.
          </p>
          <ul className="divide-y divide-border-default">
            {visiblePending.map((u) => (
              <li
                key={u.userId}
                className="flex items-center justify-between gap-4 py-3"
              >
                <div className="min-w-0">
                  <p className="truncate text-sm font-medium text-primary">
                    {u.displayName || u.email}
                  </p>
                  <p className="truncate text-xs text-tertiary">{u.email}</p>
                </div>
                <button
                  type="button"
                  disabled={approving}
                  onClick={() => onApprove(u.userId)}
                  className="shrink-0 rounded-full bg-accent px-4 py-1.5 text-sm font-medium text-inverse hover:bg-accent-dim disabled:opacity-50"
                >
                  Approve
                </button>
              </li>
            ))}
          </ul>
        </section>
      )}

      <section className="rounded-lg border border-border-default bg-surface p-5">
        <form onSubmit={onSearch} className="mb-4 flex flex-wrap gap-3">
          <input
            className={`${inputClass} flex-1 min-w-[200px]`}
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Search by email or name"
          />
          <select
            className={`${inputClass} w-auto`}
            value={status}
            onChange={(e) => setStatus(e.target.value as UserStatus | "")}
          >
            {STATUS_OPTIONS.map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </select>
          <button
            type="submit"
            disabled={searching}
            className="rounded-md border border-border-default bg-canvas px-4 py-2 text-sm font-medium text-primary hover:bg-canvas-muted disabled:opacity-50"
          >
            {searching ? "Searching…" : "Search"}
          </button>
        </form>

        {users.length === 0 ? (
          <p className="py-8 text-center text-sm text-tertiary">
            No users match.
          </p>
        ) : (
          <ul className="divide-y divide-border-default">
            {users.map((u) => (
              <li key={u.userId}>
                <Link
                  href={`/admin/users/${u.userId}` as Route}
                  className="group flex items-center justify-between gap-4 py-3"
                >
                  <div className="min-w-0">
                    <p className="truncate text-sm font-medium text-primary group-hover:text-accent">
                      {u.displayName || u.email}
                    </p>
                    <p className="truncate text-xs text-tertiary">{u.email}</p>
                  </div>
                  <div className="flex shrink-0 items-center gap-3">
                    {u.roles.includes("ADMIN") && (
                      <span className="inline-flex items-center rounded-full bg-canvas-muted px-2 py-0.5 text-xs font-medium text-secondary">
                        Admin
                      </span>
                    )}
                    <UserStatusBadge status={u.status} />
                    <i
                      className="ti ti-chevron-right text-tertiary group-hover:text-accent"
                      aria-hidden
                    />
                  </div>
                </Link>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}
