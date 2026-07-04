package com.gte619n.healthfitness.testsupport.firestore;

import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Boots a Cloud Firestore emulator for integration tests — no Docker required.
 *
 * <p>Uses the {@code firebase} CLI's bundled Firestore emulator (the same one
 * {@code infra/scripts/uat.sh} relies on), started on an ephemeral port with a
 * throwaway {@code firebase.json}. A single instance is shared across the whole
 * test JVM and torn down by a shutdown hook.
 *
 * <p>{@code FirestoreConfig} already honours {@code FIRESTORE_EMULATOR_HOST};
 * context-based tests set it via {@code @DynamicPropertySource} (see
 * {@link FirestoreEmulatorExtension}). Context-free tests can talk to the
 * emulator directly with {@link #newClient()}.
 *
 * <p>If the {@code firebase} CLI is absent the behaviour depends on the
 * {@code firestore.emulator.required} system property: when {@code true} (set by
 * CI) startup throws so the build fails loudly; otherwise the caller is expected
 * to skip via {@code Assumptions}, so local runs without firebase-tools don't
 * break.
 */
public final class FirestoreEmulator {

    /** Fixed demo project id — the emulator never contacts real GCP with it. */
    public static final String PROJECT_ID = "demo-test";

    private static final Object LOCK = new Object();
    private static volatile FirestoreEmulator instance;

    private final String hostPort;
    private final Process process;

    private FirestoreEmulator(String hostPort, Process process) {
        this.hostPort = hostPort;
        this.process = process;
    }

    /** True when the {@code firebase} CLI is on the PATH. */
    public static boolean available() {
        try {
            Process p = new ProcessBuilder("firebase", "--version")
                .redirectErrorStream(true)
                .start();
            return p.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Starts the emulator once per JVM (idempotent), blocking until ready. */
    public static FirestoreEmulator ensureStarted() {
        FirestoreEmulator local = instance;
        if (local != null) return local;
        synchronized (LOCK) {
            if (instance != null) return instance;
            boolean required = Boolean.parseBoolean(
                System.getProperty("firestore.emulator.required", "false"));
            if (!available()) {
                if (required) {
                    throw new IllegalStateException(
                        "firebase CLI not found but firestore.emulator.required=true. "
                            + "Install firebase-tools (npm i -g firebase-tools).");
                }
                throw new EmulatorUnavailableException(
                    "firebase CLI not found; skipping emulator-backed test.");
            }
            instance = start();
            return instance;
        }
    }

    private static FirestoreEmulator start() {
        int port = chosenPort();
        String host = "127.0.0.1:" + port;
        try {
            Path dir = Files.createTempDirectory("fs-emu");
            Path log = dir.resolve("emulator.log");
            Files.writeString(dir.resolve("firebase.json"),
                "{ \"emulators\": { \"firestore\": { \"host\": \"127.0.0.1\", \"port\": "
                    + port + " } } }");
            Process p = new ProcessBuilder(
                "firebase", "emulators:start", "--only", "firestore",
                "--project", PROJECT_ID)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
            FirestoreEmulator emu = new FirestoreEmulator(host, p);
            Runtime.getRuntime().addShutdownHook(new Thread(emu::stop));
            waitUntilReady(port, emu, p, log);
            return emu;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start Firestore emulator", e);
        }
    }

    /**
     * Two-stage readiness: (1) HTTP 200 on the firestore port confirms the
     * process is serving, then (2) a real Firestore write/read confirms the
     * gRPC data plane is actually usable. Polling the data plane directly makes
     * readiness authoritative regardless of startup ordering.
     */
    private static void waitUntilReady(int port, FirestoreEmulator emu, Process process, Path log) {
        HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();
        HttpRequest probe = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/"))
            .timeout(Duration.ofSeconds(2))
            .GET().build();
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        boolean httpUp = false;
        String[] lastProbeError = {"<none>"};
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new IllegalStateException(
                    "Firestore emulator exited during startup (exit=" + process.exitValue()
                        + "). Log: " + readTail(log));
            }
            if (!httpUp) {
                try {
                    HttpResponse<Void> r = http.send(probe, HttpResponse.BodyHandlers.discarding());
                    httpUp = r.statusCode() == 200;
                } catch (IOException ignored) {
                    // process not serving HTTP yet
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted waiting for emulator", e);
                }
            }
            // Only probe the data plane once HTTP is up. A gRPC client created
            // against a not-yet-ready emulator caches a broken channel and never
            // recovers, so each probe uses a FRESH short-lived client.
            if (httpUp && dataPlaneReady(emu, lastProbeError)) {
                return;
            }
            sleep(200);
        }
        throw new IllegalStateException(
            "Firestore emulator did not become ready within 90s (httpUp=" + httpUp
                + ", host=" + emu.hostPort + "). Last probe error: " + lastProbeError[0]
                + ". Log: " + readTail(log));
    }

    /** One write via a fresh client against the emulator; true once it succeeds. */
    private static boolean dataPlaneReady(FirestoreEmulator emu, String[] lastError) {
        try (Firestore fs = emu.newClient()) {
            // NB: collection ids matching __.*__ are reserved by Firestore and
            // rejected with INVALID_ARGUMENT — don't use double-underscore here.
            fs.collection("readiness_probe").document("probe")
                .set(java.util.Map.of("ok", true))
                .get(5, java.util.concurrent.TimeUnit.SECONDS);
            return true;
        } catch (Exception e) {
            Throwable root = e;
            while (root.getCause() != null) root = root.getCause();
            lastError[0] = e.getClass().getSimpleName() + " -> "
                + root.getClass().getSimpleName() + ": " + root.getMessage();
            return false;
        }
    }

    private static String readTail(Path log) {
        try {
            var lines = Files.readAllLines(log);
            int from = Math.max(0, lines.size() - 12);
            return String.join(" | ", lines.subList(from, lines.size()));
        } catch (IOException e) {
            return "<no log>";
        }
    }

    /** {@code host:port} suitable for {@code FIRESTORE_EMULATOR_HOST}. */
    public String hostPort() {
        return hostPort;
    }

    /**
     * A Firestore client wired to this emulator — mirrors the production
     * {@code FirestoreConfig} exactly (project id + {@code setEmulatorHost}).
     * Note: do NOT also call {@code setCredentials(NoCredentials)} — that
     * overrides the emulator channel {@code setEmulatorHost} installs and the
     * client silently falls back to the real {@code firestore.googleapis.com}
     * endpoint (DirectPath), surfacing as a confusing {@code NoRouteToHost}.
     */
    public Firestore newClient() {
        return FirestoreOptions.newBuilder()
            .setProjectId(PROJECT_ID)
            .setEmulatorHost(hostPort)
            .build()
            .getService();
    }

    /** Deletes all documents so each test starts from a clean database. */
    public void clearData() {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest reset = HttpRequest.newBuilder()
            .uri(URI.create("http://" + hostPort + "/emulator/v1/projects/"
                + PROJECT_ID + "/databases/(default)/documents"))
            .timeout(Duration.ofSeconds(10))
            .DELETE().build();
        try {
            client.send(reset, HttpResponse.BodyHandlers.discarding());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to clear Firestore emulator data", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted clearing emulator data", e);
        }
    }

    private void stop() {
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }

    /**
     * The Firestore emulator port. Defaults to a fixed low port (8677) rather
     * than an OS-assigned ephemeral one: the Firestore gRPC client fails to
     * reach the emulator on high ephemeral ports in this environment (the
     * channel resolves to the real endpoint), whereas a stable low port works
     * reliably. Override with {@code -Dfirestore.emulator.port} /
     * {@code FIRESTORE_EMULATOR_PORT} (e.g. to parallelise CI shards).
     */
    private static int chosenPort() {
        String configured = System.getProperty("firestore.emulator.port",
            System.getenv("FIRESTORE_EMULATOR_PORT"));
        if (configured != null && !configured.isBlank()) {
            return Integer.parseInt(configured.trim());
        }
        return 8677;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Raised (and typically translated to an assumption) when firebase is absent. */
    public static final class EmulatorUnavailableException extends RuntimeException {
        EmulatorUnavailableException(String message) {
            super(message);
        }
    }
}
