package net.topikachu.rag.api;

import lombok.RequiredArgsConstructor;
import net.topikachu.rag.service.sse.SseService;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.security.Principal;

@RestController
@RequestMapping("/api/v1/sse")
@RequiredArgsConstructor
public class SseController {

    private final SseService sseService;

    @GetMapping(path = "/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> subscribe(Mono<Principal> principalMono) {
        return principalMono
                .switchIfEmpty(Mono.error(new IllegalStateException("Unauthorized for SSE")))
                .flatMapMany(principal -> sseService.subscribe(principal.getName()));
    }
}
