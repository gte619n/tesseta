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

# Runtime stage
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /workspace/build/libs/app.jar /app/app.jar
EXPOSE 8080
# MaxRAMPercentage=65 lets the heap actually use the 2Gi container. The default
# (25%) would cap the heap at ~512MB even on 2Gi — and meal/image work allocates
# the photo + base64 + generated-image byte[]s on the HEAP, so an untuned heap
# stays as starved as the old 512Mi box. Bounding the heap also turns a memory
# spike into a catchable per-task OutOfMemoryError (→ markAnalysisFailed) instead
# of a cgroup OOM-kill that orphans every in-flight analysis.
ENTRYPOINT ["sh", "-c", "exec java -XX:MaxRAMPercentage=65.0 -jar /app/app.jar"]
