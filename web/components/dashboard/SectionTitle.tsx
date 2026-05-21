export function SectionTitle({
  children,
  compact = false,
}: {
  children: React.ReactNode;
  compact?: boolean;
}) {
  return (
    <div className="flex items-center gap-2.5">
      <span
        aria-hidden
        className={`inline-block w-[3px] rounded-[2px] bg-accent ${
          compact ? "h-[11px]" : "h-3.5"
        }`}
      />
      <span
        className={`font-medium tracking-[-0.01em] text-primary ${
          compact ? "text-[12px]" : "text-[14px]"
        }`}
      >
        {children}
      </span>
    </div>
  );
}
