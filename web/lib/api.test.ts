import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

// Mock the session source and next/navigation (pulled in via @/lib/admin).
const authMock = vi.fn();
vi.mock("@/auth", () => ({ auth: () => authMock() }));
vi.mock("next/navigation", () => ({ redirect: vi.fn() }));

// Mock next/headers cookies() — server-only, no request scope under vitest.
const cookieGet = vi.fn();
vi.mock("next/headers", () => ({
  cookies: () => Promise.resolve({ get: cookieGet }),
}));

import { apiFetch, ForbiddenError } from "@/lib/api";

describe("apiFetch admin guard", () => {
  beforeEach(() => {
    // BACKEND_URL is set globally in vitest.setup.ts (captured at module load).
    vi.stubGlobal("fetch", vi.fn(async () => new Response("{}", { status: 200 })));
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    authMock.mockReset();
    cookieGet.mockReset();
  });

  it("rejects a non-admin session on an /api/admin path without hitting the backend", async () => {
    authMock.mockResolvedValue({
      idToken: "tok",
      user: { email: "stranger@example.com" },
    });
    await expect(apiFetch("/api/admin/drugs")).rejects.toBeInstanceOf(ForbiddenError);
    expect(fetch).not.toHaveBeenCalled();
  });

  it("allows an admin session through to the backend", async () => {
    authMock.mockResolvedValue({
      idToken: "tok",
      user: { email: "evan.ruff@gmail.com" },
    });
    const res = await apiFetch("/api/admin/drugs");
    expect(res.status).toBe(200);
    expect(fetch).toHaveBeenCalledOnce();
  });

  it("does not gate non-admin paths", async () => {
    authMock.mockResolvedValue({
      idToken: "tok",
      user: { email: "stranger@example.com" },
    });
    await apiFetch("/api/me/blood");
    expect(fetch).toHaveBeenCalledOnce();
  });

  it("forwards the browser time zone from the tz cookie as X-Timezone", async () => {
    authMock.mockResolvedValue({ idToken: "tok", user: { email: "x@y.com" } });
    cookieGet.mockReturnValue({ value: encodeURIComponent("America/New_York") });
    await apiFetch("/api/me/medications/today");
    const headers = (vi.mocked(fetch).mock.calls[0]?.[1]?.headers ?? {}) as Record<string, string>;
    expect(headers["X-Timezone"]).toBe("America/New_York");
  });

  it("omits X-Timezone when no tz cookie is set", async () => {
    authMock.mockResolvedValue({ idToken: "tok", user: { email: "x@y.com" } });
    cookieGet.mockReturnValue(undefined);
    await apiFetch("/api/me/medications/today");
    const headers = (vi.mocked(fetch).mock.calls[0]?.[1]?.headers ?? {}) as Record<string, string>;
    expect(headers["X-Timezone"]).toBeUndefined();
  });
});
