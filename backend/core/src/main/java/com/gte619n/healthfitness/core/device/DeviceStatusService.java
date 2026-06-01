package com.gte619n.healthfitness.core.device;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

// Builds the per-user device list shown in the clients. A device is
// "working" if we've recorded at least one data sync for its platform, so
// this only ever returns devices that are actually feeding data — no
// placeholders. Each device carries a traffic-light status derived from how
// recently it last synced.
@Service
public class DeviceStatusService {

    private final DeviceSyncRepository deviceSyncs;

    public DeviceStatusService(DeviceSyncRepository deviceSyncs) {
        this.deviceSyncs = deviceSyncs;
    }

    public List<Device> devicesFor(String userId) {
        Instant now = Instant.now();
        return deviceSyncs.findByUser(userId).stream()
            .sorted(Comparator.comparing(
                DeviceSync::lastSyncedAt,
                Comparator.nullsLast(Comparator.reverseOrder())))
            .map(s -> new Device(
                s.platform(),
                displayName(s.platform()),
                s.platform(),
                s.lastSyncedAt(),
                DeviceSyncStatus.fromLastSynced(s.lastSyncedAt(), now)))
            .toList();
    }

    // Map a raw Google Health source-platform identifier to a friendly name.
    // Unknown platforms are title-cased so a new vendor still renders sanely.
    static String displayName(String platform) {
        if (platform == null || platform.isBlank()) {
            return "Unknown device";
        }
        return switch (platform.toUpperCase(Locale.ROOT)) {
            case "FITBIT" -> "Fitbit";
            case "WITHINGS" -> "Withings";
            case "GARMIN" -> "Garmin";
            case "OURA" -> "Oura";
            case "GOOGLE_FIT", "GOOGLEFIT" -> "Google Fit";
            case "SAMSUNG_HEALTH", "SAMSUNGHEALTH" -> "Samsung Health";
            case "WHOOP" -> "Whoop";
            case "UNKNOWN" -> "Unknown device";
            default -> titleCase(platform);
        };
    }

    private static String titleCase(String raw) {
        String cleaned = raw.replace('_', ' ').replace('-', ' ').trim().toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(cleaned.length());
        boolean capNext = true;
        for (char c : cleaned.toCharArray()) {
            if (c == ' ') {
                capNext = true;
                sb.append(c);
            } else if (capNext) {
                sb.append(Character.toUpperCase(c));
                capNext = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
