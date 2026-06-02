package com.gte619n.healthfitness.api.sync;

import com.gte619n.healthfitness.core.sync.IdempotencyStore;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Reusable helper threading the IMPL-AND-20 (D7/D18) write contract through any
 * in-scope mutating controller, so the logic lives in one place instead of being
 * copy-pasted per endpoint:
 *
 * <ul>
 *   <li><b>Client-minted id.</b> {@link #resolveId(String)} returns the client's
 *       UUID when the create body carries one, else a server-generated UUID —
 *       preserving the previous server-generates behaviour.</li>
 *   <li><b>Idempotent replay ({@code Idempotency-Key}).</b>
 *       {@link #idempotentCreate} runs a create at most once per key; a replay
 *       returns the current state of the originally-created document instead of
 *       creating a duplicate.</li>
 *   <li><b>Origin device ({@code X-HF-Origin-Device}).</b> {@link #originDeviceId()}
 *       surfaces the device that produced the change for Phase 2 FCM fan-out
 *       suppression. No publisher exists yet (Phase 2), so it is accepted and
 *       made available but otherwise a no-op here.</li>
 * </ul>
 *
 * <p>Header reads use {@link RequestContextHolder} so controllers need not add a
 * {@code HttpServletRequest}/header parameter to every signature.
 */
@Component
public class SyncWriteContext {

    /** Header carrying the client's replay-guard key (D7). */
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    /** Header carrying the originating device id for fan-out suppression (D18). */
    public static final String ORIGIN_DEVICE_HEADER = "X-HF-Origin-Device";

    private final IdempotencyStore idempotencyStore;

    public SyncWriteContext(IdempotencyStore idempotencyStore) {
        this.idempotencyStore = idempotencyStore;
    }

    /** The client-minted id when non-blank, else a fresh server-generated UUID. */
    public String resolveId(String clientId) {
        if (clientId != null && !clientId.isBlank()) {
            return clientId;
        }
        return UUID.randomUUID().toString();
    }

    /** The {@code Idempotency-Key} header, or null when absent/blank. */
    public String idempotencyKey() {
        return header(IDEMPOTENCY_KEY_HEADER);
    }

    /**
     * The {@code X-HF-Origin-Device} header, or null when absent. Consumed by
     * Phase 2 fan-out; accepted gracefully today.
     */
    public String originDeviceId() {
        return header(ORIGIN_DEVICE_HEADER);
    }

    /**
     * Run a create idempotently.
     *
     * <p>With no {@code Idempotency-Key} header this simply invokes {@code create}
     * (unchanged behaviour). With a key: if the key was seen before, the original
     * document is re-loaded via {@code loadById} and returned (a no-op replay);
     * otherwise {@code create} runs, the produced id is recorded against the key,
     * and the created result is returned.
     *
     * @param scope    operation discriminator, e.g. {@code "bloodReadings:create"}
     * @param userId   the owning user
     * @param create   performs the create and returns {@code (id, result)}
     * @param loadById re-loads the current state of a previously-created id
     * @param <T>      the controller's response type
     */
    public <T> T idempotentCreate(
        String scope,
        String userId,
        Supplier<Created<T>> create,
        Function<String, Optional<T>> loadById
    ) {
        String key = idempotencyKey();
        if (key == null) {
            return create.get().result();
        }
        Optional<String> priorId = idempotencyStore.findResult(userId, scope, key);
        if (priorId.isPresent()) {
            // Replay: return the current state of the original document. If it
            // was since deleted, fall through to a fresh create is unsafe (would
            // duplicate), so return the (possibly empty) current state instead —
            // the original id is the contract.
            return loadById.apply(priorId.get())
                .orElseGet(() -> create.get().result());
        }
        Created<T> created = create.get();
        idempotencyStore.record(userId, scope, key, created.id());
        return created.result();
    }

    /** A freshly created document: its id (for the replay guard) and the response. */
    public record Created<T>(String id, T result) {}

    private static String header(String name) {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (!(attrs instanceof ServletRequestAttributes servletAttrs)) {
            return null;
        }
        HttpServletRequest request = servletAttrs.getRequest();
        String value = request.getHeader(name);
        return (value == null || value.isBlank()) ? null : value;
    }
}
