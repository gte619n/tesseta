package com.gte619n.healthfitness.api.bloodtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gte619n.healthfitness.core.bloodtest.BloodTestReport;
import com.gte619n.healthfitness.core.bloodtest.BloodTestReportRepository;
import com.gte619n.healthfitness.core.goals.events.MetricChangedPublisher;
import com.gte619n.healthfitness.integrations.bloodtest.BloodTestDuplicateException;
import com.gte619n.healthfitness.integrations.bloodtest.BloodTestExtraction;
import com.gte619n.healthfitness.integrations.bloodtest.BloodTestExtractor;
import com.gte619n.healthfitness.integrations.bloodtest.BloodTestPdfStorage;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

// Guards the concurrency protection added after the picker retry-storm bug:
// identical PDFs uploaded near-simultaneously must not all persist. The service
// reserves the content hash atomically (tryReserveContentHash) and releases it
// in a finally so a genuine retry after a transient failure isn't blocked.
class BloodTestServiceDedupTest {

    private static final String USER = "user-1";
    private static final byte[] PDF = "pretend-pdf-bytes".getBytes();

    private final BloodTestPdfStorage pdfStorage = mock(BloodTestPdfStorage.class);
    private final BloodTestExtractor extractor = mock(BloodTestExtractor.class);
    private final BloodTestReportRepository reports = mock(BloodTestReportRepository.class);
    private final MetricChangedPublisher publisher = mock(MetricChangedPublisher.class);
    private final BloodTestService service =
        new BloodTestService(pdfStorage, extractor, reports, publisher);

    @Test
    void rejectsWhenReservationIsLost_withoutSpendingOnStorageOrGemini() {
        when(reports.findByContentHash(anyString(), anyString())).thenReturn(Optional.empty());
        when(reports.tryReserveContentHash(anyString(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> service.upload(USER, "report.pdf", PDF))
            .isInstanceOf(BloodTestDuplicateException.class);

        // No expensive work and nothing persisted for the losing upload.
        verify(pdfStorage, never()).upload(anyString(), anyString(), any());
        verify(extractor, never()).extract(any());
        verify(reports, never()).save(any());
        // Nothing to release: the reservation was never held by this caller.
        verify(reports, never()).releaseContentHash(anyString(), anyString());
    }

    @Test
    void releasesReservationOnSuccess() {
        when(reports.findByContentHash(anyString(), anyString())).thenReturn(Optional.empty());
        when(reports.tryReserveContentHash(anyString(), anyString())).thenReturn(true);
        when(pdfStorage.upload(anyString(), anyString(), any())).thenReturn("gs://bucket/x.pdf");
        when(extractor.extract(any())).thenReturn(
            new BloodTestExtraction(LocalDate.of(2026, 7, 27), "LabCorp", List.of()));

        BloodTestReport report = service.upload(USER, "report.pdf", PDF);

        assertThat(report).isNotNull();
        verify(reports).save(any());
        verify(reports).releaseContentHash(anyString(), anyString());
    }

    @Test
    void releasesReservationWhenExtractionFails() {
        when(reports.findByContentHash(anyString(), anyString())).thenReturn(Optional.empty());
        when(reports.tryReserveContentHash(anyString(), anyString())).thenReturn(true);
        when(pdfStorage.upload(anyString(), anyString(), any())).thenReturn("gs://bucket/x.pdf");
        when(extractor.extract(any())).thenThrow(new RuntimeException("gemini exploded"));

        assertThatThrownBy(() -> service.upload(USER, "report.pdf", PDF))
            .isInstanceOf(RuntimeException.class);

        // Finally-release lets the user retry the same PDF after a transient error.
        verify(reports).releaseContentHash(anyString(), anyString());
        verify(reports, never()).save(any());
    }
}
