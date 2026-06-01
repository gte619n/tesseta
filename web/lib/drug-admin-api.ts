import { apiFetch, apiJson } from './api';
import type { Drug, DrugCategory, DrugForm } from './types/medication';

// Admin-only drug catalog APIs. Server-only (uses apiFetch).

export async function listAdminDrugs(): Promise<Drug[]> {
  return apiJson<Drug[]>('/api/admin/drugs');
}

export type AdminCreateDrugRequest = {
  name: string;
  aliases?: string[];
  category: DrugCategory;
  form: DrugForm;
  defaultUnit?: string;
  commonDoses?: string[];
  suggestedMarkers?: string[];
  description?: string | null;
};

export async function createAdminDrug(data: AdminCreateDrugRequest): Promise<Drug> {
  const res = await apiFetch('/api/admin/drugs', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  });
  if (!res.ok) {
    let detail = '';
    try {
      const body = await res.json();
      detail = body?.message ?? '';
    } catch {
      // ignore
    }
    throw new Error(detail || `Create drug failed: ${res.status}`);
  }
  return res.json();
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

export async function uploadDrugImage(drugId: string, file: File): Promise<Drug> {
  const formData = new FormData();
  formData.append('file', file);
  // No Content-Type header — let fetch set the multipart boundary.
  const res = await apiFetch(`/api/admin/drugs/${drugId}/upload-image`, {
    method: 'POST',
    body: formData,
  });
  if (!res.ok) throw new Error(`Upload drug image failed: ${res.status}`);
  return res.json();
}

export async function selectDrugImage(drugId: string, imageUrl: string): Promise<Drug> {
  const res = await apiFetch(`/api/admin/drugs/${drugId}/select-image`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ imageUrl }),
  });
  if (!res.ok) throw new Error(`Select drug image failed: ${res.status}`);
  return res.json();
}

export async function deleteDrugImageCandidate(drugId: string, imageUrl: string): Promise<Drug> {
  const res = await apiFetch(`/api/admin/drugs/${drugId}/delete-image`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ imageUrl }),
  });
  if (!res.ok) throw new Error(`Delete drug image failed: ${res.status}`);
  return res.json();
}

export async function mergeAdminDrugs(sourceId: string, targetId: string): Promise<Drug> {
  const res = await apiFetch(`/api/admin/drugs/${sourceId}/merge-into/${targetId}`, {
    method: 'POST',
  });
  if (!res.ok) throw new Error(`Merge drugs failed: ${res.status}`);
  return res.json();
}
