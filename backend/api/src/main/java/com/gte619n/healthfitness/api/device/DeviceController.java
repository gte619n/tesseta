package com.gte619n.healthfitness.api.device;

import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.device.DeviceStatusService;
import com.gte619n.healthfitness.core.push.FcmToken;
import com.gte619n.healthfitness.core.push.FcmTokenRepository;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

// Lists the current user's connected devices with a freshness status,
// derived from the last time each source platform synced data. Only
// devices that have actually synced appear — no placeholders.
//
// Also owns the FCM device-token registry subpath (IMPL-AND-20, D18): the
// /fcm endpoints register/refresh and delete the per-device push token at
// users/{uid}/fcmTokens/{deviceId}. Kept in this controller (rather than a
// separate one) so all /api/me/devices* routing lives in one place; the GET
// at the root and the /fcm subpath do not collide.
@RestController
@RequestMapping("/api/me/devices")
public class DeviceController {

    private final DeviceStatusService devices;
    private final CurrentUserProvider currentUser;
    private final FcmTokenRepository fcmTokens;

    public DeviceController(
        DeviceStatusService devices,
        CurrentUserProvider currentUser,
        FcmTokenRepository fcmTokens
    ) {
        this.devices = devices;
        this.currentUser = currentUser;
        this.fcmTokens = fcmTokens;
    }

    @GetMapping
    public List<DeviceResponse> list() {
        String userId = currentUser.get().userId();
        return devices.devicesFor(userId).stream().map(DeviceResponse::from).toList();
    }

    // Register or refresh this device's FCM token (after sign-in / on rotation).
    @PutMapping("/fcm")
    public ResponseEntity<Void> registerFcm(@RequestBody FcmRegistrationRequest body) {
        if (body == null || body.token() == null || body.token().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "token is required");
        }
        if (body.deviceId() == null || body.deviceId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "deviceId is required");
        }
        String userId = currentUser.get().userId();
        fcmTokens.save(userId, new FcmToken(body.deviceId(), body.token(), null));
        return ResponseEntity.noContent().build();
    }

    // Remove this device's FCM token on sign-out (D18).
    @DeleteMapping("/fcm")
    public ResponseEntity<Void> deleteFcm(@RequestBody FcmDeleteRequest body) {
        if (body == null || body.deviceId() == null || body.deviceId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "deviceId is required");
        }
        String userId = currentUser.get().userId();
        fcmTokens.delete(userId, body.deviceId());
        return ResponseEntity.noContent().build();
    }

    public record FcmRegistrationRequest(String token, String deviceId) {}

    public record FcmDeleteRequest(String deviceId) {}
}
