import { apiJson } from "@/lib/api";

// Server-only helper for the connected-devices list. Mirrors the backend
// DeviceResponse shape. Status is a traffic light derived from the last
// time the device's source platform synced data.
export type DeviceSyncStatus = "GREEN" | "YELLOW" | "RED";

export type Device = {
  id: string;
  name: string;
  platform: string;
  lastSyncedAt: string | null;
  status: DeviceSyncStatus;
};

export async function getDevices(): Promise<Device[]> {
  return apiJson<Device[]>("/api/me/devices");
}
