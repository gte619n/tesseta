package com.gte619n.healthfitness.api.equipment;

import java.util.List;
import java.util.Map;

public record CategoryTreeResponse(
    Map<String, List<String>> categories
) {}
