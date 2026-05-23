"use client";

import { useState } from "react";
import { BloodReportActions } from "./BloodReportActions";

type ExtractedMarker = {
  name: string;
  value: number | null;
  unit: string | null;
  refRangeLow: number | null;
  refRangeHigh: number | null;
  flag: "H" | "L" | null;
};

type Props = {
  reportId: string;
  sampleDate: string | null;
  labSource: string;
  markers: ExtractedMarker[];
  deleteReport: (reportId: string) => Promise<void>;
};

export function ExpandableReport({
  reportId,
  sampleDate,
  labSource,
  markers,
  deleteReport,
}: Props) {
  const [expanded, setExpanded] = useState(false);

  return (
    <div className="px-5 py-3">
      <div className="flex items-center justify-between">
        <button
          type="button"
          onClick={() => setExpanded(!expanded)}
          className="flex cursor-pointer items-center gap-2 text-left"
        >
          <i
            className={`ti ti-chevron-right text-[12px] text-tertiary transition-transform ${
              expanded ? "rotate-90" : ""
            }`}
          />
          <span className="font-mono text-[13px] text-primary">
            {sampleDate ?? "No date"}
          </span>
          <span className="text-[12px] text-secondary">{labSource}</span>
          <span className="font-mono text-[10px] uppercase tracking-[0.06em] text-tertiary">
            {markers.length} marker{markers.length === 1 ? "" : "s"}
          </span>
        </button>
        <BloodReportActions reportId={reportId} deleteReport={deleteReport} />
      </div>

      {expanded && markers.length > 0 && (
        <div className="mt-3 ml-5">
          <table className="w-full font-mono text-[11px]">
            <thead>
              <tr className="text-left text-tertiary">
                <th className="py-1.5 pr-4 font-normal">Marker</th>
                <th className="py-1.5 pr-4 font-normal text-right">Value</th>
                <th className="py-1.5 pr-4 font-normal">Unit</th>
                <th className="py-1.5 font-normal">Ref Range</th>
              </tr>
            </thead>
            <tbody>
              {markers.map((m, idx) => (
                <tr key={idx} className="border-t-[0.5px] border-border-subtle">
                  <td className="py-1.5 pr-4 text-primary">{m.name}</td>
                  <td className="py-1.5 pr-4 text-right">
                    <span
                      className={
                        m.flag === "H"
                          ? "text-alert"
                          : m.flag === "L"
                            ? "text-warn"
                            : "text-primary"
                      }
                    >
                      {m.value?.toFixed(2) ?? "—"}
                    </span>
                    {m.flag && (
                      <span className="ml-1 text-[9px] font-medium text-alert">
                        {m.flag}
                      </span>
                    )}
                  </td>
                  <td className="py-1.5 pr-4 text-tertiary">{m.unit ?? ""}</td>
                  <td className="py-1.5 text-tertiary">
                    {m.refRangeLow ?? "—"} – {m.refRangeHigh ?? "—"}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
