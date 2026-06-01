import type { Metadata } from "next";

/**
 * Page-title helpers. The root layout (`app/layout.tsx`) owns the title
 * template — `{ default: "tesseta", template: "tesseta: %s" }` — so pages only
 * declare their leaf title and Next renders e.g. `tesseta: Goals`.
 *
 * Every `page.tsx` MUST export either `metadata` (static) or `generateMetadata`
 * (dynamic). `scripts/check-page-titles.mjs` enforces this in CI so navigating
 * to a new page always updates the document title.
 */

/** Static page title. Pass `description` to override the layout default. */
export function pageMetadata(title: string, description?: string): Metadata {
  return { title, ...(description ? { description } : {}) };
}

/** Title that bypasses the `tesseta: %s` template (used by the landing page). */
export function absoluteTitle(title: string): Metadata {
  return { title: { absolute: title } };
}

/**
 * Reusable `generateMetadata` for dynamic entity routes: fetch the entity from
 * the route params and derive its title. On any error (e.g. 404) it returns
 * empty metadata so the title falls back to the layout default rather than
 * throwing during metadata resolution.
 *
 * @example
 *   export const generateMetadata = entityMetadata(
 *     ({ id }: { id: string }) => getGoalDeep(id),
 *     (goal) => goal.title,
 *   );
 */
export function entityMetadata<P extends Record<string, string>, T>(
  fetcher: (params: P) => Promise<T>,
  toTitle: (entity: T) => string,
): (props: { params: Promise<P> }) => Promise<Metadata> {
  return async function generateMetadata(props): Promise<Metadata> {
    try {
      const params = await props.params;
      return { title: toTitle(await fetcher(params)) };
    } catch {
      return {};
    }
  };
}
