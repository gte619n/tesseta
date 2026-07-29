import { revalidatePath } from 'next/cache';
import {
  listOAuthClients,
  registerOAuthClient,
  type RegisterOAuthClientRequest,
} from '@/lib/oauth-admin-api';
import { AdminOAuthClient } from '@/components/admin/AdminOAuthClient';
import { pageMetadata } from '@/lib/page-metadata';

export const metadata = pageMetadata('OAuth Clients');

// Read-mostly: registered OAuth clients are global platform config, not
// per-user state. Rendered per request (auth() reads cookies); the register
// action revalidates to refresh the list immediately.
export const dynamic = 'force-dynamic';

export default async function AdminOAuthPage() {
  // Admin gating handled by app/admin/layout.tsx (and again in apiFetch).
  const clients = await listOAuthClients();

  async function registerAction(data: RegisterOAuthClientRequest) {
    'use server';
    // Returns the registration including the one-time clientSecret so the
    // client component can display it. It is never retrievable again.
    const created = await registerOAuthClient(data);
    revalidatePath('/admin/oauth');
    return created;
  }

  return (
    <div className="container mx-auto max-w-7xl px-4 py-8">
      <div className="mb-6 flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-semibold text-primary">OAuth clients</h1>
          <p className="mt-0.5 text-sm text-tertiary">
            Third-party apps registered to request read-only access to user data.
          </p>
        </div>
        <span className="text-sm text-secondary">
          {clients.length} registered
        </span>
      </div>

      <AdminOAuthClient clients={clients} register={registerAction} />
    </div>
  );
}
