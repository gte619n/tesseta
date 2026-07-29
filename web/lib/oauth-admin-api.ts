import { apiFetch, apiJson } from './api';
import type {
  OAuthClientSummary,
  OAuthClientRegistration,
} from './types/oauth';

// Admin-only third-party OAuth client APIs (ADR-0020). Server-only (uses
// apiFetch). Backed by AdminOAuthClientController:
//   GET  /api/admin/oauth-clients  — list (never includes secrets)
//   POST /api/admin/oauth-clients  — register; returns clientSecret ONCE

export async function listOAuthClients(): Promise<OAuthClientSummary[]> {
  return apiJson<OAuthClientSummary[]>('/api/admin/oauth-clients');
}

export type RegisterOAuthClientRequest = {
  name: string;
  logoUrl?: string | null;
  redirectUris: string[];
  scopes: string[];
  confidential: boolean;
};

export async function registerOAuthClient(
  data: RegisterOAuthClientRequest,
): Promise<OAuthClientRegistration> {
  const res = await apiFetch('/api/admin/oauth-clients', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  });
  if (!res.ok) {
    // The controller returns a 400 with a "message" field for validation
    // failures (missing name / redirect_uri, unknown scope). Surface it.
    let detail = '';
    try {
      const body = await res.json();
      detail = body?.message ?? '';
    } catch {
      // ignore — fall back to the status code
    }
    throw new Error(detail || `Register OAuth client failed: ${res.status}`);
  }
  return res.json();
}
