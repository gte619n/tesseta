package com.gte619n.healthfitness.core.equipment;

import org.springframework.stereotype.Service;

// Returns placeholder icon URLs for equipment when image generation fails.
// These are static SVG icons served from the web frontend.
@Service
public class PlaceholderService {

    public String getPlaceholderUrl(String category) {
        return switch (category) {
            case "Free Weights" -> "/placeholders/free-weights.svg";
            case "Machines - Strength" -> "/placeholders/strength-machine.svg";
            case "Machines - Cardio" -> "/placeholders/cardio.svg";
            case "Cable Systems" -> "/placeholders/cable.svg";
            case "Benches & Racks" -> "/placeholders/bench.svg";
            case "Bodyweight" -> "/placeholders/bodyweight.svg";
            case "Accessories" -> "/placeholders/accessory.svg";
            default -> "/placeholders/equipment.svg";
        };
    }
}
