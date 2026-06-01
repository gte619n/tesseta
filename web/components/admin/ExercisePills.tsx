import type { ExerciseStatus, ExerciseMediaStatus } from '@/lib/types/exercise';

const STATUS_MAP: Record<ExerciseStatus, { label: string; cls: string }> = {
  DRAFT: { label: 'Draft', cls: 'bg-canvas-muted text-tertiary' },
  PUBLISHED: { label: 'Published', cls: 'bg-accent-bg text-accent-dim' },
  ARCHIVED: { label: 'Archived', cls: 'bg-canvas-muted text-tertiary' },
};

const MEDIA_MAP: Record<ExerciseMediaStatus, { label: string; cls: string }> = {
  NONE: { label: 'No media', cls: 'bg-canvas-muted text-tertiary' },
  PENDING: { label: 'Generating', cls: 'bg-warn-bg text-warn' },
  NEEDS_REVIEW: { label: 'Needs review', cls: 'bg-warn-bg text-warn' },
  APPROVED: { label: 'Media approved', cls: 'bg-accent-bg text-accent-dim' },
  FAILED: { label: 'Media failed', cls: 'bg-alert-bg text-alert' },
};

export function StatusPill({ status }: { status: ExerciseStatus }) {
  const m = STATUS_MAP[status];
  return (
    <span className={`caps-mono rounded-[3px] px-1.5 py-px text-[9px] tracking-[0.06em] ${m.cls}`}>
      {m.label}
    </span>
  );
}

export function MediaStatusPill({ status }: { status: ExerciseMediaStatus }) {
  const m = MEDIA_MAP[status];
  return (
    <span className={`caps-mono rounded-[3px] px-1.5 py-px text-[9px] tracking-[0.06em] ${m.cls}`}>
      {m.label}
    </span>
  );
}
