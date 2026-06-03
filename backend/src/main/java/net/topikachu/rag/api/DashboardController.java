package net.topikachu.rag.api;

import lombok.RequiredArgsConstructor;
import net.topikachu.rag.common.AjaxResult;
import net.topikachu.rag.service.DashboardService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping("/dashboard/stats")
    public Mono<AjaxResult> stats() {
        return Mono.fromCallable(dashboardService::getStats)
                .subscribeOn(Schedulers.boundedElastic())
                .map(AjaxResult::success);
    }
}
