// Extends Vitest's expect with jest-dom matchers (toBeInTheDocument, etc.)
// for Testing Library component tests.
import "@testing-library/jest-dom/vitest";

// lib/api.ts captures BACKEND_URL at module load, so it must be present before
// any test imports it. A dummy value is fine — network calls are mocked.
process.env.BACKEND_URL ||= "http://backend.test";
