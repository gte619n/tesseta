// Types for the medications feature

export type DrugCategory = "PRESCRIPTION" | "SUPPLEMENT" | "OTC" | "PEPTIDE" | "TOPICAL";
export type DrugForm = "INJECTABLE_VIAL" | "TABLET" | "CAPSULE" | "SOFTGEL" | "CREAM" | "PATCH" | "LIQUID" | "POWDER";
export type MedicationStatus = "ACTIVE" | "DISCONTINUED";
export type FrequencyType = "DAILY" | "WEEKLY" | "MONTHLY" | "PRN" | "CYCLE";
export type TimeWindow = "MORNING" | "AFTERNOON" | "EVENING" | "BEDTIME";
export type DayOfWeek = "MON" | "TUE" | "WED" | "THU" | "FRI" | "SAT" | "SUN";
export type DiscontinueReason = "COMPLETED" | "SIDE_EFFECTS" | "SWITCHED" | "COST" | "OTHER";
export type ChangeType = "DOSE_CHANGE" | "FREQUENCY_CHANGE" | "SCHEDULE_CHANGE";

export interface Drug {
  drugId: string;
  name: string;
  aliases: string[];
  category: DrugCategory;
  form: DrugForm;
  defaultUnit: string;
  commonDoses: string[];
  imageUrl: string | null;
  imageFallback: string;
  suggestedMarkers: string[];
  description: string | null;
}

export interface TimeSlot {
  window: TimeWindow;
  dose: number;
}

export interface DosagePeriod {
  dose: number;
  unit: string;
  startDate: string;      // ISO date (YYYY-MM-DD)
  endDate: string | null; // null = current/active period; end is exclusive
}

export interface CycleConfig {
  onWeeks: number;
  offWeeks: number;
  startDate: string;
}

export interface FrequencyConfig {
  type: FrequencyType;
  timesPerPeriod?: number;
  specificDays?: DayOfWeek[];
  cycle?: CycleConfig;
}

export interface DayAdherence {
  date: string;
  taken: boolean;
}

export interface AdherenceSummary {
  last30Days: DayAdherence[];
  percentage: number;
}

export interface Medication {
  medicationId: string;
  drugId: string;
  drug: Drug | null;
  customName: string | null;
  status: MedicationStatus;
  dose: number;
  unit: string;
  frequency: FrequencyConfig;
  timeSlots: TimeSlot[];
  protocolId: string | null;
  notes: string | null;
  prescribedBy: string | null;
  startDate: string;
  endDate: string | null;
  discontinueReason: DiscontinueReason | null;
  discontinueNotes: string | null;
  correlatedMarkers: string[];
  dosagePeriods: DosagePeriod[];
  adherence?: AdherenceSummary;
}

export interface HistoryEntry {
  historyId: string;
  changeType: ChangeType;
  previousValue: string;
  newValue: string;
  changedAt: string;
  notes: string | null;
}

export interface MedicationDetail extends Medication {
  history: HistoryEntry[];
}

export interface TodaysDose {
  medicationId: string;
  drugName: string;
  imageUrl: string | null;
  window: TimeWindow;
  dose: number;
  unit: string;
  taken: boolean;
  takenAt: string | null;
}

// Labels and display helpers
export const CATEGORY_LABELS: Record<DrugCategory, string> = {
  PRESCRIPTION: "Prescription",
  SUPPLEMENT: "Supplement",
  OTC: "OTC",
  PEPTIDE: "Peptide",
  TOPICAL: "Topical",
};

export const FORM_LABELS: Record<DrugForm, string> = {
  INJECTABLE_VIAL: "Injectable",
  TABLET: "Tablet",
  CAPSULE: "Capsule",
  SOFTGEL: "Softgel",
  CREAM: "Cream",
  PATCH: "Patch",
  LIQUID: "Liquid",
  POWDER: "Powder",
};

export const TIME_WINDOW_LABELS: Record<TimeWindow, string> = {
  MORNING: "Morning",
  AFTERNOON: "Afternoon",
  EVENING: "Evening",
  BEDTIME: "Bedtime",
};

export const FREQUENCY_LABELS: Record<FrequencyType, string> = {
  DAILY: "Daily",
  WEEKLY: "Weekly",
  MONTHLY: "Monthly",
  PRN: "As needed",
  CYCLE: "Cycling",
};

export const DAY_LABELS: Record<DayOfWeek, string> = {
  MON: "Mon",
  TUE: "Tue",
  WED: "Wed",
  THU: "Thu",
  FRI: "Fri",
  SAT: "Sat",
  SUN: "Sun",
};

export const DISCONTINUE_LABELS: Record<DiscontinueReason, string> = {
  COMPLETED: "Course completed",
  SIDE_EFFECTS: "Side effects",
  SWITCHED: "Switched medication",
  COST: "Cost",
  OTHER: "Other",
};

// Alias for backward compatibility
export const DISCONTINUE_REASON_LABELS = DISCONTINUE_LABELS;

export function formatFrequency(freq: FrequencyConfig): string {
  switch (freq.type) {
    case "DAILY":
      return freq.timesPerPeriod === 1 ? "Once daily" : `${freq.timesPerPeriod}x daily`;
    case "WEEKLY":
      if (freq.specificDays && freq.specificDays.length > 0) {
        return freq.specificDays.map(d => DAY_LABELS[d]).join(", ");
      }
      return freq.timesPerPeriod === 1 ? "Once weekly" : `${freq.timesPerPeriod}x weekly`;
    case "MONTHLY":
      return freq.timesPerPeriod === 1 ? "Once monthly" : `${freq.timesPerPeriod}x monthly`;
    case "PRN":
      return "As needed";
    case "CYCLE":
      if (freq.cycle) {
        return `${freq.cycle.onWeeks}wk on / ${freq.cycle.offWeeks}wk off`;
      }
      return "Cycling";
    default:
      return "Unknown";
  }
}
