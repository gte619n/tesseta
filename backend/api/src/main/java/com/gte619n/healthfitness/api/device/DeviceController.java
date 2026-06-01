package com.gte619n.healthfitness.api.device;

import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.device.DeviceStatusService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Lists the current user's connected devices with a freshness status,
// derived from the last time each source platform synced data. Only
// devices that have actually synced appear — no placeholders.
@RestController
@RequestMapping("/api/me/devices")
public class DeviceController {

    private final DeviceStatusService devices;
    private final CurrentUserProvider currentUser;

    public DeviceController(DeviceStatusService devices, CurrentUserProvider currentUser) {
        this.devices = devices;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<DeviceResponse> list() {
        String userId = currentUser.get().userId();
        return devices.devicesFor(userId).stream().map(DeviceResponse::from).toList();
    }
}
