import type { UserStatus } from '@/lib/user-admin-api';

// Small presentational status pill shared by the user list + detail pages.
const STATUS_STYLE: Record<UserStatus, { label: string; cls: string }> = {
  ACTIVE: { label: 'Active', cls: 'bg-success-muted text-success' },
  PENDING_APPROVAL: { label: 'Pending', cls: 'bg-warning-muted text-warning' },
  SUSPENDED: { label: 'Suspended', cls: 'bg-warning-muted text-warning' },
  DISABLED: { label: 'Disabled', cls: 'bg-danger-muted text-danger' },
};

export function UserStatusBadge({ status }: { status: UserStatus }) {
  const s = STATUS_STYLE[status] ?? {
    label: status,
    cls: 'bg-canvas-muted text-secondary',
  };
  return (
    <span
      className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ${s.cls}`}
    >
      {s.label}
    </span>
  );
}
