package com.gte619n.healthfitness.core.trt;

/**
 * A single curated, cited guideline entry in the TRT knowledge base.
 *
 * <p>Component names are part of the client wire contract — do not rename.
 *
 * @param id       stable identifier, e.g. "trt-total-t-target"
 * @param topic    short topic label, e.g. "Total testosterone target"
 * @param guidance the factual guidance, written conservatively
 * @param source   real citation string for the guidance
 */
public record TrtGuidelineEntry(
    String id,
    String topic,
    String guidance,
    String source
) {}
