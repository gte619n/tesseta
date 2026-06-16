"use client";

import Link from 'next/link';
import type { Route } from 'next';
import { usePathname, useSearchParams } from 'next/navigation';

// IMPL-20: "Review" is no longer its own route — it's the "Needs review" preset
// over the catalog. The tab links to the catalog with ?preset=needs-review and
// is highlighted by that search param rather than the pathname.
type Tab = { href: Route; label: string; preset: string | null };

const TABS: Tab[] = [
  {
    href: '/admin/exercises/catalog' as Route,
    label: 'Catalog',
    preset: null,
  },
  {
    href: '/admin/exercises/catalog?preset=needs-review' as Route,
    label: 'Needs review',
    preset: 'needs-review',
  },
];

export function ExerciseSubTabs({ reviewCount }: { reviewCount: number }) {
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const onCatalog = pathname?.startsWith('/admin/exercises/catalog') ?? false;
  const activePreset = searchParams.get('preset');

  return (
    <nav className="-mb-px flex items-center gap-6 text-sm">
      {TABS.map((t) => {
        const active = onCatalog && (activePreset ?? null) === t.preset;
        const label =
          t.preset === 'needs-review' ? `Needs review (${reviewCount})` : t.label;
        return (
          <Link
            key={t.href}
            href={t.href}
            className={`border-b-2 py-3 transition-colors ${
              active
                ? 'border-accent text-primary'
                : 'border-transparent text-tertiary hover:text-secondary'
            }`}
          >
            {label}
          </Link>
        );
      })}
    </nav>
  );
}
