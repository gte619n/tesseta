// Third-party OAuth platform types (ADR-0020). Mirror the backend wire shapes:
// the consent metadata from GET /oauth/authorize, the consent result, and the
// Connected Apps list from GET /api/me/connected-apps.

export type ConsentScope = {
  scope: string;
  description: string;
};

export type ConsentMetadata = {
  clientId: string;
  clientName: string;
  logoUrl: string | null;
  redirectUri: string;
  state: string | null;
  previouslyGranted: boolean;
  scopes: ConsentScope[];
};

export type ConsentResult = {
  redirectUri: string;
};

export type ConnectedApp = {
  clientId: string;
  name: string;
  logoUrl: string | null;
  scopes: ConsentScope[];
  grantedAt: string | null;
};

// Admin OAuth client management (ADR-0020). Mirrors the wire shapes of
// GET/POST /api/admin/oauth-clients (AdminOAuthClientController).

export type OAuthClientSummary = {
  clientId: string;
  name: string;
  logoUrl: string | null;
  redirectUris: string[];
  scopes: string[];
  confidential: boolean;
};

// The register response is the ONLY time clientSecret is ever returned — it's
// SHA-256 hashed server-side and never retrievable again. null for public
// (PKCE-only) clients.
export type OAuthClientRegistration = OAuthClientSummary & {
  clientSecret: string | null;
};

// The read-only scopes a third-party app can request. Mirrors the backend
// PlatformScope enum — keep in sync with
// backend/.../platform/PlatformScope.java (there is no catalog endpoint).
export const OAUTH_SCOPE_CATALOG: ConsentScope[] = [
  { scope: "profile:read", description: "Your name and height" },
  {
    scope: "workouts:read",
    description:
      "Your training programs, scheduled and completed workouts, and logged sets",
  },
  {
    scope: "nutrition:read",
    description: "Your food log, macros, and daily nutrition totals",
  },
  {
    scope: "medications:read",
    description: "Your medications, schedules, doses, and adherence",
  },
  {
    scope: "labs:read",
    description:
      "Your blood readings, DEXA scans, body composition, and daily health metrics",
  },
  {
    scope: "offline_access",
    description:
      "Keep monitoring your data in the background when you're not using the app",
  },
];
