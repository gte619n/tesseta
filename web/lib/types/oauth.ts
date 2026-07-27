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
