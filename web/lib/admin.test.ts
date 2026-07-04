import { afterEach, describe, expect, it, vi } from "vitest";

// admin.ts imports auth/redirect at module load; stub them so the pure
// isAdminEmail predicate can be tested in isolation.
vi.mock("@/auth", () => ({ auth: vi.fn() }));
vi.mock("next/navigation", () => ({ redirect: vi.fn() }));

import { isAdminEmail } from "@/lib/admin";

describe("isAdminEmail", () => {
  afterEach(() => {
    delete process.env.ADMIN_EMAILS;
  });

  it("accepts a built-in admin email", () => {
    expect(isAdminEmail("evan.ruff@gmail.com")).toBe(true);
  });

  it("rejects a non-admin email", () => {
    expect(isAdminEmail("stranger@example.com")).toBe(false);
  });

  it("rejects empty / missing emails (fail closed)", () => {
    expect(isAdminEmail(null)).toBe(false);
    expect(isAdminEmail(undefined)).toBe(false);
    expect(isAdminEmail("")).toBe(false);
  });

  it("honours the ADMIN_EMAILS env allow-list", () => {
    process.env.ADMIN_EMAILS = "ops@team.com, lead@team.com";
    expect(isAdminEmail("ops@team.com")).toBe(true);
    expect(isAdminEmail("lead@team.com")).toBe(true);
    expect(isAdminEmail("other@team.com")).toBe(false);
  });
});
