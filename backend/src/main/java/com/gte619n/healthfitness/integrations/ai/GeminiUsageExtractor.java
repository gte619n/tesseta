package com.gte619n.healthfitness.integrations.ai;

import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import java.util.Optional;

/**
 * Pulls token counts out of a google-genai {@link GenerateContentResponse}'s
 * {@code usageMetadata} into a tiny transport record, so every Gemini call site
 * (or the AOP aspect, when AOP is on the classpath) extracts usage the same way
 * before handing it to {@link com.gte619n.healthfitness.core.ai.GeminiCallRecorder}.
 *
 * <p>Verified against google-genai 1.69.0:
 * {@code usageMetadata()} &rarr; {@code Optional<GenerateContentResponseUsageMetadata>};
 * {@code promptTokenCount()} / {@code candidatesTokenCount()} /
 * {@code totalTokenCount()} &rarr; {@code Optional<Integer>}.
 *
 * <p>For STREAMING calls the SDK accumulates usage on the terminal chunk; pass
 * the LAST {@code GenerateContentResponse} observed from the stream iterator and
 * this reads its final counts (spec §6, risk note on streaming usage).
 */
public final class GeminiUsageExtractor {

    private GeminiUsageExtractor() {}

    /** Token counts for one call; zeros when the response carries no usage. */
    public record Usage(long inputTokens, long outputTokens, long totalTokens) {
        public static final Usage EMPTY = new Usage(0, 0, 0);
    }

    /**
     * Extract usage from a completed response (or the terminal stream chunk).
     * Null-safe: a null response or absent metadata yields {@link Usage#EMPTY}.
     */
    public static Usage from(GenerateContentResponse response) {
        if (response == null) {
            return Usage.EMPTY;
        }
        Optional<GenerateContentResponseUsageMetadata> meta = response.usageMetadata();
        if (meta.isEmpty()) {
            return Usage.EMPTY;
        }
        GenerateContentResponseUsageMetadata m = meta.get();
        long in = m.promptTokenCount().map(Integer::longValue).orElse(0L);
        long out = m.candidatesTokenCount().map(Integer::longValue).orElse(0L);
        long total = m.totalTokenCount().map(Integer::longValue).orElse(in + out);
        return new Usage(in, out, total);
    }
}
