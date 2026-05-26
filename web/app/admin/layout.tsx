import Link from 'next/link';
import { requireAdmin } from '@/lib/admin';
import { AdminSubNav } from '@/components/admin/AdminSubNav';

export default async function AdminLayout({ children }: { children: React.ReactNode }) {
  await requireAdmin();

  return (
    <div className="min-h-screen bg-canvas">
      <header className="border-b border-border-default bg-surface">
        <div className="container mx-auto flex max-w-7xl items-center justify-between px-4 py-4">
          <Link href="/admin" className="text-sm font-semibold text-primary">
            Administration
          </Link>
          <Link href="/" className="text-xs text-tertiary hover:text-secondary">
            ← Back to app
          </Link>
        </div>
        <div className="container mx-auto max-w-7xl px-4">
          <AdminSubNav />
        </div>
      </header>
      {children}
    </div>
  );
}
