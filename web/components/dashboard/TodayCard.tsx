import { today } from "@/lib/fixtures/dashboard";
import { Pill } from "./StatCard";
import { SectionTitle } from "./SectionTitle";

export function TodayCard({ compact = false }: { compact?: boolean }) {
  return (
    <div
      className={`rounded-[10px] border-[0.5px] border-border-default bg-surface ${
        compact ? "px-[15px] py-[13px]" : "px-[18px] py-4"
      }`}
    >
      <div className={`mb-[${compact ? "11" : "14"}px] flex items-center justify-between`}>
        <SectionTitle compact={compact}>Today</SectionTitle>
        <span className="caps-mono text-[9px] text-tertiary">{today.status}</span>
      </div>

      <div className="mb-[13px] flex items-center justify-between">
        <div>
          <div className="caps-mono mb-[3px] text-[9px] text-tertiary">Calories</div>
          <div
            className={`font-mono font-medium leading-none tracking-[-0.02em] text-primary tabular ${
              compact ? "text-[19px]" : "text-[22px]"
            }`}
          >
            {today.calories.current}
            <span
              className={`ml-1 font-normal text-tertiary ${
                compact ? "text-[10px]" : "text-[11px]"
              }`}
            >
              / {today.calories.target}
            </span>
          </div>
        </div>
        <CaloriesDonut pct={today.calories.pct} size={compact ? 38 : 44} />
      </div>

      <div className="grid grid-cols-3 gap-2 border-b-[0.5px] border-border-subtle pb-[13px]">
        {today.macros.map((m) => (
          <div key={m.label}>
            <div className="caps-mono mb-[3px] text-[9px] text-tertiary">
              {m.label}
            </div>
            <div
              className={`font-mono font-medium text-primary tabular ${
                compact ? "text-[12px]" : "text-[14px]"
              }`}
            >
              {m.value}
              <span
                className={`ml-0.5 font-normal text-tertiary ${
                  compact ? "text-[9px]" : "text-[10px]"
                }`}
              >
                {m.unit}
              </span>
            </div>
            <div className="mt-[5px] h-0.5 bg-canvas">
              <div
                className="h-full"
                style={{ width: `${m.pct}%`, background: m.color }}
              />
            </div>
          </div>
        ))}
      </div>

      <div className="flex items-center justify-between pt-3">
        <div>
          <div
            className={`font-medium text-primary ${
              compact ? "text-[11px]" : "text-[11px]"
            }`}
          >
            {today.workout.title}
          </div>
          <div
            className={`mt-0.5 font-mono text-tertiary tabular ${
              compact ? "text-[10px]" : "text-[11px]"
            }`}
          >
            {compact ? today.workout.metaPhone : today.workout.meta}
          </div>
        </div>
        <Pill tone="good">{today.workout.pill}</Pill>
      </div>
    </div>
  );
}

function CaloriesDonut({ pct, size }: { pct: number; size: number }) {
  // Dashoffset math from the mockup: 113.1 - (pct/100) * 113.1
  const circumference = 113.1;
  const offset = circumference * (1 - pct / 100);
  return (
    <svg width={size} height={size} viewBox="0 0 44 44">
      <circle cx="22" cy="22" r="18" fill="none" stroke="var(--color-canvas)" strokeWidth="4" />
      <circle
        cx="22"
        cy="22"
        r="18"
        fill="none"
        stroke="var(--color-accent)"
        strokeWidth="4"
        strokeDasharray={circumference}
        strokeDashoffset={offset}
        transform="rotate(-90 22 22)"
        strokeLinecap="round"
      />
      <text
        x="22"
        y="26"
        textAnchor="middle"
        fontSize={size <= 38 ? 9 : 10}
        fill="var(--color-primary)"
        fontFamily="var(--font-mono)"
        fontWeight={500}
      >
        {pct}%
      </text>
    </svg>
  );
}
