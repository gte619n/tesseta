// Admin user-administration wire types (IMPL-MULTIUSER-01 P1.5). Client-safe:
// no server-only imports, so both the server api module and the 'use client'
// admin console can share these shapes. The fetch/mutation functions live in
// the server-only lib/user-admin-api.ts.
//
// Connection presence is reported as booleans only — tokens are never exposed.

export type UserStatus =
  | 'PENDING_APPROVAL'
  | 'ACTIVE'
  | 'SUSPENDED'
  | 'DISABLED';

export type UserRole = 'ADMIN' | 'USER';

export type AdminUserSummary = {
  userId: string;
  email: string;
  displayName: string | null;
  roles: string[];
  status: UserStatus;
  createdAt: string;
};

export type AdminUserDetail = AdminUserSummary & {
  updatedAt: string;
  googleHealthConnected: boolean;
  withingsConnected: boolean;
};

export type AllowlistEntry = {
  email: string;
  addedBy: string;
  addedAt: string;
};
