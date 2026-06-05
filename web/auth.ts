// Auth.js v5 configuration. Lives at the project root per Auth.js convention.
// Sessions use the JWT strategy (no DB). access_type=offline + prompt=consent
// guarantees Google returns a refresh_token on the first sign-in; the jwt
// callback rotates the id_token when it gets within 60s of expiry so users
// stay signed in indefinitely.
//
// IMPL-04: When a sign-in carries the Google Health API scope (incremental
// authorization, triggered by clicking "Connect Google Health" on the
// body-composition page), the JWT callback immediately forwards the
// refresh_token + access_token to the backend, which envelope-encrypts the
// refresh_token via KMS and stores it server-side. The tokens are NOT
// persisted in the session cookie — they live in the JWT callback's
// `account` object for milliseconds, then go to the backend, then are gone.
import NextAuth, { type DefaultSession } from "next-auth";
import Google from "next-auth/providers/google";
import Credentials from "next-auth/providers/credentials";
import type { JWT } from "next-auth/jwt";

// UAT / local end-to-end only. When UAT_AUTH_ENABLED=1 (set by
// infra/scripts/uat.sh) a "uat-dev" Credentials provider lets the Selenium
// suite sign in as an arbitrary test user with NO Google OAuth: it calls the
// backend's POST /api/auth/dev-login (itself gated by app.auth.dev-login-enabled)
// and stores the returned backend session token as the session idToken — the
// exact field the Google path populates — so the rest of the app is unchanged.
// The provider is simply absent in production builds where the flag is unset.
const UAT_AUTH_ENABLED = process.env.UAT_AUTH_ENABLED === "1";

const GOOGLE_HEALTH_SCOPE =
  "https://www.googleapis.com/auth/googlehealth.health_metrics_and_measurements.readonly";

declare module "next-auth" {
  interface Session {
    idToken?: string;
    error?: "RefreshAccessTokenError" | "GoogleHealthConnectError";
    user: DefaultSession["user"];
  }
}

declare module "next-auth/jwt" {
  interface JWT {
    idToken?: string;
    refreshToken?: string;
    expiresAt?: number;
    // Set on UAT dev sign-ins so the jwt callback skips Google token rotation
    // (the idToken is a backend session token, not a Google id_token).
    uat?: boolean;
    error?: "RefreshAccessTokenError" | "GoogleHealthConnectError";
  }
}

const GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token";

// UAT dev sign-in: exchange a test identity for a real backend session token.
// Returns null on any failure so signIn() surfaces a normal auth error.
async function uatDevLogin(
  userId: string,
  email: string,
  name: string,
): Promise<{ id: string; email: string; name: string; devToken: string } | null> {
  const backendUrl = process.env.BACKEND_URL;
  if (!backendUrl) return null;
  try {
    const res = await fetch(`${backendUrl.replace(/\/$/, "")}/api/auth/dev-login`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ userId, email, name }),
    });
    if (!res.ok) return null;
    const { accessToken } = (await res.json()) as { accessToken: string };
    if (!accessToken) return null;
    return { id: userId, email, name, devToken: accessToken };
  } catch {
    return null;
  }
}

async function refreshIdToken(token: JWT): Promise<JWT> {
  if (!token.refreshToken) {
    return { ...token, error: "RefreshAccessTokenError" };
  }
  try {
    const res = await fetch(GOOGLE_TOKEN_URL, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        client_id: process.env.AUTH_GOOGLE_ID!,
        client_secret: process.env.AUTH_GOOGLE_SECRET!,
        grant_type: "refresh_token",
        refresh_token: token.refreshToken,
      }),
    });
    if (!res.ok) {
      return { ...token, error: "RefreshAccessTokenError" };
    }
    const refreshed = (await res.json()) as {
      id_token?: string;
      expires_in?: number;
      refresh_token?: string;
    };
    return {
      ...token,
      idToken: refreshed.id_token ?? token.idToken,
      expiresAt: Math.floor(Date.now() / 1000) + (refreshed.expires_in ?? 3600),
      refreshToken: refreshed.refresh_token ?? token.refreshToken,
      error: undefined,
    };
  } catch {
    return { ...token, error: "RefreshAccessTokenError" };
  }
}

async function forwardGoogleHealthGrant(
  idToken: string,
  refreshToken: string,
  accessToken: string,
): Promise<boolean> {
  const backendUrl = process.env.BACKEND_URL;
  if (!backendUrl) return false;
  try {
    const res = await fetch(
      `${backendUrl.replace(/\/$/, "")}/api/me/google-health/connect`,
      {
        method: "POST",
        headers: {
          Authorization: `Bearer ${idToken}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({ refreshToken, accessToken }),
      },
    );
    return res.ok;
  } catch {
    return false;
  }
}

export const { handlers, auth, signIn, signOut } = NextAuth({
  providers: [
    Google({
      clientId: process.env.AUTH_GOOGLE_ID,
      clientSecret: process.env.AUTH_GOOGLE_SECRET,
      authorization: {
        params: {
          access_type: "offline",
          prompt: "consent",
          scope: "openid email profile",
        },
      },
    }),
    ...(UAT_AUTH_ENABLED
      ? [
          Credentials({
            id: "uat-dev",
            name: "UAT Dev",
            credentials: {
              userId: { label: "User ID" },
              email: { label: "Email" },
              name: { label: "Name" },
            },
            authorize: async (creds) => {
              const userId = String(creds?.userId ?? "").trim();
              if (!userId) return null;
              const email = String(creds?.email ?? `${userId}@uat.local`);
              const name = String(creds?.name ?? userId);
              return uatDevLogin(userId, email, name);
            },
          }),
        ]
      : []),
  ],
  session: {
    strategy: "jwt",
    maxAge: 60 * 60 * 24 * 365,
    updateAge: 60 * 60 * 24,
  },
  pages: {
    signIn: "/auth/signin",
  },
  callbacks: {
    async jwt({ token, account, user }) {
      // UAT dev sign-in: the credentials authorize() returned the backend
      // session token. Store it as idToken (the field apiFetch reads) and mark
      // the token `uat` so we never try to Google-rotate it below.
      if (account?.provider === "uat-dev" && user) {
        return {
          ...token,
          idToken: (user as { devToken?: string }).devToken,
          uat: true,
          error: undefined,
        };
      }
      // A previously-issued UAT token: return as-is (backend session tokens are
      // not refreshable via Google; UAT runs are far shorter than their TTL).
      if (token.uat) {
        return token;
      }
      if (account) {
        // If this sign-in granted the Google Health scope, immediately
        // forward the refresh + access tokens to the backend so it can
        // encrypt + persist them. We do NOT store them in the JWT cookie.
        const grantedScopes = account.scope ?? "";
        if (
          grantedScopes.includes(GOOGLE_HEALTH_SCOPE) &&
          account.id_token &&
          account.refresh_token &&
          account.access_token
        ) {
          const ok = await forwardGoogleHealthGrant(
            account.id_token,
            account.refresh_token,
            account.access_token,
          );
          if (!ok) {
            return {
              ...token,
              idToken: account.id_token,
              refreshToken: account.refresh_token,
              expiresAt: account.expires_at,
              error: "GoogleHealthConnectError",
            };
          }
        }
        return {
          ...token,
          idToken: account.id_token,
          refreshToken: account.refresh_token,
          expiresAt: account.expires_at,
          error: undefined,
        };
      }
      if (token.expiresAt && Date.now() / 1000 < token.expiresAt - 60) {
        return token;
      }
      return refreshIdToken(token);
    },
    async session({ session, token }) {
      session.idToken = token.idToken;
      session.error = token.error;
      return session;
    },
  },
});
