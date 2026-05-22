import { devices, navItems } from "@/lib/fixtures/dashboard";

export type SidebarUser = {
  name: string;
  email: string | null;
  initials: string;
};

export function Sidebar({ user }: { user: SidebarUser }) {
  return (
    <aside className="flex min-h-[880px] flex-col border-r-[0.5px] border-border-strong bg-canvas-muted px-3.5 py-[22px]">
      <div className="mb-2.5 flex items-center gap-[11px] border-b-[0.5px] border-border-strong px-1.5 pb-[18px]">
        <div className="flex h-8 w-8 items-center justify-center rounded-md bg-primary font-mono text-[12px] font-medium tracking-[-0.02em] text-inverse">
          HF
        </div>
        <div>
          <div className="text-[13px] font-medium leading-[1.1] tracking-[-0.01em] text-primary">
            Health · Fitness
          </div>
          <div className="mt-0.5 caps-mono text-[9px] text-tertiary">v0.1.0</div>
        </div>
      </div>

      <NavSectionLabel>Main</NavSectionLabel>
      {navItems.map((item) => (
        <NavItem key={item.label} {...item} />
      ))}

      <div className="pt-[18px]" />
      <NavSectionLabel>Devices</NavSectionLabel>
      {devices.map((d) => (
        <div key={d.name} className="flex items-center gap-[11px] px-[11px] py-[9px]">
          <span
            className={`h-1.5 w-1.5 shrink-0 rounded-full ${
              d.connected ? "bg-accent" : "bg-quaternary"
            }`}
            aria-hidden
          />
          <span className="text-[12px] font-normal text-secondary">{d.name}</span>
        </div>
      ))}

      <div className="mt-auto border-t-[0.5px] border-border-strong pt-3.5">
        <button
          type="button"
          className="mb-0.5 flex w-full items-center gap-[11px] rounded-md bg-transparent px-[11px] py-[9px] text-left"
        >
          <i className="ti ti-settings text-[16px] text-tertiary" aria-hidden />
          <span className="text-[13px] font-normal text-secondary">Settings</span>
        </button>
        <button
          type="button"
          className="flex w-full items-center gap-2.5 rounded-lg border-[0.5px] border-border-strong bg-surface px-[11px] py-[7px]"
        >
          <div className="flex h-[26px] w-[26px] shrink-0 items-center justify-center rounded-md bg-primary text-[11px] font-medium text-inverse">
            {user.initials}
          </div>
          <div className="min-w-0 flex-1 text-left">
            <div className="truncate text-[12px] font-medium leading-[1.1] text-primary">
              {user.name}
            </div>
            <div className="mt-px truncate caps-mono text-[9px] tracking-[0.04em] text-tertiary">
              {user.email ?? ""}
            </div>
          </div>
          <i className="ti ti-selector text-[14px] text-tertiary" aria-hidden />
        </button>
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

type NavItemProps = {
  label: string;
  icon: string;
  active?: boolean;
  badge?: string;
};

function NavItem({ label, icon, active, badge }: NavItemProps) {
  return (
    <button
      type="button"
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
    </button>
  );
}
