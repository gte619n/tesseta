package com.gte619n.healthfitness.testsupport.push;

import com.gte619n.healthfitness.core.push.FcmSendResult;
import com.gte619n.healthfitness.core.push.FcmSender;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Capturing {@link FcmSender} test double (IMPL-AND-20 Phase 2). Records every
 * fan-out so tests can assert the recipient token list and the data-only sync
 * payload (Phase 2 verification). Installed as a {@code @TestConfiguration}
 * primary bean (or via {@code TestPersistenceConfig}) so it wins over the
 * production no-op transport.
 *
 * <p>Optionally returns a fixed set of {@code unregisteredTokens} so the prune
 * path in {@code SyncChangePublisher} can be exercised.
 */
public class RecordingFcmSender implements FcmSender {

    /** One captured send: the addressed tokens and the changed collections. */
    public record Sent(List<String> tokens, List<String> collections) {}

    private final List<Sent> sends = new CopyOnWriteArrayList<>();
    private volatile List<String> unregisteredToReport = List.of();

    @Override
    public FcmSendResult sendSyncData(List<String> tokens, List<String> collections) {
        sends.add(new Sent(new ArrayList<>(tokens), new ArrayList<>(collections)));
        List<String> unregistered = new ArrayList<>();
        for (String t : unregisteredToReport) {
            if (tokens.contains(t)) {
                unregistered.add(t);
            }
        }
        return new FcmSendResult(tokens.size() - unregistered.size(), unregistered);
    }

    /** All sends in order. */
    public List<Sent> sends() {
        return List.copyOf(sends);
    }

    /** The most recent send, or null if none. */
    public Sent lastSend() {
        return sends.isEmpty() ? null : sends.get(sends.size() - 1);
    }

    /** Arrange for these tokens to be reported UNREGISTERED on the next sends. */
    public void reportUnregistered(String... tokens) {
        this.unregisteredToReport = List.of(tokens);
    }

    public void clear() {
        sends.clear();
        unregisteredToReport = List.of();
    }
}
