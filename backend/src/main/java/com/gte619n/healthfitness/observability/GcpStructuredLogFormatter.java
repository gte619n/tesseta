package com.gte619n.healthfitness.observability;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import java.util.Map;
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLogFormatter;

/**
 * OBS-003 — renders each log event as a single-line JSON object in the shape
 * Google Cloud Logging's structured-log ingestion understands, so backend logs on
 * Cloud Run are parsed (severity, message, source, labels) instead of landing as
 * opaque text lines. Spring Boot 3.5 ships ECS/GELF/Logstash formats but not GCP,
 * so this is the project's own formatter (wired via {@code logback-spring.xml} +
 * {@code logging.structured.format.console}).
 *
 * <p>Emits the fields the Cloud Logging agent maps by convention:
 * <ul>
 *   <li>{@code severity} — the log level mapped to a Cloud Logging LogSeverity.</li>
 *   <li>{@code message} — the formatted message (+ stack trace when present).</li>
 *   <li>{@code timestamp} — RFC-3339 via {@code seconds}/{@code nanos}.</li>
 *   <li>{@code logging.googleapis.com/trace} / {@code /spanId} — the Micrometer
 *       correlation ids from the MDC ({@code traceId}/{@code spanId}) when tracing
 *       is on, so log lines join to their request trace.</li>
 *   <li>every remaining MDC entry as a top-level field (request correlation).</li>
 * </ul>
 */
public class GcpStructuredLogFormatter implements StructuredLogFormatter<ILoggingEvent> {

    private final JsonWriter<ILoggingEvent> writer = JsonWriter
        .<ILoggingEvent>of(members -> {
            members.add("severity", event -> severityOf(event.getLevel().levelStr));
            members.add("message", GcpStructuredLogFormatter::messageWithStackTrace);
            members.add("logger", ILoggingEvent::getLoggerName);
            members.add("thread", ILoggingEvent::getThreadName);
            members.add("timestamp", event -> event.getInstant().toString());
            members.add("logging.googleapis.com/trace",
                    event -> event.getMDCPropertyMap().get("traceId"))
                .whenNotNull();
            members.add("logging.googleapis.com/spanId",
                    event -> event.getMDCPropertyMap().get("spanId"))
                .whenNotNull();
            members.addMapEntries(GcpStructuredLogFormatter::extraMdc);
        })
        .withNewLineAtEnd();

    @Override
    public String format(ILoggingEvent event) {
        return writer.writeToString(event);
    }

    /** MDC minus the trace/span keys already surfaced as Cloud Logging fields. */
    private static Map<String, String> extraMdc(ILoggingEvent event) {
        Map<String, String> mdc = event.getMDCPropertyMap();
        if (mdc == null || mdc.isEmpty()) {
            return Map.of();
        }
        java.util.LinkedHashMap<String, String> out = new java.util.LinkedHashMap<>(mdc);
        out.remove("traceId");
        out.remove("spanId");
        return out;
    }

    private static String messageWithStackTrace(ILoggingEvent event) {
        String msg = event.getFormattedMessage();
        IThrowableProxy tp = event.getThrowableProxy();
        if (tp == null) {
            return msg;
        }
        return msg + "\n" + ThrowableProxyUtil.asString(tp);
    }

    /** Map a Logback level to a Cloud Logging LogSeverity string. */
    private static String severityOf(String level) {
        return switch (level) {
            case "TRACE" -> "DEBUG";
            case "DEBUG" -> "DEBUG";
            case "INFO" -> "INFO";
            case "WARN" -> "WARNING";
            case "ERROR" -> "ERROR";
            default -> "DEFAULT";
        };
    }
}
