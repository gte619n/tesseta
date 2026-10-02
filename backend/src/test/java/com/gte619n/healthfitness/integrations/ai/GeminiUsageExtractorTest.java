package com.gte619n.healthfitness.integrations.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import org.junit.jupiter.api.Test;

class GeminiUsageExtractorTest {

    private static GenerateContentResponse responseWithUsage(
        Integer prompt, Integer candidates, Integer total) {
        GenerateContentResponseUsageMetadata.Builder meta =
            GenerateContentResponseUsageMetadata.builder();
        if (prompt != null) meta.promptTokenCount(prompt);
        if (candidates != null) meta.candidatesTokenCount(candidates);
        if (total != null) meta.totalTokenCount(total);
        return GenerateContentResponse.builder().usageMetadata(meta.build()).build();
    }

    @Test
    void extractsTokenCountsFromUsageMetadata() {
        GeminiUsageExtractor.Usage u =
            GeminiUsageExtractor.from(responseWithUsage(1200, 340, 1540));
        assertThat(u.inputTokens()).isEqualTo(1200);
        assertThat(u.outputTokens()).isEqualTo(340);
        assertThat(u.totalTokens()).isEqualTo(1540);
    }

    @Test
    void derivesTotalWhenAbsent() {
        GeminiUsageExtractor.Usage u =
            GeminiUsageExtractor.from(responseWithUsage(100, 50, null));
        assertThat(u.totalTokens()).isEqualTo(150);
    }

    @Test
    void terminalStreamChunkUsageIsRead() {
        // Streaming: the SDK accumulates final usage on the terminal chunk; the
        // extractor reads whichever response it is handed.
        GenerateContentResponse lastChunk = responseWithUsage(5000, 2000, 7000);
        GeminiUsageExtractor.Usage u = GeminiUsageExtractor.from(lastChunk);
        assertThat(u.inputTokens()).isEqualTo(5000);
        assertThat(u.outputTokens()).isEqualTo(2000);
    }

    @Test
    void absentMetadataYieldsEmpty() {
        GenerateContentResponse noMeta = GenerateContentResponse.builder().build();
        assertThat(GeminiUsageExtractor.from(noMeta)).isEqualTo(GeminiUsageExtractor.Usage.EMPTY);
    }

    @Test
    void nullResponseYieldsEmpty() {
        assertThat(GeminiUsageExtractor.from(null)).isEqualTo(GeminiUsageExtractor.Usage.EMPTY);
    }
}
