# Build stage
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /workspace
COPY gradle gradle
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY app/build.gradle.kts app/build.gradle.kts
COPY api/build.gradle.kts api/build.gradle.kts
COPY core/build.gradle.kts core/build.gradle.kts
COPY persistence/build.gradle.kts persistence/build.gradle.kts
COPY integrations/build.gradle.kts integrations/build.gradle.kts
# Prime the dependency cache without source. Failures here are not fatal —
# the source-copy bootJar step below will still resolve everything.
RUN ./gradlew --no-daemon :app:dependencies >/dev/null 2>&1 || true
COPY app/src app/src
COPY api/src api/src
COPY core/src core/src
COPY persistence/src persistence/src
COPY integrations/src integrations/src
RUN ./gradlew --no-daemon :app:bootJar -x test

# Runtime stage
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /workspace/app/build/libs/app.jar /app/app.jar
EXPOSE 8080
# MaxRAMPercentage=65 lets the heap actually use the 2Gi container. The default
# (25%) would cap the heap at ~512MB even on 2Gi — and meal/image work allocates
# the photo + base64 + generated-image byte[]s on the HEAP, so an untuned heap
# stays as starved as the old 512Mi box. Bounding the heap also turns a memory
# spike into a catchable per-task OutOfMemoryError (→ markAnalysisFailed) instead
# of a cgroup OOM-kill that orphans every in-flight analysis.
ENTRYPOINT ["sh", "-c", "exec java -XX:MaxRAMPercentage=65.0 -jar /app/app.jar"]
