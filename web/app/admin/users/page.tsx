import { revalidatePath } from 'next/cache';
import {
  listAdminUsers,
  listPendingUsers,
  approveUser,
  type AdminUserSummary,
  type UserStatus,
} from '@/lib/user-admin-api';
import { AdminUsersClient } from '@/components/admin/AdminUsersClient';
import { pageMetadata } from '@/lib/page-metadata';

export const metadata = pageMetadata('Users');

// Per-user admin state — never statically cached. Mutations call revalidatePath.
export const dynamic = 'force-dynamic';

export default async function AdminUsersPage() {
  // Admin gating handled by app/admin/layout.tsx (and again in apiFetch).
  const [users, pending] = await Promise.all([
    listAdminUsers({ limit: 50 }),
    listPendingUsers().catch(() => [] as AdminUserSummary[]),
  ]);

  async function searchAction(
    query: string,
    status: UserStatus | '',
  ): Promise<AdminUserSummary[]> {
    'use server';
    return listAdminUsers({ query, status, limit: 50 });
  }

  async function approveAction(userId: string) {
    'use server';
    await approveUser(userId);
    revalidatePath('/admin/users');
  }

  return (
    <div className="container mx-auto max-w-7xl px-4 py-8">
      <div className="mb-6 flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-semibold text-primary">Users</h1>
          <p className="mt-0.5 text-sm text-tertiary">
            Search logins, approve pending signups, manage roles and status.
          </p>
        </div>
        <span className="text-sm text-secondary">
          {pending.length} pending approval
        </span>
      </div>

      <AdminUsersClient
        initialUsers={users}
        pending={pending}
        search={searchAction}
        approve={approveAction}
      />
    </div>
  );
}
