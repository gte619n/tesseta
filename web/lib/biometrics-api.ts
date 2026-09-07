import { cache } from "react";
import { apiJson } from "@/lib/api";

// One metric's row in the biometrics settings section (mirrors the backend
// BiometricSummary). latestValue is in the canonical `unit`; the client formats
// it for the user's unit preference. avgIntervalDays is "one every N days" over
// the last 60 days (null when there are no readings).
export type BiometricSummary = {
  key: string;
  label: string;
  unit: string;
  visible: boolean;
  latestValue: number | null;
  latestAt: string | null;
  observationsLast60d: number;
  avgIntervalDays: number | null;
};

export async function fetchBiometrics(): Promise<BiometricSummary[]> {
  return apiJson<BiometricSummary[]>("/api/me/biometrics");
}

// The set of metric keys the user has hidden, for the dashboard to drop cards.
// Cached per render so the several dashboard sections share one /api/me read.
export const loadHiddenBiometrics = cache(async (): Promise<Set<string>> => {
  try {
    const me = await apiJson<{ hiddenBiometrics?: string[] }>("/api/me");
    return new Set(me.hiddenBiometrics ?? []);
  } catch {
    return new Set();
  }
});
