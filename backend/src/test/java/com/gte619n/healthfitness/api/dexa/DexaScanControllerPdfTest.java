package com.gte619n.healthfitness.api.dexa;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Phase-6 tail: uploads are validated by magic bytes, not just the client's
 * content-type header (which is skipped when omitted).
 */
class DexaScanControllerPdfTest {

    @Test
    void acceptsThePdfSignature() {
        byte[] pdf = "%PDF-1.7\n%âãÏÓ".getBytes(StandardCharsets.ISO_8859_1);
        assertThat(DexaScanController.looksLikePdf(pdf)).isTrue();
    }

    @Test
    void rejectsNonPdfContent() {
        assertThat(DexaScanController.looksLikePdf("not a pdf".getBytes(StandardCharsets.US_ASCII)))
            .isFalse();
        assertThat(DexaScanController.looksLikePdf(new byte[] {1, 2, 3})).isFalse();
        assertThat(DexaScanController.looksLikePdf(new byte[0])).isFalse();
        assertThat(DexaScanController.looksLikePdf(null)).isFalse();
    }
}
