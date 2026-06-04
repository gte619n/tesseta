package com.gte619n.healthfitness.api.location;

import com.gte619n.healthfitness.core.location.DayOfWeek;
import com.gte619n.healthfitness.core.location.HoursSlot;
import java.util.List;
import java.util.Map;

public record UpdateLocationRequest(
    String name,
    String address,
    Boolean is24Hours,
    Map<DayOfWeek, HoursSlot> hours,
    List<String> amenities,
    List<String> equipmentIds
) {}
