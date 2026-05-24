package com.gte619n.healthfitness.core.equipment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlaceholderServiceTest {

    private PlaceholderService service;

    @BeforeEach
    void setUp() {
        service = new PlaceholderService();
    }

    @Test
    void getPlaceholderUrl_freeWeights() {
        String url = service.getPlaceholderUrl("Free Weights");
        assertThat(url).isEqualTo("/placeholders/free-weights.svg");
    }

    @Test
    void getPlaceholderUrl_machinesStrength() {
        String url = service.getPlaceholderUrl("Machines - Strength");
        assertThat(url).isEqualTo("/placeholders/strength-machine.svg");
    }

    @Test
    void getPlaceholderUrl_machinesCardio() {
        String url = service.getPlaceholderUrl("Machines - Cardio");
        assertThat(url).isEqualTo("/placeholders/cardio.svg");
    }

    @Test
    void getPlaceholderUrl_cableSystems() {
        String url = service.getPlaceholderUrl("Cable Systems");
        assertThat(url).isEqualTo("/placeholders/cable.svg");
    }

    @Test
    void getPlaceholderUrl_benchesAndRacks() {
        String url = service.getPlaceholderUrl("Benches & Racks");
        assertThat(url).isEqualTo("/placeholders/bench.svg");
    }

    @Test
    void getPlaceholderUrl_bodyweight() {
        String url = service.getPlaceholderUrl("Bodyweight");
        assertThat(url).isEqualTo("/placeholders/bodyweight.svg");
    }

    @Test
    void getPlaceholderUrl_accessories() {
        String url = service.getPlaceholderUrl("Accessories");
        assertThat(url).isEqualTo("/placeholders/accessory.svg");
    }

    @Test
    void getPlaceholderUrl_unknownCategory() {
        String url = service.getPlaceholderUrl("Unknown Category");
        assertThat(url).isEqualTo("/placeholders/equipment.svg");
    }
}
