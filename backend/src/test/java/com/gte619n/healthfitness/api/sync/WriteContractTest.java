package com.gte619n.healthfitness.api.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.gte619n.healthfitness.testsupport.TestPersistenceConfig;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The offline write contract, enforced (baseline problem: idempotency was
 * per-endpoint folklore). An offline outbox replays every queued mutation, so
 * EVERY mutating endpoint (POST/PUT/PATCH/DELETE) must be safe to replay. This
 * test enumerates the live mutating surface and asserts each endpoint is
 * classified in {@code write-contract.properties} and that NONE is NEEDS_FIX.
 *
 * <p>A new mutating endpoint that isn't classified fails here — so replay-safety
 * can never be silently skipped again. Categories (documented in
 * {@code docs/reference/write-contract.md}):
 * KEY_GUARDED, SET_SEMANTICS, DETERMINISTIC_ID, IDEMPOTENT_DELETE,
 * NON_PERSISTING_POST, EXEMPT. NEEDS_FIX is a failure.
 *
 * <p>When the surface changes intentionally, run the test; its failure message
 * lists the exact live keys to add/remove in the resource.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@Import(TestPersistenceConfig.class)
class WriteContractTest {

    private static final Set<String> MUTATING = Set.of("POST", "PUT", "PATCH", "DELETE");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    void everyMutatingEndpointIsClassifiedAndReplaySafe() throws Exception {
        Map<String, String> contract = loadContract();

        // Live mutating endpoints, keyed "METHOD path", one per (method, pattern).
        Set<String> live = new TreeSet<>();
        for (var entry : handlerMapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = entry.getKey();
            HandlerMethod handler = entry.getValue();
            var methods = info.getMethodsCondition().getMethods();
            var patterns = info.getPathPatternsCondition() != null
                ? info.getPathPatternsCondition().getPatternValues()
                : info.getPatternsCondition().getPatterns();
            for (var rm : methods) {
                if (!MUTATING.contains(rm.name())) {
                    continue;
                }
                for (String pattern : patterns) {
                    live.add(rm.name() + " " + pattern);
                }
            }
        }

        // 1. Every live mutating endpoint must be classified.
        Set<String> unclassified = new TreeSet<>();
        for (String key : live) {
            if (!contract.containsKey(key)) {
                unclassified.add(key);
            }
        }
        // Print the live set so an intentional surface change can be diffed
        // against the resource straight from the test log.
        live.forEach(k -> System.out.println("[write-contract] live: " + k));
        assertThat(unclassified)
            .as("Unclassified mutating endpoints — add each to "
                + "src/test/resources/write-contract.properties with its replay category "
                + "(see docs/reference/write-contract.md). An outbox will replay these:\n  "
                + String.join("\n  ", unclassified))
            .isEmpty();

        // 2. No live endpoint may be NEEDS_FIX (the audit must stay closed).
        var needsFix = new TreeMap<String, String>();
        for (String key : live) {
            if ("NEEDS_FIX".equals(contract.get(key))) {
                needsFix.put(key, contract.get(key));
            }
        }
        assertThat(needsFix.keySet())
            .as("Mutating endpoints with no replay protection (a blind outbox "
                + "replay would duplicate/corrupt). Route through "
                + "SyncWriteContext.idempotentCreate or give them a deterministic id:\n  "
                + String.join("\n  ", needsFix.keySet()))
            .isEmpty();

        // 3. Guard against rot: a classified key that no longer exists live.
        Set<String> stale = new TreeSet<>();
        for (String key : contract.keySet()) {
            if (!live.contains(key)) {
                stale.add(key);
            }
        }
        assertThat(stale)
            .as("Stale classifications in write-contract.txt (endpoint gone) — remove:\n  "
                + String.join("\n  ", stale))
            .isEmpty();
    }

    /**
     * Loads the classification resource. Each non-blank, non-{@code #} line is
     * {@code CATEGORY|METHOD path}, e.g. {@code KEY_GUARDED|POST /api/me/medications}.
     * The pipe delimiter avoids the space/brace escaping a .properties file needs.
     */
    private Map<String, String> loadContract() throws Exception {
        Map<String, String> map = new TreeMap<>();
        try (InputStream in = getClass().getResourceAsStream("/write-contract.txt")) {
            if (in == null) {
                return map;
            }
            BufferedReader reader =
                new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int bar = trimmed.indexOf('|');
                if (bar < 0) {
                    continue;
                }
                map.put(trimmed.substring(bar + 1).strip(), trimmed.substring(0, bar).strip());
            }
        }
        return map;
    }
}
