"use client";

import { useEffect, useRef, useState } from 'react';
import type { EquipmentRequirement } from '@/lib/types/exercise';
import type { Equipment } from '@/lib/types/gym';

interface Props {
  value: EquipmentRequirement[];
  onChange: (value: EquipmentRequirement[]) => void;
  // Server action that searches the global Equipment catalog (GET /api/equipment).
  searchEquipment: (search: string) => Promise<Equipment[]>;
  // Resolved names for equipmentIds already on the exercise, so existing groups
  // render labels rather than raw ids. Optional; falls back to the id.
  initialNames?: Record<string, string>;
}

// Builds the exercise's equipment requirement as a list of "any-of" groups.
// Each group is satisfied when a gym has at least one of its members; the
// exercise is executable when every group is satisfied. Zero groups = bodyweight.
export function EquipmentRequirementPicker({
  value,
  onChange,
  searchEquipment,
  initialNames,
}: Props) {
  const [names, setNames] = useState<Record<string, string>>(initialNames ?? {});

  function rememberNames(items: Equipment[]) {
    setNames((prev) => {
      const next = { ...prev };
      for (const it of items) next[it.equipmentId] = it.name;
      return next;
    });
  }

  function addGroup() {
    onChange([...value, { anyOf: [] }]);
  }

  function removeGroup(idx: number) {
    onChange(value.filter((_, i) => i !== idx));
  }

  function addToGroup(idx: number, item: Equipment) {
    rememberNames([item]);
    onChange(
      value.map((g, i) =>
        i === idx
          ? g.anyOf.includes(item.equipmentId)
            ? g
            : { anyOf: [...g.anyOf, item.equipmentId] }
          : g,
      ),
    );
  }

  function removeFromGroup(idx: number, equipmentId: string) {
    onChange(
      value.map((g, i) =>
        i === idx ? { anyOf: g.anyOf.filter((id) => id !== equipmentId) } : g,
      ),
    );
  }

  return (
    <div className="space-y-3">
      {value.length === 0 ? (
        <p className="text-xs text-tertiary">
          No requirements — this exercise is bodyweight and runs at every gym.
        </p>
      ) : null}

      {value.map((group, idx) => (
        <div key={idx} className="rounded-md border border-border-default bg-canvas p-3">
          <div className="mb-2 flex items-center justify-between">
            <span className="caps-mono text-[9px] tracking-[0.06em] text-tertiary">
              Group {idx + 1} · any of
            </span>
            <button
              type="button"
              onClick={() => removeGroup(idx)}
              aria-label="Remove group"
              className="cursor-pointer rounded px-1.5 text-[14px] leading-none text-tertiary hover:text-alert"
            >
              ×
            </button>
          </div>

          <div className="mb-2 flex flex-wrap gap-1.5">
            {group.anyOf.length === 0 ? (
              <span className="text-[11px] text-tertiary">Empty — add equipment below.</span>
            ) : (
              group.anyOf.map((id) => (
                <span
                  key={id}
                  className="inline-flex items-center gap-1 rounded-full border border-border-default bg-surface px-2 py-0.5 text-[11px] text-primary"
                >
                  {names[id] ?? id}
                  <button
                    type="button"
                    onClick={() => removeFromGroup(idx, id)}
                    aria-label={`Remove ${names[id] ?? id}`}
                    className="cursor-pointer text-tertiary hover:text-alert"
                  >
                    ×
                  </button>
                </span>
              ))
            )}
          </div>

          <EquipmentSearchBox
            searchEquipment={searchEquipment}
            onPick={(item) => addToGroup(idx, item)}
            excludeIds={group.anyOf}
          />
        </div>
      ))}

      <button
        type="button"
        onClick={addGroup}
        className="caps-mono w-full cursor-pointer rounded-md border-[0.5px] border-dashed border-border-default px-3 py-2 text-[10px] tracking-[0.06em] text-tertiary hover:border-accent hover:text-secondary"
      >
        + Add requirement group
      </button>
    </div>
  );
}

function EquipmentSearchBox({
  searchEquipment,
  onPick,
  excludeIds,
}: {
  searchEquipment: (search: string) => Promise<Equipment[]>;
  onPick: (item: Equipment) => void;
  excludeIds: string[];
}) {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<Equipment[]>([]);
  const [open, setOpen] = useState(false);
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    if (debounceRef.current) clearTimeout(debounceRef.current);
    const q = query.trim();
    if (!q) {
      setResults([]);
      return;
    }
    debounceRef.current = setTimeout(() => {
      void searchEquipment(q)
        .then((items) => {
          setResults(items.slice(0, 20));
          setOpen(true);
        })
        .catch(() => setResults([]));
    }, 250);
    return () => {
      if (debounceRef.current) clearTimeout(debounceRef.current);
    };
  }, [query, searchEquipment]);

  const filtered = results.filter((r) => !excludeIds.includes(r.equipmentId));

  return (
    <div className="relative">
      <input
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        onFocus={() => {
          if (results.length > 0) setOpen(true);
        }}
        onBlur={() => setTimeout(() => setOpen(false), 150)}
        placeholder="Search equipment…"
        className="w-full rounded-md border border-border-default bg-surface px-2.5 py-1.5 text-xs text-primary focus:outline-none focus:ring-2 focus:ring-accent"
      />
      {open && filtered.length > 0 ? (
        <ul className="absolute z-10 mt-1 max-h-56 w-full overflow-y-auto rounded-md border border-border-default bg-surface py-1 shadow-lg">
          {filtered.map((item) => (
            <li key={item.equipmentId}>
              <button
                type="button"
                onMouseDown={(e) => {
                  e.preventDefault();
                  onPick(item);
                  setQuery('');
                  setResults([]);
                  setOpen(false);
                }}
                className="flex w-full items-center gap-2 px-2.5 py-1.5 text-left text-xs text-primary hover:bg-canvas-muted"
              >
                <span className="flex-1 truncate">{item.name}</span>
                <span className="truncate text-[10px] text-tertiary">{item.subcategory}</span>
              </button>
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}
