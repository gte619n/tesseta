import { revalidatePath } from 'next/cache';
import {
  getCurationQueue,
  approveCurationItem,
  rejectCurationItem,
  type CurationType,
} from '@/lib/curation-admin-api';
import { AdminCurationClient } from '@/components/admin/AdminCurationClient';
import { pageMetadata } from '@/lib/page-metadata';

export const metadata = pageMetadata('Curation');

export const dynamic = 'force-dynamic';

export default async function AdminCurationPage() {
  // Admin gating handled by app/admin/layout.tsx (and again in apiFetch).
  const queue = await getCurationQueue();

  async function approveAction(type: CurationType, id: string) {
    'use server';
    await approveCurationItem(type, id);
    revalidatePath('/admin/curation');
  }

  async function rejectAction(type: CurationType, id: string, reason: string) {
    'use server';
    await rejectCurationItem(type, id, reason);
    revalidatePath('/admin/curation');
  }

  return (
    <div className="container mx-auto max-w-7xl px-4 py-8">
      <div className="mb-6 flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-semibold text-primary">Curation queue</h1>
          <p className="mt-0.5 text-sm text-tertiary">
            Pending catalog submissions across all types. Programs and ad-hoc
            workouts approve/reject here; equipment and exercises link to their
            own consoles.
          </p>
        </div>
        <span className="text-sm text-secondary">{queue.length} pending</span>
      </div>

      <AdminCurationClient
        items={queue}
        approve={approveAction}
        reject={rejectAction}
      />
    </div>
  );
}
