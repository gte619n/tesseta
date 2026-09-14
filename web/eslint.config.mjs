// Next.js 16 ships `eslint-config-next` as native ESLint flat config arrays.
// Spread them directly — the old FlatCompat().extends(...) wrapper breaks under
// v16 ("Converting circular structure to JSON").
import nextCoreWebVitals from "eslint-config-next/core-web-vitals";
import nextTypescript from "eslint-config-next/typescript";

const config = [
  ...nextCoreWebVitals,
  ...nextTypescript,
];

export default config;
