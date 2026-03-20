package net.topikachu.rag.service.etl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class EtlJobStarter {

    public void start(Mono<Void> job, String description) {
        job.subscribe(
                null,
                error -> log.error("{} failed", description, error),
                () -> log.info("{} finished", description));
    }
}
