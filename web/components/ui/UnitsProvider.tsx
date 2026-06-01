"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useState,
} from "react";
import {
  DEFAULT_UNIT_PREFERENCES,
  type HeightUnit,
  type TemperatureUnit,
  type UnitPreferences,
  type WeightUnit,
} from "@/lib/units";

const STORAGE_KEY = "hf-units";

type UnitsContextValue = {
  prefs: UnitPreferences;
  setWeightUnit: (unit: WeightUnit) => void;
  setHeightUnit: (unit: HeightUnit) => void;
  setTemperatureUnit: (unit: TemperatureUnit) => void;
};

const UnitsContext = createContext<UnitsContextValue | null>(null);

function parseStored(raw: string | null): UnitPreferences {
  if (!raw) return DEFAULT_UNIT_PREFERENCES;
  try {
    const parsed = JSON.parse(raw) as Partial<UnitPreferences>;
    return {
      weight: parsed.weight === "KG" ? "KG" : "LB",
      height: parsed.height === "CM" ? "CM" : "FT_IN",
      temperature: parsed.temperature === "C" ? "C" : "F",
    };
  } catch {
    return DEFAULT_UNIT_PREFERENCES;
  }
}

function persist(next: UnitPreferences) {
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(next));
  } catch {
    // Ignore quota/availability errors; in-memory state still updates.
  }
}

export function UnitsProvider({ children }: { children: React.ReactNode }) {
  // SSR-safe: first render always matches the server (defaults). We read
  // localStorage only in an effect to avoid hydration mismatches.
  const [prefs, setPrefs] = useState<UnitPreferences>(DEFAULT_UNIT_PREFERENCES);

  useEffect(() => {
    setPrefs(parseStored(window.localStorage.getItem(STORAGE_KEY)));
  }, []);

  const setWeightUnit = useCallback((weight: WeightUnit) => {
    setPrefs((prev) => {
      const next = { ...prev, weight };
      persist(next);
      return next;
    });
  }, []);

  const setHeightUnit = useCallback((height: HeightUnit) => {
    setPrefs((prev) => {
      const next = { ...prev, height };
      persist(next);
      return next;
    });
  }, []);

  const setTemperatureUnit = useCallback((temperature: TemperatureUnit) => {
    setPrefs((prev) => {
      const next = { ...prev, temperature };
      persist(next);
      return next;
    });
  }, []);

  return (
    <UnitsContext.Provider
      value={{ prefs, setWeightUnit, setHeightUnit, setTemperatureUnit }}
    >
      {children}
    </UnitsContext.Provider>
  );
}

export function useUnits(): UnitsContextValue {
  const ctx = useContext(UnitsContext);
  if (!ctx) {
    throw new Error("useUnits must be used within a UnitsProvider");
  }
  return ctx;
}
