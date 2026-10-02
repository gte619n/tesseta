// Admin curation console wire types (IMPL-MULTIUSER-01 P3.5). Client-safe:
// contains no server-only imports so both the server api module and the
// 'use client' console can share these shapes. The actual fetch/mutation
// functions live in the server-only lib/curation-admin-api.ts.

export type CatalogStatus =
  | 'PRIVATE'
  | 'PENDING_REVIEW'
  | 'PUBLISHED'
  | 'REJECTED'
  | 'ARCHIVED';

// "program" | "adhoc" | "equipment" | "exercise" | "food"
export type CurationType = 'program' | 'adhoc' | 'equipment' | 'exercise' | 'food';

export type CurationQueueItem = {
  type: CurationType;
  id: string;
  title: string;
  status: CatalogStatus;
  // Internal-only contributor credit (D12); may be null.
  contributorId: string | null;
  submittedAt: string | null;
};

// Catalog types the curation console can approve/reject directly.
export const CURATABLE_TYPES: CurationType[] = ['program', 'adhoc'];
