import Link from 'next/link';
import { getPendingEquipment, getAdminCatalog } from '@/lib/gym-api';
import { listAdminDrugs } from '@/lib/drug-admin-api';

export const dynamic = 'force-dynamic';

export default async function AdminOverviewPage() {
  // Admin gating handled by app/admin/layout.tsx
  const [pending, catalog, drugs] = await Promise.all([
    getPendingEquipment().catch(() => []),
    getAdminCatalog().catch(() => []),
    listAdminDrugs().catch(() => []),
  ]);

  return (
    <div className="container mx-auto max-w-7xl px-4 py-8">
      <h1 className="mb-1 text-2xl font-semibold text-primary">Admin overview</h1>
      <p className="mb-6 text-sm text-tertiary">
        Quick links to the things you can manage.
      </p>

      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <SectionCard
          href="/admin/equipment"
          icon="barbell"
          title="Equipment"
          line1={`${pending.length} pending review`}
          line2={`${catalog.length} active in catalog`}
        />
        <SectionCard
          href="/admin/drugs"
          icon="pill"
          title="Drugs"
          line1={`${drugs.length} in catalog`}
          line2="Edit, regenerate, or merge"
        />
      </div>
    </div>
  );
}

function SectionCard({
  href,
  icon,
  title,
  line1,
  line2,
}: {
  href: string;
  icon: string;
  title: string;
  line1: string;
  line2: string;
}) {
  return (
    <Link
      href={href as never}
      className="group flex items-start gap-4 rounded-lg border border-border-default bg-surface p-5 transition-shadow hover:shadow-md"
    >
      <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-md bg-canvas-muted text-secondary">
        <i className={`ti ti-${icon} text-xl`} aria-hidden />
      </div>
      <div className="min-w-0 flex-1">
        <h2 className="text-base font-semibold text-primary group-hover:text-accent">
          {title}
        </h2>
        <p className="mt-0.5 text-xs text-secondary">{line1}</p>
        <p className="text-xs text-tertiary">{line2}</p>
      </div>
      <i className="ti ti-arrow-right shrink-0 text-tertiary group-hover:text-accent" aria-hidden />
    </Link>
  );
}
