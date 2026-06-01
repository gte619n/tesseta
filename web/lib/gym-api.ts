import { apiFetch, apiJson } from './api';
import type {
  Location,
  Equipment,
  AdminEquipment,
  CreateLocationRequest,
  UpdateLocationRequest,
  CreateEquipmentRequest,
  ImportPreviewResponse,
  ImportConfirmRequest,
  ImportConfirmResponse,
} from './types/gym';

// Location APIs
export async function getLocations(includeInactive = false): Promise<Location[]> {
  const params = includeInactive ? '?include=inactive' : '';
  return apiJson<Location[]>(`/api/me/gyms${params}`);
}

export async function getLocation(locationId: string): Promise<Location> {
  return apiJson<Location>(`/api/me/gyms/${locationId}`);
}

export async function createLocation(data: CreateLocationRequest): Promise<Location> {
  const res = await apiFetch('/api/me/gyms', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  });
  if (!res.ok) throw new Error(`Create location failed: ${res.status}`);
  return res.json();
}

export async function updateLocation(locationId: string, data: UpdateLocationRequest): Promise<Location> {
  const res = await apiFetch(`/api/me/gyms/${locationId}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  });
  if (!res.ok) throw new Error(`Update location failed: ${res.status}`);
  return res.json();
}

export async function deleteLocation(locationId: string): Promise<void> {
  const res = await apiFetch(`/api/me/gyms/${locationId}`, { method: 'DELETE' });
  if (!res.ok) throw new Error(`Delete location failed: ${res.status}`);
}

export async function setDefaultLocation(locationId: string): Promise<void> {
  const res = await apiFetch(`/api/me/gyms/${locationId}/default`, { method: 'POST' });
  if (!res.ok) throw new Error(`Set default failed: ${res.status}`);
}

export async function uploadCoverPhoto(locationId: string, file: File): Promise<string> {
  const formData = new FormData();
  formData.append('file', file);
  const res = await apiFetch(`/api/me/gyms/${locationId}/photo`, {
    method: 'POST',
    body: formData,
  });
  if (!res.ok) throw new Error(`Upload failed: ${res.status}`);
  const data = await res.json();
  return data.coverPhotoUrl;
}

export async function deleteCoverPhoto(locationId: string): Promise<void> {
  const res = await apiFetch(`/api/me/gyms/${locationId}/photo`, { method: 'DELETE' });
  if (!res.ok) throw new Error(`Delete photo failed: ${res.status}`);
}

// Equipment Catalog APIs
export async function getEquipmentCatalog(params?: {
  search?: string;
  category?: string;
  subcategory?: string;
}): Promise<Equipment[]> {
  const searchParams = new URLSearchParams();
  if (params?.search) searchParams.set('search', params.search);
  if (params?.category) searchParams.set('category', params.category);
  if (params?.subcategory) searchParams.set('sub', params.subcategory);
  const query = searchParams.toString();
  return apiJson<Equipment[]>(`/api/equipment${query ? `?${query}` : ''}`);
}

export async function getEquipment(equipmentId: string): Promise<Equipment> {
  return apiJson<Equipment>(`/api/equipment/${equipmentId}`);
}

export async function getEquipmentCategories(): Promise<Record<string, string[]>> {
  return apiJson<Record<string, string[]>>('/api/equipment/categories');
}

// Equipment Submission APIs
export async function submitEquipment(data: CreateEquipmentRequest): Promise<Equipment> {
  const res = await apiFetch('/api/me/equipment', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  });
  if (!res.ok) throw new Error(`Submit equipment failed: ${res.status}`);
  return res.json();
}

export async function getMyEquipment(): Promise<Equipment[]> {
  return apiJson<Equipment[]>('/api/me/equipment');
}

export async function deleteMyEquipment(equipmentId: string): Promise<void> {
  const res = await apiFetch(`/api/me/equipment/${equipmentId}`, { method: 'DELETE' });
  if (!res.ok) throw new Error(`Delete equipment failed: ${res.status}`);
}

// Admin APIs
export async function getPendingEquipment(): Promise<AdminEquipment[]> {
  return apiJson<AdminEquipment[]>('/api/admin/equipment/pending');
}

export async function getAdminCatalog(): Promise<AdminEquipment[]> {
  return apiJson<AdminEquipment[]>('/api/admin/equipment/catalog');
}

// Admin create — writes equipment straight into the shared catalog (ACTIVE,
// no owner). The backend enum deserializes by its uppercase name, so the
// lowercase UI literal is normalized here at the wire boundary.
export async function createCatalogEquipment(data: CreateEquipmentRequest): Promise<Equipment> {
  const res = await apiFetch('/api/admin/equipment', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ...data, specSchema: data.specSchema.toUpperCase() }),
  });
  if (!res.ok) {
    let detail = '';
    try {
      const body = await res.json();
      detail = body?.message ?? '';
    } catch {
      // ignore
    }
    throw new Error(detail || `Create equipment failed: ${res.status}`);
  }
  return res.json();
}

export async function approveEquipment(equipmentId: string): Promise<Equipment> {
  const res = await apiFetch(`/api/admin/equipment/${equipmentId}/approve`, { method: 'POST' });
  if (!res.ok) throw new Error(`Approve failed: ${res.status}`);
  return res.json();
}

export async function rejectEquipment(equipmentId: string, reason?: string): Promise<void> {
  const res = await apiFetch(`/api/admin/equipment/${equipmentId}/reject`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ reason: reason ?? '' }),
  });
  if (!res.ok) throw new Error(`Reject failed: ${res.status}`);
}

export async function updateEquipment(equipmentId: string, data: Partial<CreateEquipmentRequest>): Promise<Equipment> {
  const res = await apiFetch(`/api/admin/equipment/${equipmentId}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  });
  if (!res.ok) throw new Error(`Update equipment failed: ${res.status}`);
  return res.json();
}

export async function getEquipmentImagePrompt(equipmentId: string): Promise<string> {
  const data = await apiJson<{ prompt: string }>(`/api/admin/equipment/${equipmentId}/image-prompt`);
  return data.prompt ?? '';
}

export async function regenerateEquipmentImage(
  equipmentId: string,
  promptOverride?: string,
): Promise<void> {
  const res = await apiFetch(`/api/admin/equipment/${equipmentId}/regenerate-image`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ promptOverride: promptOverride ?? null }),
  });
  if (!res.ok) throw new Error(`Regenerate image failed: ${res.status}`);
}

export async function uploadEquipmentImage(equipmentId: string, file: File): Promise<Equipment> {
  const formData = new FormData();
  formData.append('file', file);
  const res = await apiFetch(`/api/admin/equipment/${equipmentId}/upload-image`, {
    method: 'POST',
    body: formData,
  });
  if (!res.ok) throw new Error(`Upload image failed: ${res.status}`);
  return res.json();
}

export async function selectEquipmentImage(equipmentId: string, imageUrl: string): Promise<Equipment> {
  const res = await apiFetch(`/api/admin/equipment/${equipmentId}/select-image`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ imageUrl }),
  });
  if (!res.ok) throw new Error(`Select image failed: ${res.status}`);
  return res.json();
}

export async function deleteEquipmentImageCandidate(
  equipmentId: string,
  imageUrl: string,
): Promise<Equipment> {
  const res = await apiFetch(`/api/admin/equipment/${equipmentId}/delete-image`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ imageUrl }),
  });
  if (!res.ok) throw new Error(`Delete image failed: ${res.status}`);
  return res.json();
}

export async function mergeEquipment(sourceId: string, targetId: string): Promise<Equipment> {
  const res = await apiFetch(`/api/admin/equipment/${sourceId}/merge-into/${targetId}`, {
    method: 'POST',
  });
  if (!res.ok) throw new Error(`Merge failed: ${res.status}`);
  return res.json();
}

export async function updateLocationEquipmentSpecs(
  locationId: string,
  equipmentId: string,
  specs: Record<string, unknown>,
): Promise<Location> {
  const res = await apiFetch(`/api/me/gyms/${locationId}/equipment/${equipmentId}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ specs }),
  });
  if (!res.ok) throw new Error(`Update location specs failed: ${res.status}`);
  return res.json();
}

// Location Equipment Management APIs
export async function addEquipmentToLocation(locationId: string, equipmentId: string): Promise<void> {
  const res = await apiFetch(`/api/me/gyms/${locationId}/equipment/${equipmentId}`, { method: 'POST' });
  if (!res.ok) throw new Error(`Add equipment failed: ${res.status}`);
}

export async function removeEquipmentFromLocation(locationId: string, equipmentId: string): Promise<void> {
  const res = await apiFetch(`/api/me/gyms/${locationId}/equipment/${equipmentId}`, { method: 'DELETE' });
  if (!res.ok) throw new Error(`Remove equipment failed: ${res.status}`);
}

// Bulk Equipment Import APIs (IMPL-GYM-002)
export async function bulkImportPreview(
  locationId: string,
  rawText: string,
): Promise<ImportPreviewResponse> {
  const res = await apiFetch(`/api/me/gyms/${locationId}/equipment/import/preview`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ rawText }),
  });
  if (!res.ok) throw new Error(`Bulk import preview failed: ${res.status}`);
  return res.json();
}

export async function bulkImportConfirm(
  locationId: string,
  body: ImportConfirmRequest,
): Promise<ImportConfirmResponse> {
  const res = await apiFetch(`/api/me/gyms/${locationId}/equipment/import/confirm`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`Bulk import confirm failed: ${res.status}`);
  return res.json();
}
