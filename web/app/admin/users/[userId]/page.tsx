import { notFound } from 'next/navigation';
import { revalidatePath } from 'next/cache';
import {
  getAdminUser,
  setUserStatus,
  setUserRole,
  approveUser,
  listAllowlist,
  addAllowlistEntry,
  removeAllowlistEntry,
  type UserStatus,
  type AllowlistEntry,
} from '@/lib/user-admin-api';
import { BackendError } from '@/lib/api';
import { AdminUserDetailClient } from '@/components/admin/AdminUserDetailClient';
import { pageMetadata } from '@/lib/page-metadata';

export const metadata = pageMetadata('User detail');

export const dynamic = 'force-dynamic';

export default async function AdminUserDetailPage({
  params,
}: {
  params: Promise<{ userId: string }>;
}) {
  const { userId } = await params;

  const user = await getAdminUser(userId).catch((e) => {
    if (e instanceof BackendError && e.status === 404) return null;
    throw e;
  });
  if (!user) notFound();

  const allowlist = await listAllowlist().catch(() => [] as AllowlistEntry[]);

  async function statusAction(next: UserStatus) {
    'use server';
    await setUserStatus(userId, next);
    revalidatePath(`/admin/users/${userId}`);
  }

  async function roleAction(grant: boolean) {
    'use server';
    await setUserRole(userId, 'ADMIN', grant);
    revalidatePath(`/admin/users/${userId}`);
  }

  async function approveAction() {
    'use server';
    await approveUser(userId);
    revalidatePath(`/admin/users/${userId}`);
  }

  async function addAllowlistAction(email: string) {
    'use server';
    await addAllowlistEntry(email);
    revalidatePath(`/admin/users/${userId}`);
  }

  async function removeAllowlistAction(email: string) {
    'use server';
    await removeAllowlistEntry(email);
    revalidatePath(`/admin/users/${userId}`);
  }

  return (
    <div className="container mx-auto max-w-3xl px-4 py-8">
      <AdminUserDetailClient
        user={user}
        allowlist={allowlist}
        setStatus={statusAction}
        setAdmin={roleAction}
        approve={approveAction}
        addAllowlist={addAllowlistAction}
        removeAllowlist={removeAllowlistAction}
      />
    </div>
  );
}
