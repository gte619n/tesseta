import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

// Mock the session source and next/navigation (pulled in via @/lib/admin).
const authMock = vi.fn();
vi.mock("@/auth", () => ({ auth: () => authMock() }));
vi.mock("next/navigation", () => ({ redirect: vi.fn() }));

import { apiFetch, ForbiddenError } from "@/lib/api";

describe("apiFetch admin guard", () => {
  beforeEach(() => {
    // BACKEND_URL is set globally in vitest.setup.ts (captured at module load).
    vi.stubGlobal("fetch", vi.fn(async () => new Response("{}", { status: 200 })));
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    authMock.mockReset();
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
});
