import { requireAdmin } from '@/lib/admin';
import { getPendingEquipment } from '@/lib/gym-api';
import { PendingEquipmentCard } from '@/components/admin/PendingEquipmentCard';

export const dynamic = 'force-dynamic';

export default async function AdminEquipmentPage() {
  await requireAdmin();

  const pending = await getPendingEquipment();

  return (
    <div className="container mx-auto max-w-5xl py-8 px-4">
      <div className="mb-6 flex items-center justify-between">
        <h1 className="text-2xl font-semibold text-primary">Equipment Review</h1>
        <span className="text-sm text-secondary">
          Pending: {pending.length}
        </span>
      </div>

      {pending.length === 0 ? (
        <div className="rounded-lg border border-border-default bg-surface px-6 py-12 text-center">
          <p className="text-sm text-secondary">No equipment pending review</p>
        </div>
      ) : (
        <div className="space-y-4">
          {pending.map(eq => (
            <PendingEquipmentCard key={eq.equipmentId} equipment={eq} />
          ))}
        </div>
      )}
    </div>
  );
}
