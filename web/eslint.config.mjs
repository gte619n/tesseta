// Next.js 16 ships `eslint-config-next` as native ESLint flat config arrays.
// Spread them directly — the old FlatCompat().extends(...) wrapper breaks under
// v16 ("Converting circular structure to JSON").
import nextCoreWebVitals from "eslint-config-next/core-web-vitals";
import nextTypescript from "eslint-config-next/typescript";

const config = [
  ...nextCoreWebVitals,
  ...nextTypescript,
  {
    // eslint-config-next 16 bundles eslint-plugin-react-hooks v6, which adds
    // several new rules that flag PRE-EXISTING (Next 15-era) patterns across the
    // app — not regressions introduced by the migration. Rather than block the
    // Next 16 upgrade on a 38-file refactor, these are downgraded error → warn so
    // they stay visible and can be burned down as a follow-up (then re-escalated
    // to error). See docs/plans/IMPL-DEADLINE-MIG-decision-log.md.
    rules: {
      "react-hooks/set-state-in-effect": "warn",
      "react-hooks/refs": "warn",
      "react-hooks/purity": "warn",
      "react-hooks/immutability": "warn",
    },
  },
];

export default config;
