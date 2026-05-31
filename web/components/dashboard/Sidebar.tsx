import Link from "next/link";
import type { Route } from "next";
import { TessetaMark } from "@/components/brand/TessetaMark";
import { navItems } from "@/lib/fixtures/dashboard";
import type { Device, DeviceSyncStatus } from "@/lib/device-api";
import { SidebarNavItem } from "@/components/dashboard/SidebarNavItem";

export type SidebarUser = {
  name: string;
  email: string | null;
  initials: string;
  image: string | null;
};

// Traffic-light dot colors. GREEN = synced in the last day, YELLOW =
// within a week, RED = stale or never. Maps to the same tokens the rest of
// the dashboard uses for good/warn/alert.
const STATUS_DOT: Record<DeviceSyncStatus, string> = {
  GREEN: "bg-good",
  YELLOW: "bg-warn",
  RED: "bg-alert",
};

const STATUS_LABEL: Record<DeviceSyncStatus, string> = {
  GREEN: "Synced",
  YELLOW: "Sync delayed",
  RED: "Not syncing",
};

export function Sidebar({
  user,
  isAdmin = false,
  devices = [],
}: {
  user: SidebarUser;
  isAdmin?: boolean;
  devices?: Device[];
}) {
  return (
    <aside className="flex min-h-[880px] flex-col border-r-[0.5px] border-border-strong bg-canvas-muted px-3.5 py-[22px]">
      <div className="mb-2.5 flex items-center gap-[11px] border-b-[0.5px] border-border-strong px-1.5 pb-[18px]">
        <TessetaMark variant="dark" size={32} className="rounded-[7px]" />
        <div>
          <div className="text-[13px] font-medium leading-[1.1] tracking-[-0.01em] text-primary">
            tesseta
          </div>
          <div className="mt-0.5 caps-mono text-[9px] text-tertiary">v0.1.0</div>
        </div>
      </div>

      <NavSectionLabel>Main</NavSectionLabel>
      {navItems.map((item) => (
        <SidebarNavItem key={item.label} {...item} />
      ))}

      <div className="pt-[18px]" />
      <NavSectionLabel>Devices</NavSectionLabel>
      {devices.length === 0 ? (
        <div className="px-[11px] py-[9px] text-[12px] font-normal text-tertiary">
          No devices syncing yet
        </div>
      ) : (
        devices.map((d) => (
          <div
            key={d.id}
            className="flex items-center gap-[11px] px-[11px] py-[9px]"
            title={`${STATUS_LABEL[d.status]}${
              d.lastSyncedAt ? ` · last sync ${formatRelative(d.lastSyncedAt)}` : ""
            }`}
          >
            <span
              className={`h-1.5 w-1.5 shrink-0 rounded-full ${STATUS_DOT[d.status]}`}
              aria-hidden
            />
            <span className="flex-1 truncate text-[12px] font-normal text-secondary">
              {d.name}
            </span>
            {d.lastSyncedAt ? (
              <span className="caps-mono text-[9px] tracking-[0.04em] text-tertiary">
                {formatRelative(d.lastSyncedAt)}
              </span>
            ) : null}
          </div>
        ))
      )}

      <div className="mt-auto border-t-[0.5px] border-border-strong pt-3.5">
        {isAdmin ? (
          <Link
            href={"/admin" as Route}
            className="mb-0.5 flex w-full items-center gap-[11px] rounded-md bg-transparent px-[11px] py-[9px] text-left hover:bg-canvas-muted"
          >
            <i className="ti ti-shield-lock text-[16px] text-tertiary" aria-hidden />
            <span className="text-[13px] font-normal text-secondary">Administration</span>
          </Link>
        ) : null}
        <Link
          href="/me/profile"
          className="mb-0.5 flex w-full items-center gap-[11px] rounded-md bg-transparent px-[11px] py-[9px] text-left hover:bg-canvas-muted"
        >
          <i className="ti ti-settings text-[16px] text-tertiary" aria-hidden />
          <span className="text-[13px] font-normal text-secondary">Settings</span>
        </Link>
        <div className="flex w-full items-center gap-2.5 rounded-lg border-[0.5px] border-border-strong bg-surface px-[11px] py-[7px]">
          {user.image ? (
            // eslint-disable-next-line @next/next/no-img-element -- Google avatar
            // URLs are arbitrary remote hosts; a plain <img> avoids configuring
            // next/image remotePatterns (matches the rest of the codebase).
            <img
              src={user.image}
              alt=""
              className="h-[26px] w-[26px] shrink-0 rounded-md object-cover"
            />
          ) : (
            <div className="flex h-[26px] w-[26px] shrink-0 items-center justify-center rounded-md bg-primary text-[11px] font-medium text-inverse">
              {user.initials}
            </div>
          )}
          <div className="min-w-0 flex-1 text-left">
            <div className="truncate text-[12px] font-medium leading-[1.1] text-primary">
              {user.name}
            </div>
            <div className="mt-px truncate caps-mono text-[9px] tracking-[0.04em] text-tertiary">
              {user.email ?? ""}
            </div>
          </div>
        </div>
      </div>
    </aside>
  );
}

function NavSectionLabel({ children }: { children: React.ReactNode }) {
  return (
    <div className="px-1.5 pb-1.5 pt-2.5">
      <div className="caps-mono text-[9px] tracking-[0.1em] text-tertiary">
        {children}
      </div>
    </div>
  );
}

// Compact relative-time label ("3h", "2d") for the last sync timestamp.
function formatRelative(iso: string): string {
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return "";
  const diffSec = Math.max(0, Math.round((Date.now() - then) / 1000));
  if (diffSec < 60) return `${diffSec}s`;
  const min = Math.round(diffSec / 60);
  if (min < 60) return `${min}m`;
  const hr = Math.round(min / 60);
  if (hr < 48) return `${hr}h`;
  const days = Math.round(hr / 24);
  return `${days}d`;
}
