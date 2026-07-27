package com.gte619n.healthfitness.api.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gte619n.healthfitness.api.v1.CursorCodec.Position;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CursorCodecTest {

    @Test
    void roundTripsSortKeyAndId() {
        Instant t = Instant.parse("2026-07-01T12:34:56Z");
        Position p = CursorCodec.decode(CursorCodec.encode(t, "entry-42"));
        assertThat(p.sortKey()).isEqualTo(Instant.ofEpochMilli(t.toEpochMilli()));
        assertThat(p.id()).isEqualTo("entry-42");
    }

    @Test
    void handlesIdsContainingSeparators() {
        Position p = CursorCodec.decode(CursorCodec.encode(Instant.EPOCH, "2026-07-01_med:9"));
        assertThat(p.id()).isEqualTo("2026-07-01_med:9");
    }

    @Test
    void nullSortKeyEncodesAsEpoch() {
        Position p = CursorCodec.decode(CursorCodec.encode(null, "x"));
        assertThat(p.sortKey()).isEqualTo(Instant.EPOCH);
    }

    @Test
    void malformedCursorIsRejected() {
        assertThatThrownBy(() -> CursorCodec.decode("!!!not-base64!!!"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CursorCodec.decode(
            java.util.Base64.getUrlEncoder().encodeToString("no-separator".getBytes())))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
