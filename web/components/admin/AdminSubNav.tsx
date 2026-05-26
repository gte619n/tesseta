"use client";

import Link from 'next/link';
import type { Route } from 'next';
import { usePathname } from 'next/navigation';

const SECTIONS: { href: Route; label: string; icon: string }[] = [
  { href: '/admin' as Route, label: 'Overview', icon: 'layout-dashboard' },
  { href: '/admin/equipment' as Route, label: 'Equipment', icon: 'barbell' },
  { href: '/admin/drugs' as Route, label: 'Drugs', icon: 'pill' },
];

export function AdminSubNav() {
  const pathname = usePathname();
  return (
    <nav className="flex gap-1 -mb-px">
      {SECTIONS.map((s) => {
        const active =
          s.href === '/admin' ? pathname === '/admin' : pathname?.startsWith(s.href as string);
        return (
          <Link
            key={s.href}
            href={s.href}
            className={`flex items-center gap-2 border-b-2 px-3 py-2.5 text-sm transition-colors ${
              active
                ? 'border-accent text-primary'
                : 'border-transparent text-tertiary hover:text-secondary'
            }`}
          >
            <i className={`ti ti-${s.icon} text-[14px]`} aria-hidden />
            {s.label}
          </Link>
        );
      })}
    </nav>
  );
}
