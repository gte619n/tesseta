package com.gte619n.healthfitness.core.workoutprogram;

import com.gte619n.healthfitness.core.exercise.BlockType;
import java.util.List;

public record Block(
    String blockId,
    BlockType type,
    String title,
    int orderIndex,
    List<Prescription> prescriptions
) {}
