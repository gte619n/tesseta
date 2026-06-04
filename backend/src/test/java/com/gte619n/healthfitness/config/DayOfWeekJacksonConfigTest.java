package com.gte619n.healthfitness.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gte619n.healthfitness.core.location.DayOfWeek;
import com.gte619n.healthfitness.core.location.HoursSlot;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DayOfWeekJacksonConfigTest {

    private final ObjectMapper mapper =
        new ObjectMapper().registerModule(new DayOfWeekJacksonConfig().dayOfWeekModule());

    @Test
    void deserializesLowercaseMapKeys() throws Exception {
        String json = "{\"mon\":{\"open\":\"06:00\",\"close\":\"20:00\"}}";
        Map<DayOfWeek, HoursSlot> hours =
            mapper.readValue(json, new TypeReference<Map<DayOfWeek, HoursSlot>>() {});
        assertThat(hours).containsKey(DayOfWeek.MON);
        assertThat(hours.get(DayOfWeek.MON).open()).isEqualTo("06:00");
    }

    @Test
    void serializesMapKeysAsLowercase() throws Exception {
        Map<DayOfWeek, HoursSlot> hours = Map.of(DayOfWeek.MON, new HoursSlot("06:00", "20:00"));
        String json = mapper.writeValueAsString(hours);
        assertThat(json).contains("\"mon\"").doesNotContain("\"MON\"");
    }

    @Test
    void roundTripsAllDays() throws Exception {
        Map<DayOfWeek, HoursSlot> original = Map.of(
            DayOfWeek.MON, new HoursSlot("06:00", "20:00"),
            DayOfWeek.FRI, new HoursSlot("06:00", "20:00"));
        String json = mapper.writeValueAsString(original);
        Map<DayOfWeek, HoursSlot> back =
            mapper.readValue(json, new TypeReference<Map<DayOfWeek, HoursSlot>>() {});
        assertThat(back).isEqualTo(original);
    }

    // The Android client serializes a weekly schedule's specificDays lowercase
    // ("sun"); the default enum deserializer rejected that with a 400. The web
    // client sends them uppercase ("SUN"). Both must deserialize.
    @Test
    void deserializesMedicationDaysCaseInsensitively() throws Exception {
        var lower = mapper.readValue(
            "[\"sun\",\"mon\"]",
            new TypeReference<java.util.List<com.gte619n.healthfitness.core.medication.DayOfWeek>>() {});
        assertThat(lower).containsExactly(
            com.gte619n.healthfitness.core.medication.DayOfWeek.SUN,
            com.gte619n.healthfitness.core.medication.DayOfWeek.MON);

        var upper = mapper.readValue(
            "[\"SUN\"]",
            new TypeReference<java.util.List<com.gte619n.healthfitness.core.medication.DayOfWeek>>() {});
        assertThat(upper).containsExactly(com.gte619n.healthfitness.core.medication.DayOfWeek.SUN);
    }

    @Test
    void deserializesLowercaseDaysInsideFrequencyConfig() throws Exception {
        String json = "{\"type\":\"WEEKLY\",\"timesPerPeriod\":1,\"specificDays\":[\"sun\"]}";
        var freq = mapper.readValue(
            json, com.gte619n.healthfitness.core.medication.FrequencyConfig.class);
        assertThat(freq.specificDays())
            .containsExactly(com.gte619n.healthfitness.core.medication.DayOfWeek.SUN);
    }
}
