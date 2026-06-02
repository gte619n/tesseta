package com.gte619n.healthfitness.api.location;

import com.gte619n.healthfitness.core.location.DayOfWeek;
import com.gte619n.healthfitness.core.location.HoursSlot;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;

public record CreateLocationRequest(
    String id,              // optional client-minted id (IMPL-AND-20 D7); null ⇒ server-generated
    @NotBlank(message = "name is required")
    String name,
    String address,
    boolean is24Hours,
    Map<DayOfWeek, HoursSlot> hours,
    List<String> amenities,
    List<String> equipmentIds
) {}
