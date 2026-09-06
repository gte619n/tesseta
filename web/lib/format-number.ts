// Shared display formatting for numeric values. Values are grouped with
// thousands separators (e.g. 1409 -> "1,409") so large numbers read cleanly
// across the app. The decimal is dropped for whole numbers and otherwise kept
// up to `maxDecimals` places (trailing zeros trimmed).
//
// Use these for display only — grouped strings do not round-trip through
// number parsing, so never feed them back into editable inputs.

export function formatNumber(value: number, maxDecimals = 1): string {
  return value.toLocaleString("en-US", { maximumFractionDigits: maxDecimals });
}

// Grouped whole-number display, e.g. 2450.4 -> "2,450".
export function formatWholeNumber(value: number): string {
  return formatNumber(value, 0);
}
