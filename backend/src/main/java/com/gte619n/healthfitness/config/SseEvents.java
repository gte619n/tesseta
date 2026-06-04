package com.gte619n.healthfitness.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Serialize a payload as JSON and push it as a named SSE event — the body that
 * was copy-pasted as a private {@code sendEvent} helper in every streaming chat
 * controller. On any send failure (typically a disconnected client) the emitter
 * is completed with the error so the streaming task stops promptly rather than
 * writing into a dead response.
 */
public final class SseEvents {

    private SseEvents() {}

    public static void send(SseEmitter emitter, ObjectMapper json, String name, Object data) {
        try {
            emitter.send(SseEmitter.event()
                .name(name)
                .data(json.writeValueAsString(data), MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    }
}
