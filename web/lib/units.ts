// Pure, dependency-free unit conversions + formatters.
//
// Canonical raw values used across the app:
//   - Weight: pounds (lb) — the server converts backend kg to lb.
//   - Height: centimeters (cm) — backend stores heightCm.
//   - Temperature: Fahrenheit (°F).
//
// Defaults preserve today's rendering (lb / ft-in / °F) so nothing
// changes visually until the user toggles a preference.

export type WeightUnit = "LB" | "KG";
export type HeightUnit = "FT_IN" | "CM";
export type TemperatureUnit = "F" | "C";

export interface UnitPreferences {
  weight: WeightUnit;
  height: HeightUnit;
  temperature: TemperatureUnit;
}

export const DEFAULT_UNIT_PREFERENCES: UnitPreferences = {
  weight: "LB",
  height: "FT_IN",
  temperature: "F",
};

const LB_PER_KG = 2.20462;
const CM_PER_INCH = 2.54;
const INCHES_PER_FOOT = 12;

// ---- Weight -------------------------------------------------------------

export function lbToKg(lb: number): number {
  return lb / LB_PER_KG;
}

export function kgToLb(kg: number): number {
  return kg * LB_PER_KG;
}

export function weightUnitLabel(unit: WeightUnit): string {
  return unit === "KG" ? "kg" : "lb";
}

// Converts a canonical lb value into the chosen unit's numeric value.
export function weightValue(lb: number, unit: WeightUnit): number {
  return unit === "KG" ? lbToKg(lb) : lb;
}

export interface FormatWeightOpts {
  // Drop the unit suffix and return just the rounded number string.
  withUnit?: boolean;
  decimals?: number;
}

// "189.2 lb" | "85.8 kg" (1 decimal by default).
export function formatWeight(
  lb: number,
  unit: WeightUnit,
  opts: FormatWeightOpts = {},
): string {
  const { withUnit = true, decimals = 1 } = opts;
  const num = weightValue(lb, unit).toFixed(decimals);
  return withUnit ? `${num} ${weightUnitLabel(unit)}` : num;
}

// ---- Height -------------------------------------------------------------

export function cmToFtIn(cm: number): { ft: number; in: number } {
  const totalIn = cm / CM_PER_INCH;
  let ft = Math.floor(totalIn / INCHES_PER_FOOT);
  let inches = Math.round(totalIn - ft * INCHES_PER_FOOT);
  // Carry 12" up to a foot so we never render e.g. 5 ft 12 in.
  if (inches === INCHES_PER_FOOT) {
    ft += 1;
    inches = 0;
  }
  return { ft, in: inches };
}

export function ftInToCm(ft: number, inches: number): number {
  return Math.round((ft * INCHES_PER_FOOT + inches) * CM_PER_INCH);
}

export function heightUnitLabel(unit: HeightUnit): string {
  return unit === "CM" ? "cm" : "ft / in";
}

// "6 ft 2 in" | "188 cm".
export function formatHeight(cm: number, unit: HeightUnit): string {
  if (unit === "CM") return `${Math.round(cm)} cm`;
  const { ft, in: inches } = cmToFtIn(cm);
  return `${ft} ft ${inches} in`;
}

// ---- Temperature --------------------------------------------------------

export function fToC(f: number): number {
  return ((f - 32) * 5) / 9;
}

export function cToF(c: number): number {
  return (c * 9) / 5 + 32;
}

export function temperatureUnitLabel(unit: TemperatureUnit): string {
  return unit === "C" ? "°C" : "°F";
}

// "98.6 °F" | "37.0 °C" (1 decimal).
export function formatTemperature(f: number, unit: TemperatureUnit): string {
  const value = unit === "C" ? fToC(f) : f;
  return `${value.toFixed(1)} ${temperatureUnitLabel(unit)}`;
}
