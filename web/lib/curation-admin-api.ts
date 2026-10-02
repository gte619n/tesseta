import { apiFetch, apiJson } from './api';
import type { CurationQueueItem, CurationType } from './types/curation';

// Admin-only unified curation console (IMPL-MULTIUSER-01 P3.5). Server-only.
// Backed by AdminCurationController:
//   GET  /api/admin/curation/queue                  — all PENDING_REVIEW items
//   POST /api/admin/curation/programs/{id}/approve|reject
//   POST /api/admin/curation/adhoc/{id}/approve|reject
//
// The queue aggregates heterogeneous catalogs normalized to one CatalogStatus
// vocabulary. Approve/reject are OWNED here only for the two NEW catalog types
// (programs, adhoc); equipment/exercise rows are read-only aggregations whose
// mutations live in their own admin consoles (link out — do not duplicate).
//
// Wire types + CURATABLE_TYPES now live in the client-safe lib/types/curation
// module so the 'use client' console can share them without importing this
// server-only module. Re-exported here for existing server-side importers.
export type {
  CatalogStatus,
  CurationType,
  CurationQueueItem,
} from './types/curation';
export { CURATABLE_TYPES } from './types/curation';

export async function getCurationQueue(): Promise<CurationQueueItem[]> {
  return apiJson<CurationQueueItem[]>('/api/admin/curation/queue');
}

async function mutate(path: string, body?: unknown): Promise<void> {
  const res = await apiFetch(path, {
    method: 'POST',
    ...(body !== undefined
      ? {
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(body),
        }
      : {}),
  });
  if (!res.ok) {
    let detail = '';
    try {
      const data = await res.json();
      detail = data?.message ?? '';
    } catch {
      // ignore
    }
    throw new Error(detail || `${path} failed: ${res.status}`);
  }
}

export async function approveCurationItem(
  type: CurationType,
  id: string,
): Promise<void> {
  const seg = type === 'program' ? 'programs' : 'adhoc';
  return mutate(`/api/admin/curation/${seg}/${encodeURIComponent(id)}/approve`);
}

export async function rejectCurationItem(
  type: CurationType,
  id: string,
  reason: string,
): Promise<void> {
  const seg = type === 'program' ? 'programs' : 'adhoc';
  return mutate(`/api/admin/curation/${seg}/${encodeURIComponent(id)}/reject`, {
    reason,
  });
}
