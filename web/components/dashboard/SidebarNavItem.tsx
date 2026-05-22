"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import type { Route } from "next";

type Props = {
  label: string;
  icon: string;
  href: string;
  badge?: string;
};

export function SidebarNavItem({ label, icon, href, badge }: Props) {
  const pathname = usePathname();
  // Active when we're exactly on this route OR (for non-root hrefs) on
  // any descendant. Keeps the Dashboard tile from lighting up on every
  // sub-route.
  const active =
    href === "/" ? pathname === "/" : pathname?.startsWith(href) ?? false;
  return (
    <Link
      href={href as Route}
      aria-current={active ? "page" : undefined}
      className={`mb-0.5 flex w-full items-center justify-between gap-[11px] rounded-md px-[11px] py-[9px] text-left ${
        active ? "bg-accent-bg" : ""
      }`}
    >
      <span className="flex items-center gap-[11px]">
        <i
          className={`ti ti-${icon} text-[16px] ${
            active ? "text-accent-dim" : "text-tertiary"
          }`}
          aria-hidden
        />
        <span
          className={`text-[13px] ${
            active ? "font-medium text-accent-dim" : "font-normal text-secondary"
          }`}
        >
          {label}
        </span>
      </span>
      {badge ? (
        <span className="caps-mono rounded-[3px] bg-warn-bg px-1.5 py-px text-[9px] tracking-[0.06em] text-[#7C3F0F]">
          {badge}
        </span>
      ) : null}
    </Link>
  );
}
