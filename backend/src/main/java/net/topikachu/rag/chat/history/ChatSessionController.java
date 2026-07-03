package net.topikachu.rag.chat.history;

import lombok.RequiredArgsConstructor;
import net.topikachu.rag.auth.CurrentUserContext;
import net.topikachu.rag.auth.CurrentUserContextService;
import net.topikachu.rag.chat.history.dto.RenameChatSessionRequest;
import net.topikachu.rag.common.AjaxResult;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.security.Principal;

@RestController
@RequestMapping("/api/v1/chat/sessions")
@RequiredArgsConstructor
public class ChatSessionController {

    private final ChatHistoryService chatHistoryService;
    private final CurrentUserContextService currentUserContextService;

    @GetMapping
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    public Mono<AjaxResult> listSessions(@RequestParam(required = false) String keyword,
                                         @RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "20") int size,
                                         Mono<Principal> principalMono) {
        return principalMono.flatMap(principal -> {
            CurrentUserContext user = currentUserContextService.resolveByUsername(principal.getName());
            return chatHistoryService.listSessions(user.userId(), keyword, page, size)
                    .map(AjaxResult::success);
        });
    }

    @GetMapping("/{conversationId}/messages")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    public Mono<AjaxResult> listMessages(@PathVariable String conversationId,
                                         Mono<Principal> principalMono) {
        return principalMono.flatMap(principal -> {
            CurrentUserContext user = currentUserContextService.resolveByUsername(principal.getName());
            return chatHistoryService.listMessages(conversationId, user.userId())
                    .map(AjaxResult::success);
        });
    }

    @PatchMapping("/{conversationId}")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    public Mono<AjaxResult> rename(@PathVariable String conversationId,
                                   @RequestBody RenameChatSessionRequest request,
                                   Mono<Principal> principalMono) {
        if (request == null || !StringUtils.hasText(request.title())) {
            return Mono.just(AjaxResult.error("标题不能为空"));
        }
        return principalMono.flatMap(principal -> {
            CurrentUserContext user = currentUserContextService.resolveByUsername(principal.getName());
            return chatHistoryService.renameSession(conversationId, user.userId(), request.title())
                    .map(updated -> updated ? AjaxResult.success() : AjaxResult.error("会话不存在或标题无效"));
        });
    }

    @DeleteMapping("/{conversationId}")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    public Mono<AjaxResult> delete(@PathVariable String conversationId,
                                   Mono<Principal> principalMono) {
        return principalMono.flatMap(principal -> {
            CurrentUserContext user = currentUserContextService.resolveByUsername(principal.getName());
            return chatHistoryService.softDeleteSession(conversationId, user.userId())
                    .map(deleted -> deleted ? AjaxResult.success() : AjaxResult.error("会话不存在"));
        });
    }
}
