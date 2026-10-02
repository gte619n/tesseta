import { apiFetch, apiJson } from './api';
import type {
  UserStatus,
  UserRole,
  AdminUserSummary,
  AdminUserDetail,
  AllowlistEntry,
} from './types/user-admin';

// Admin-only user administration APIs (IMPL-MULTIUSER-01 P1.5). Server-only
// (uses apiFetch). Backed by AdminUserController:
//   GET    /api/admin/users?query=&status=&limit=   — search/list
//   GET    /api/admin/users/pending                 — PENDING_APPROVAL queue
//   GET    /api/admin/users/{id}                     — detail (+ connection flags)
//   POST   /api/admin/users/{id}/status              — change account status
//   POST   /api/admin/users/{id}/approve             — approve a pending signup
//   POST   /api/admin/users/{id}/role                — grant/revoke a role
//   GET    /api/admin/users/allowlist                — invite allowlist
//   POST   /api/admin/users/allowlist                — add an email
//   DELETE /api/admin/users/allowlist/{email}        — remove an email
//
// Connection presence is reported as booleans only — tokens are never exposed.
//
// Wire types now live in the client-safe lib/types/user-admin module so the
// 'use client' console can share them without importing this server-only
// module. Re-exported here for existing server-side importers.
export type {
  UserStatus,
  UserRole,
  AdminUserSummary,
  AdminUserDetail,
  AllowlistEntry,
} from './types/user-admin';

// ---- reads ----

export async function listAdminUsers(params?: {
  query?: string;
  status?: UserStatus | '';
  limit?: number;
}): Promise<AdminUserSummary[]> {
  const sp = new URLSearchParams();
  if (params?.query) sp.set('query', params.query);
  if (params?.status) sp.set('status', params.status);
  if (params?.limit) sp.set('limit', String(params.limit));
  const qs = sp.toString();
  return apiJson<AdminUserSummary[]>(`/api/admin/users${qs ? `?${qs}` : ''}`);
}

export async function listPendingUsers(): Promise<AdminUserSummary[]> {
  return apiJson<AdminUserSummary[]>('/api/admin/users/pending');
}

export async function getAdminUser(userId: string): Promise<AdminUserDetail> {
  return apiJson<AdminUserDetail>(`/api/admin/users/${encodeURIComponent(userId)}`);
}

// ---- user mutations ----

async function postUser(path: string, body?: unknown): Promise<AdminUserDetail> {
  const res = await apiFetch(path, {
    method: 'POST',
    ...(body !== undefined
      ? {
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(body),
        }
      : {}),
  });
  if (!res.ok) {
    // The controller returns a problem with a "message" field for conflicts
    // (last-admin guard, self-lockout) and bad requests. Surface it verbatim.
    let detail = '';
    try {
      const data = await res.json();
      detail = data?.message ?? '';
    } catch {
      // ignore — fall back to the status code
    }
    throw new Error(detail || `${path} failed: ${res.status}`);
  }
  return res.json();
}

export async function setUserStatus(
  userId: string,
  status: UserStatus,
): Promise<AdminUserDetail> {
  return postUser(`/api/admin/users/${encodeURIComponent(userId)}/status`, { status });
}

export async function approveUser(userId: string): Promise<AdminUserDetail> {
  return postUser(`/api/admin/users/${encodeURIComponent(userId)}/approve`);
}

export async function setUserRole(
  userId: string,
  role: UserRole,
  grant: boolean,
): Promise<AdminUserDetail> {
  return postUser(`/api/admin/users/${encodeURIComponent(userId)}/role`, { role, grant });
}

// ---- invite allowlist ----

export async function listAllowlist(): Promise<AllowlistEntry[]> {
  return apiJson<AllowlistEntry[]>('/api/admin/users/allowlist');
}

export async function addAllowlistEntry(email: string): Promise<AllowlistEntry[]> {
  const res = await apiFetch('/api/admin/users/allowlist', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email }),
  });
  if (!res.ok) {
    let detail = '';
    try {
      const data = await res.json();
      detail = data?.message ?? '';
    } catch {
      // ignore
    }
    throw new Error(detail || `Add allowlist entry failed: ${res.status}`);
  }
  return res.json();
}

export async function removeAllowlistEntry(email: string): Promise<AllowlistEntry[]> {
  const res = await apiFetch(
    `/api/admin/users/allowlist/${encodeURIComponent(email)}`,
    { method: 'DELETE' },
  );
  if (!res.ok) {
    throw new Error(`Remove allowlist entry failed: ${res.status}`);
  }
  return res.json();
}
