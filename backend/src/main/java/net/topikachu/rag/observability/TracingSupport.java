package net.topikachu.rag.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

@Component
public class TracingSupport {

    private final ObjectProvider<Tracer> tracerProvider;

    public TracingSupport(ObjectProvider<Tracer> tracerProvider) {
        this.tracerProvider = tracerProvider;
    }

    public void tagCurrent(Map<String, ?> tags) {
        Tracer tracer = tracerProvider.getIfAvailable();
        if (tracer == null) {
            return;
        }
        Span span = tracer.currentSpan();
        if (span == null) {
            return;
        }
        applyTags(span, tags);
    }

    public String getCurrentTraceId() {
        Tracer tracer = tracerProvider.getIfAvailable();
        if (tracer == null) return null;
        Span span = tracer.currentSpan();
        if (span == null) return null;
        return span.context().traceId();
    }

    public <T> Mono<T> traceMono(String name, Map<String, ?> tags, Mono<T> publisher) {
        return Mono.defer(() -> {
            Span span = startSpan(name, tags);
            if (span == null) {
                return publisher;
            }
            return publisher
                    .doOnError(span::error)
                    .doFinally(signalType -> span.end());
        });
    }

    public <T> Flux<T> traceFlux(String name, Map<String, ?> tags, Flux<T> publisher) {
        return Flux.defer(() -> {
            Span span = startSpan(name, tags);
            if (span == null) {
                return publisher;
            }
            return publisher
                    .doOnError(span::error)
                    .doFinally(signalType -> span.end());
        });
    }

    private Span startSpan(String name, Map<String, ?> tags) {
        Tracer tracer = tracerProvider.getIfAvailable();
        if (tracer == null) {
            return null;
        }
        Span span = tracer.nextSpan().name(name).start();
        applyTags(span, tags);
        return span;
    }

    private void applyTags(Span span, Map<String, ?> tags) {
        if (tags == null || tags.isEmpty()) {
            return;
        }
        tags.forEach((key, value) -> {
            if (key == null || value == null) {
                return;
            }
            String normalized = String.valueOf(value);
            if (!normalized.isBlank()) {
                span.tag(key, normalized);
            }
        });
    }
}
