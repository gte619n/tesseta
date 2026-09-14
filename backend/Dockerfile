# Build stage
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /workspace
COPY gradle gradle
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
# Prime the dependency cache without source. Failures here are not fatal —
# the source-copy bootJar step below will still resolve everything.
RUN ./gradlew --no-daemon dependencies >/dev/null 2>&1 || true
COPY src src
RUN ./gradlew --no-daemon bootJar -x test

# CDS stage — build a Class Data Sharing archive (PERF-002).
# Spring Boot 3.5's supported CDS flow (docs.spring.io/spring-boot/reference/
# packaging/class-data-sharing.html + .../packaging/container-images/dockerfiles.html):
#   1. Explode the executable jar with the `tools` jarmode so the classes load
#      off the filesystem (CDS archives can only be created against an exploded
#      layout, not a nested-jar boot archive).
#   2. Do a "training run" that starts the Spring context and exits right after
#      refresh (`-Dspring.context.exit=onRefresh`), dumping the loaded classes
#      to app.jsa via `-XX:ArchiveClassesAtExit`. AOT is left OFF (out of scope;
#      plain CDS only) so no BeanFactory pre-processing is needed.
# The training run loads the full context, so it must not require live external
# credentials — every AI/GCS bean is env-gated (enabled=... defaults still
# construct fakes without a key), and Firestore is not contacted before refresh.
FROM eclipse-temurin:21-jdk AS cds
WORKDIR /training
COPY --from=builder /workspace/build/libs/app.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --destination extracted
# Training run against the exploded launcher: start, reach context-refresh, exit,
# and persist the shared archive. DECISION (operator, 2026-09-13): the training
# run is NON-FATAL — if it fails (e.g. a fail-closed bean needs a real credential
# the build environment doesn't have), the image still builds, just without a
# pre-baked archive. `|| true` swallows the training exit code; the `:> app.jsa`
# fallback guarantees the file exists so the runtime COPY never errors. At runtime
# `-XX:+AutoCreateSharedArchive` treats an empty/invalid archive as "regenerate,
# don't fail" — so a failed training run costs the startup win, never the deploy.
RUN cd extracted \
    && (java -XX:ArchiveClassesAtExit=/training/app.jsa \
            -Dspring.context.exit=onRefresh \
            -jar app.jar || true) \
    && ([ -s /training/app.jsa ] || : > /training/app.jsa)

# Runtime stage
FROM eclipse-temurin:21-jre
WORKDIR /app
# Drop the base image's `pebble` service-manager Go binary: it's unused by our
# `java -jar` entrypoint but carries several HIGH Go stdlib / x-net CVEs that
# fail the image vulnerability scan (no Go net paths are reachable in a Java
# app). Deterministically removing it clears those findings; a no-op if absent.
RUN rm -f /usr/bin/pebble
# Copy the exploded application layout (extracted/app.jar + extracted/lib/) and
# the CDS archive produced above. `extract` (no --layers) yields a restructured
# app.jar that loads its dependencies from the sibling lib/ dir off the
# filesystem — the layout CDS was trained against.
COPY --from=cds /training/extracted/ /app/
COPY --from=cds /training/app.jsa /app/app.jsa
EXPOSE 8080
# MaxRAMPercentage=65 lets the heap actually use the 2Gi container. The default
# (25%) would cap the heap at ~512MB even on 2Gi — and meal/image work allocates
# the photo + base64 + generated-image byte[]s on the HEAP, so an untuned heap
# stays as starved as the old 512Mi box. Bounding the heap also turns a memory
# spike into a catchable per-task OutOfMemoryError (→ markAnalysisFailed) instead
# of a cgroup OOM-kill that orphans every in-flight analysis.
#
# CDS (PERF-002): -XX:SharedArchiveFile loads classes from the pre-dumped
# app.jsa to cut the measured 15.7s p95 cold start. -XX:+AutoCreateSharedArchive
# makes the flag self-healing — if app.jsa is missing or JVM-incompatible the
# JVM regenerates/ignores it instead of failing to boot (so a CDS problem can
# never take the service down; worst case is a slower start). We run the exploded
# app.jar (same layout the archive was trained on) rather than the original
# nested-jar boot archive so the class paths in the archive match at runtime.
ENTRYPOINT ["sh", "-c", "exec java -XX:MaxRAMPercentage=65.0 -XX:SharedArchiveFile=/app/app.jsa -XX:+AutoCreateSharedArchive -jar /app/app.jar"]
