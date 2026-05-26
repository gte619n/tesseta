package com.gte619n.healthfitness.core.equipment;

import java.util.concurrent.CompletableFuture;

/**
 * Generates an AI image for a piece of equipment and persists the result.
 *
 * <p>Implementations live in higher modules (e.g. {@code integrations}) so
 * {@code core} can depend only on this abstraction — mirroring the
 * {@link EquipmentParser} pattern.
 *
 * <p>The expected behavior is fire-and-forget: the call returns immediately
 * with a {@link CompletableFuture} that completes when the underlying job
 * finishes (success OR failure). On completion the implementation is
 * responsible for updating the equipment's {@code imageUrl} and
 * {@code imageStatus} via the repository.
 *
 * <p>Callers that don't need to await completion can ignore the returned
 * future; callers driving a batch can {@link CompletableFuture#allOf} them.
 */
public interface EquipmentImageGenerator {
    /**
     * Kick off async image generation for the given equipment. Returns
     * immediately. The returned future completes when the underlying job
     * finishes; at that point the equipment's {@code imageUrl} and
     * {@code imageStatus} have been updated in storage.
     */
    CompletableFuture<Void> generateImageAsync(Equipment equipment);

    /**
     * Same as above but uses {@code promptOverride} verbatim instead of
     * building one from the equipment metadata. Pass {@code null} to fall
     * back to the default prompt.
     */
    CompletableFuture<Void> generateImageAsync(Equipment equipment, String promptOverride);

    /**
     * Returns the default prompt this generator would produce for the
     * given equipment. Used by admins to seed an editable prompt field
     * before requesting a regeneration with overrides.
     */
    String defaultPrompt(Equipment equipment);
}
