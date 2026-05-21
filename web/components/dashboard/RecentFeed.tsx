import type { LogEntry } from "@/lib/fixtures/dashboard";
import { SectionTitle } from "./SectionTitle";

type Variant = "desktop" | "foldable" | "phone";

export function RecentFeed({
  entries,
  variant = "desktop",
  showViewAll = true,
}: {
  entries: LogEntry[];
  variant?: Variant;
  showViewAll?: boolean;
}) {
  const compact = variant !== "desktop";
  return (
    <div
      className={`rounded-[10px] border-[0.5px] border-border-default bg-surface ${
        variant === "phone"
          ? "px-[15px] py-[13px]"
          : variant === "foldable"
            ? "px-[15px] py-3"
            : "px-[18px] py-3.5"
      }`}
    >
      <div className={`mb-${compact ? "2" : "2.5"} flex items-center justify-between`}>
        <SectionTitle compact={compact}>Recent</SectionTitle>
        {showViewAll ? (
          variant === "phone" ? (
            <span className="caps-mono text-[9px] text-tertiary">View all</span>
          ) : (
            <a
              href="#"
              className="caps-mono flex items-center gap-1 text-[10px] tracking-[0.06em] text-tertiary no-underline"
            >
              View all <i className="ti ti-arrow-right text-[11px]" aria-hidden />
            </a>
          )
        ) : null}
      </div>
      <ul className="m-0 list-none p-0">
        {entries.map((e, i) => (
          <li
            key={`${e.title}-${e.time}`}
            className={`flex items-center gap-3 py-2 ${
              i === entries.length - 1
                ? ""
                : "border-b-[0.5px] border-border-subtle"
            }`}
          >
            <div
              className={`flex shrink-0 items-center justify-center rounded-[6px] ${
                variant === "foldable" ? "h-6 w-6" : "h-[26px] w-[26px]"
              } ${e.tone === "activity" ? "bg-good-bg text-accent-dim" : "bg-canvas text-secondary"}`}
            >
              <i className={`ti ti-${e.icon} text-[13px]`} aria-hidden />
            </div>
            <div className="min-w-0 flex-1">
              <div className={`font-medium text-primary ${compact ? "text-[11px]" : "text-[12px]"}`}>
                {variant === "foldable" && e.metaFoldable ? e.metaFoldable : e.title}
              </div>
              {variant !== "foldable" && (e.meta || e.metaPhone) ? (
                <div className="font-mono text-[10px] text-tertiary tabular">
                  {variant === "phone" && e.metaPhone ? e.metaPhone : e.meta}
                </div>
              ) : null}
            </div>
            <div
              className={`text-right font-mono text-tertiary tabular ${
                variant === "foldable" ? "text-[10px]" : "text-[11px]"
              }`}
            >
              {e.time}
            </div>
          </li>
        ))}
      </ul>
    </div>
  );
}
