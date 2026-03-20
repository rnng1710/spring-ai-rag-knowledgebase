package net.topikachu.rag.api;

import lombok.RequiredArgsConstructor;
import net.topikachu.rag.common.AjaxResult;
import net.topikachu.rag.service.SysUserService;
import net.topikachu.rag.service.TokenService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.security.Principal;
import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final TokenService tokenService;
    private final SysUserService sysUserService;
    private final JwtDecoder jwtDecoder;

    @PostMapping("/login")
    public Mono<AjaxResult> login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");

        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            return Mono.just(AjaxResult.error(400, "Username and password are required"));
        }

        return Mono.fromCallable(() -> sysUserService.login(username, password))
                .subscribeOn(Schedulers.boundedElastic())
                .map(AjaxResult::success)
                .onErrorResume(e -> Mono.just(AjaxResult.error(401, e.getMessage())));
    }

    @PostMapping("/register")
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<AjaxResult> register(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");

        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            return Mono.just(AjaxResult.error(400, "Username and password are required"));
        }

        return Mono.fromRunnable(() -> sysUserService.register(username, password))
                .subscribeOn(Schedulers.boundedElastic())
                .thenReturn(AjaxResult.success("User registered successfully", null))
                .onErrorResume(e -> Mono.just(AjaxResult.error(400, e.getMessage())));
    }

    @PostMapping("/change-password")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    public Mono<AjaxResult> changePassword(@RequestBody Map<String, String> body, Mono<Principal> principalMono) {
        return principalMono.switchIfEmpty(Mono.error(new IllegalStateException("Unauthorized")))
                .flatMap(principal -> {
                    String username = principal.getName();
                    String bodyUsername = body.get("username");
                    String oldPassword = body.get("old_password");
                    String newPassword = body.get("new_password");

                    if (StringUtils.hasText(bodyUsername) && !username.equals(bodyUsername)) {
                        return Mono.just(AjaxResult.error(403, "Username mismatch with current user"));
                    }
                    if (!StringUtils.hasText(oldPassword) || !StringUtils.hasText(newPassword)) {
                        return Mono.just(AjaxResult.error(400, "old_password and new_password are required"));
                    }

                    return Mono.fromRunnable(() -> sysUserService.changePassword(username, oldPassword, newPassword))
                            .subscribeOn(Schedulers.boundedElastic())
                            .thenReturn(AjaxResult.success("Password changed successfully", null))
                            .onErrorResume(e -> Mono.just(AjaxResult.error(400, e.getMessage())));
                })
                .onErrorResume(e -> Mono.just(AjaxResult.error(401, e.getMessage())));
    }

    @PostMapping("/refresh")
    public Mono<AjaxResult> refresh(@RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody(required = false) Map<String, String> body) {
        String refreshToken = null;
        if (StringUtils.hasText(authHeader) && authHeader.startsWith("Bearer ")) {
            refreshToken = authHeader.substring(7);
        }
        if (!StringUtils.hasText(refreshToken) && body != null) {
            refreshToken = body.get("refresh_token");
        }
        if (!StringUtils.hasText(refreshToken)) {
            return Mono.just(AjaxResult.error(400, "Refresh token is required"));
        }

        final String token = refreshToken;
        return Mono.fromCallable(() -> {
                    Jwt jwt = jwtDecoder.decode(token);
                    if (jwt.getExpiresAt() != null && jwt.getExpiresAt().isBefore(Instant.now())) {
                        throw new IllegalArgumentException("Refresh token expired");
                    }
                    if (!"refresh".equals(jwt.getClaim("type"))) {
                        throw new IllegalArgumentException("Invalid token type");
                    }
                    String username = jwt.getSubject();
                    var user = sysUserService.findByUsername(username);
                    if (user == null) {
                        throw new IllegalArgumentException("User not found");
                    }
                    return tokenService.generateTokens(username, user.getRole());
                })
                .subscribeOn(Schedulers.boundedElastic())
                .map(AjaxResult::success)
                .onErrorResume(e -> Mono.just(AjaxResult.error(401, "Invalid refresh token: " + e.getMessage())));
    }
}
