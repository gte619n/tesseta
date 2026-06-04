package com.gte619n.healthfitness.core.medication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DosagePeriodTest {

    private static final LocalDate JAN = LocalDate.of(2026, 1, 26);
    private static final LocalDate MAY = LocalDate.of(2026, 5, 26);

    @Test
    @DisplayName("active() returns the open-ended period")
    void activeReturnsOpenPeriod() {
        List<DosagePeriod> periods = List.of(
            new DosagePeriod(25, "mg", JAN, MAY),
            new DosagePeriod(50, "mg", MAY, null)
        );
        DosagePeriod active = DosagePeriod.active(periods);
        assertThat(active).isNotNull();
        assertThat(active.dose()).isEqualTo(50);
        assertThat(active.isActive()).isTrue();
    }

    @Test
    @DisplayName("changeDose closes the active period and opens a new one")
    void changeDoseClosesAndOpens() {
        List<DosagePeriod> start = List.of(DosagePeriod.initial(25, "mg", JAN));
        List<DosagePeriod> result = DosagePeriod.changeDose(start, 50, "mg", MAY);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).dose()).isEqualTo(25);
        assertThat(result.get(0).endDate()).isEqualTo(MAY);
        assertThat(result.get(1).dose()).isEqualTo(50);
        assertThat(result.get(1).isActive()).isTrue();
        // input is not mutated
        assertThat(start).hasSize(1);
        DosagePeriod.validate(result);
    }

    @Test
    @DisplayName("replaceActive swaps dose/unit on the open period only")
    void replaceActiveSwapsOpenPeriod() {
        List<DosagePeriod> periods = List.of(
            new DosagePeriod(25, "mg", JAN, MAY),
            new DosagePeriod(50, "mg", MAY, null)
        );
        List<DosagePeriod> result = DosagePeriod.replaceActive(periods, 75, "mcg");
        assertThat(result.get(0).dose()).isEqualTo(25);
        assertThat(result.get(1).dose()).isEqualTo(75);
        assertThat(result.get(1).unit()).isEqualTo("mcg");
        assertThat(result.get(1).startDate()).isEqualTo(MAY);
    }

    @Test
    @DisplayName("closeActive closes the open period at the given end date")
    void closeActiveClosesOpenPeriod() {
        List<DosagePeriod> periods = List.of(DosagePeriod.initial(25, "mg", JAN));
        List<DosagePeriod> result = DosagePeriod.closeActive(periods, MAY);
        assertThat(result.get(0).endDate()).isEqualTo(MAY);
        assertThat(result.stream().anyMatch(DosagePeriod::isActive)).isFalse();
    }

    @Test
    @DisplayName("reopen appends a new open period and closes any prior open one")
    void reopenAppendsOpenPeriod() {
        // Simulate a discontinued med whose period was already closed.
        List<DosagePeriod> closed = List.of(new DosagePeriod(25, "mg", JAN, MAY));
        List<DosagePeriod> result = DosagePeriod.reopen(closed, 25, "mg", MAY.plusMonths(1));
        assertThat(result).hasSize(2);
        assertThat(result.get(1).isActive()).isTrue();
        assertThat(result.get(1).startDate()).isEqualTo(MAY.plusMonths(1));
        DosagePeriod.validate(result);
    }

    @Test
    @DisplayName("shiftEarliestStart moves only the earliest period's start")
    void shiftEarliestStartMovesEarliest() {
        List<DosagePeriod> periods = List.of(
            new DosagePeriod(25, "mg", JAN, MAY),
            new DosagePeriod(50, "mg", MAY, null));
        LocalDate newStart = JAN.minusDays(10);
        List<DosagePeriod> result = DosagePeriod.shiftEarliestStart(periods, newStart);
        assertThat(result.get(0).startDate()).isEqualTo(newStart);
        assertThat(result.get(1).startDate()).isEqualTo(MAY);
        DosagePeriod.validate(result);
    }

    @Test
    @DisplayName("validate accepts a contiguous history with one open period")
    void validateAcceptsValidHistory() {
        List<DosagePeriod> periods = List.of(
            new DosagePeriod(25, "mg", JAN, MAY),
            new DosagePeriod(50, "mg", MAY, null)
        );
        DosagePeriod.validate(periods); // does not throw
    }

    @Test
    @DisplayName("validate accepts gaps between periods")
    void validateAcceptsGaps() {
        List<DosagePeriod> periods = List.of(
            new DosagePeriod(25, "mg", JAN, MAY),
            new DosagePeriod(50, "mg", MAY.plusMonths(1), null)
        );
        DosagePeriod.validate(periods); // gaps allowed
    }

    @Test
    @DisplayName("validate rejects an empty list")
    void validateRejectsEmpty() {
        assertThatThrownBy(() -> DosagePeriod.validate(List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must not be empty");
    }

    @Test
    @DisplayName("validate rejects more than one open period")
    void validateRejectsMultipleOpen() {
        List<DosagePeriod> periods = List.of(
            new DosagePeriod(25, "mg", JAN, null),
            new DosagePeriod(50, "mg", MAY, null)
        );
        assertThatThrownBy(() -> DosagePeriod.validate(periods))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Exactly one open");
    }

    @Test
    @DisplayName("validate rejects overlapping periods")
    void validateRejectsOverlap() {
        List<DosagePeriod> periods = List.of(
            new DosagePeriod(25, "mg", JAN, MAY.plusMonths(1)),
            new DosagePeriod(50, "mg", MAY, null)
        );
        assertThatThrownBy(() -> DosagePeriod.validate(periods))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("overlap");
    }

    @Test
    @DisplayName("validate rejects a non-positive dose")
    void validateRejectsNonPositiveDose() {
        assertThatThrownBy(() -> DosagePeriod.validate(List.of(new DosagePeriod(0, "mg", JAN, null))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("dose must be positive");
    }

    @Test
    @DisplayName("validate rejects an end date that is not after the start")
    void validateRejectsBadDateRange() {
        List<DosagePeriod> periods = List.of(
            new DosagePeriod(25, "mg", MAY, JAN),
            new DosagePeriod(50, "mg", MAY, null)
        );
        assertThatThrownBy(() -> DosagePeriod.validate(periods))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
