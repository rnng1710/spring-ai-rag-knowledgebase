package net.topikachu.rag.api;

import net.topikachu.rag.service.sse.SseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.security.Principal;

@RestController
@RequestMapping("/api/v1/sse")
public class SseController {

    @Autowired
    private SseService sseService;

    @GetMapping(path = "/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(Principal principal) {
        if (principal == null) {
            throw new IllegalStateException("Unauthorized for SSE");
        }
        return sseService.subscribe(principal.getName());
    }
}
