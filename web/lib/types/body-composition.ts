/** Body-composition metric kinds tracked from Google Health / DEXA. */
export type Metric = "WEIGHT_KG" | "BODY_FAT_PERCENT" | "LEAN_MASS_KG" | "BMI";

/** A single body-composition reading: one metric at one sample time. */
export type Reading = {
  recordId: string;
  metric: Metric;
  value: number;
  sampleTime: string;
  sourcePlatform: string | null;
  recordingMethod: string | null;
};
