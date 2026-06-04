package com.gte619n.healthfitness.api.goals.dto;

public record ChatMessageRequest(
    String threadId,   // null/blank → a new thread is created lazily
    String message
) {}
