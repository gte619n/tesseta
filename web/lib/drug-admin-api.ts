import { apiFetch, apiJson } from './api';
import type { Drug, DrugCategory, DrugForm } from './types/medication';

// Admin-only drug catalog APIs. Server-only (uses apiFetch).

export async function listAdminDrugs(): Promise<Drug[]> {
  return apiJson<Drug[]>('/api/admin/drugs');
}

export type AdminUpdateDrugRequest = {
  name?: string;
  aliases?: string[];
  category?: DrugCategory;
  form?: DrugForm;
  defaultUnit?: string;
};

export async function updateAdminDrug(
  drugId: string,
  data: AdminUpdateDrugRequest,
): Promise<Drug> {
  const res = await apiFetch(`/api/admin/drugs/${drugId}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  });
  if (!res.ok) throw new Error(`Update drug failed: ${res.status}`);
  return res.json();
}

export async function deleteAdminDrug(drugId: string): Promise<void> {
  const res = await apiFetch(`/api/admin/drugs/${drugId}`, { method: 'DELETE' });
  if (!res.ok) {
    let detail = '';
    try {
      detail = await res.text();
    } catch {
      // ignore
    }
    throw new Error(`Delete drug failed: ${res.status}${detail ? ' — ' + detail : ''}`);
  }
}

export async function getDrugImagePrompt(drugId: string): Promise<string> {
  const data = await apiJson<{ prompt: string }>(`/api/admin/drugs/${drugId}/image-prompt`);
  return data.prompt ?? '';
}

export async function regenerateDrugImage(
  drugId: string,
  promptOverride?: string,
): Promise<Drug> {
  const res = await apiFetch(`/api/admin/drugs/${drugId}/regenerate-image`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ promptOverride: promptOverride ?? null }),
  });
  if (!res.ok) throw new Error(`Regenerate drug image failed: ${res.status}`);
  return res.json();
}

export async function mergeAdminDrugs(sourceId: string, targetId: string): Promise<Drug> {
  const res = await apiFetch(`/api/admin/drugs/${sourceId}/merge-into/${targetId}`, {
    method: 'POST',
  });
  if (!res.ok) throw new Error(`Merge drugs failed: ${res.status}`);
  return res.json();
}
