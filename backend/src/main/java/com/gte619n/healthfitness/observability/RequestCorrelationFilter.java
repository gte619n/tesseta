package com.gte619n.healthfitness.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * OBS-003 — puts a per-request correlation id (and, when present, Cloud Run's
 * trace id) into the SLF4J MDC so every log line emitted while handling a request
 * carries it. The {@link GcpStructuredLogFormatter} surfaces {@code traceId} as
 * Cloud Logging's {@code logging.googleapis.com/trace} field (joining log lines to
 * their request) and copies the remaining MDC entries (e.g. {@code requestId})
 * onto the JSON object.
 *
 * <p>Runs outermost so the ids are set before any downstream filter/controller
 * logs, and always clears the MDC so ids never leak across pooled threads.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestCorrelationFilter extends OncePerRequestFilter {

    /** Cloud Run stamps this on every inbound request. */
    private static final String CLOUD_TRACE_HEADER = "X-Cloud-Trace-Context";

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain
    ) throws ServletException, IOException {
        try {
            MDC.put("requestId", UUID.randomUUID().toString());
            String traceId = traceIdFrom(request.getHeader(CLOUD_TRACE_HEADER));
            if (traceId != null) {
                MDC.put("traceId", traceId);
            }
            chain.doFilter(request, response);
        } finally {
            MDC.remove("requestId");
            MDC.remove("traceId");
        }
    }

    /**
     * Parse the trace id out of {@code X-Cloud-Trace-Context}, whose format is
     * {@code TRACE_ID/SPAN_ID;o=OPTIONS}. Returns just the {@code TRACE_ID}, or
     * null when the header is absent/blank.
     */
    private static String traceIdFrom(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        int slash = header.indexOf('/');
        String id = slash >= 0 ? header.substring(0, slash) : header;
        return id.isBlank() ? null : id;
    }
}
