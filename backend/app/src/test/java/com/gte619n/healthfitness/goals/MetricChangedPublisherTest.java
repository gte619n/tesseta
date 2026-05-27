package com.gte619n.healthfitness.goals;

import static org.assertj.core.api.Assertions.assertThat;

import com.gte619n.healthfitness.core.goals.eval.MetricKey;
import com.gte619n.healthfitness.core.goals.events.MetricChangedEvent;
import com.gte619n.healthfitness.core.goals.events.MetricChangedPublisher;
import com.gte619n.healthfitness.testsupport.TestPersistenceConfig;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.test.context.ActiveProfiles;

/**
 * Verifies that {@link MetricChangedPublisher#publish} produces a
 * {@link MetricChangedEvent} with the correct fields.
 *
 * Uses a small {@link TestConfiguration} that registers an in-test
 * {@link EventListener} collecting events into a list — compatible
 * with Spring 5 and 6 without requiring {@code @RecordApplicationEvents}.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestPersistenceConfig.class, MetricChangedPublisherTest.EventCaptor.class})
class MetricChangedPublisherTest {

    @Autowired
    MetricChangedPublisher publisher;

    @Autowired
    EventCaptor captor;

    @Test
    void publishEmitsOneEventWithCorrectFields() {
        captor.clear();

        publisher.publish("user-pub-test", MetricKey.BLOOD_LDL);

        assertThat(captor.events()).hasSize(1);
        MetricChangedEvent evt = captor.events().get(0);
        assertThat(evt.userId()).isEqualTo("user-pub-test");
        assertThat(evt.metricKey()).isEqualTo(MetricKey.BLOOD_LDL.key());
        assertThat(evt.occurredAt()).isNotNull();
    }

    @Test
    void publishAllEmitsOneEventPerKey() {
        captor.clear();

        publisher.publishAll("user-pub-all",
            List.of(MetricKey.BLOOD_LDL, MetricKey.BLOOD_APOB));

        assertThat(captor.events()).hasSize(2);
        assertThat(captor.events())
            .extracting(MetricChangedEvent::metricKey)
            .containsExactlyInAnyOrder(MetricKey.BLOOD_LDL.key(), MetricKey.BLOOD_APOB.key());
    }

    /** Simple test event sink — registered as a Spring bean so the context wires it. */
    @TestConfiguration
    static class EventCaptor {

        private final List<MetricChangedEvent> events = new ArrayList<>();

        @EventListener
        public void on(MetricChangedEvent event) {
            events.add(event);
        }

        public List<MetricChangedEvent> events() {
            return events;
        }

        public void clear() {
            events.clear();
        }
    }
}
