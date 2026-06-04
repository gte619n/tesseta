package com.gte619n.healthfitness.api.device;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gte619n.healthfitness.core.auth.CurrentUser;
import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.device.Device;
import com.gte619n.healthfitness.core.device.DeviceStatusService;
import com.gte619n.healthfitness.core.device.DeviceSyncStatus;
import com.gte619n.healthfitness.core.push.FcmTokenRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// Slice test for /api/me/devices and its /fcm subpath. Besides the happy-path
// listing and token registration, this pins the validation guards that throw
// ResponseStatusException — proving they surface as 400s with the structured
// body produced by GlobalExceptionHandler (which @WebMvcTest pulls in as a
// @RestControllerAdvice) rather than collapsing into a 500.
@WebMvcTest(DeviceController.class)
@AutoConfigureMockMvc(addFilters = false)
class DeviceControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean DeviceStatusService devices;
    @MockitoBean CurrentUserProvider currentUser;
    @MockitoBean FcmTokenRepository fcmTokens;

    private static final CurrentUser CALLER =
        new CurrentUser("sub-1", "a@b.com", "A", null);

    @Test
    void listReturnsDevicesForCaller() throws Exception {
        when(currentUser.get()).thenReturn(CALLER);
        when(devices.devicesFor("sub-1")).thenReturn(List.of(
            new Device("d1", "Pixel 8", "ANDROID", Instant.parse("2026-06-04T00:00:00Z"),
                DeviceSyncStatus.GREEN)));

        mvc.perform(get("/api/me/devices"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value("d1"))
            .andExpect(jsonPath("$[0].name").value("Pixel 8"))
            .andExpect(jsonPath("$[0].platform").value("ANDROID"))
            .andExpect(jsonPath("$[0].status").value("GREEN"));
    }

    @Test
    void registerFcmStoresTokenAndReturnsNoContent() throws Exception {
        when(currentUser.get()).thenReturn(CALLER);

        mvc.perform(put("/api/me/devices/fcm")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"tok-abc\",\"deviceId\":\"d1\"}"))
            .andExpect(status().isNoContent());

        verify(fcmTokens).save(eq("sub-1"), any());
    }

    @Test
    void registerFcmRejectsBlankToken() throws Exception {
        mvc.perform(put("/api/me/devices/fcm")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"\",\"deviceId\":\"d1\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("token is required"));

        verify(fcmTokens, never()).save(any(), any());
    }

    @Test
    void deleteFcmRejectsMissingDeviceId() throws Exception {
        mvc.perform(delete("/api/me/devices/fcm")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceId\":\"\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("deviceId is required"));

        verify(fcmTokens, never()).delete(any(), any());
    }
}
