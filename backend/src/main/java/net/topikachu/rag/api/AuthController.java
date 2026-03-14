package net.topikachu.rag.api;

import net.topikachu.rag.common.AjaxResult;
import net.topikachu.rag.service.SysUserService;
import net.topikachu.rag.service.TokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

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
    public AjaxResult login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");

        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            return AjaxResult.error(400, "Username and password are required");
        }

        try {
            Map<String, String> tokens = sysUserService.login(username, password);
            return AjaxResult.success(tokens);
        } catch (Exception e) {
            return AjaxResult.error(401, e.getMessage());
        }
    }

    @PostMapping("/register")
    @PreAuthorize("hasRole('ADMIN')")
    public AjaxResult register(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");

        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            return AjaxResult.error(400, "Username and password are required");
        }

        try {
            sysUserService.register(username, password);
            return AjaxResult.success("User registered successfully");
        } catch (Exception e) {
            return AjaxResult.error(400, e.getMessage());
        }
    }

    @PostMapping("/change-password")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    public AjaxResult changePassword(@RequestBody Map<String, String> body, Principal principal) {
        if (principal == null || !StringUtils.hasText(principal.getName())) {
            return AjaxResult.error(401, "Unauthorized");
        }

        String username = principal.getName();
        String bodyUsername = body.get("username");
        String oldPassword = body.get("old_password");
        String newPassword = body.get("new_password");

        if (StringUtils.hasText(bodyUsername) && !username.equals(bodyUsername)) {
            return AjaxResult.error(403, "Username mismatch with current user");
        }

        if (!StringUtils.hasText(oldPassword) || !StringUtils.hasText(newPassword)) {
            return AjaxResult.error(400, "old_password and new_password are required");
        }

        try {
            sysUserService.changePassword(username, oldPassword, newPassword);
            return AjaxResult.success("Password changed successfully");
        } catch (Exception e) {
            return AjaxResult.error(400, e.getMessage());
        }
    }

    @PostMapping("/refresh")
    public AjaxResult refresh(@RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody(required = false) Map<String, String> body) {

        String refreshToken = null;
        // 1. Try header (Bearer ...)
        if (StringUtils.hasText(authHeader) && authHeader.startsWith("Bearer ")) {
            refreshToken = authHeader.substring(7);
        }
        // 2. Try body
        if (!StringUtils.hasText(refreshToken) && body != null) {
            refreshToken = body.get("refresh_token");
        }

        if (!StringUtils.hasText(refreshToken)) {
            return AjaxResult.error(400, "Refresh token is required");
        }

        try {
            Jwt jwt = jwtDecoder.decode(refreshToken);

            // Check expiry
            if (jwt.getExpiresAt() != null && jwt.getExpiresAt().isBefore(Instant.now())) {
                return AjaxResult.error(401, "Refresh token expired");
            }

            // Check type
            String type = jwt.getClaim("type");
            if (!"refresh".equals(type)) {
                return AjaxResult.error(401, "Invalid token type");
            }

            String username = jwt.getSubject();

            // Re-verify user existence
            if (sysUserService.findByUsername(username) == null) {
                return AjaxResult.error(401, "User not found");
            }

            // Assume refreshes keep the same role (or re-fetch from DB if needed, but
            // tokenService usually just needs username/role)
            // Ideally we re-fetch role from DB:
            String role = sysUserService.findByUsername(username).getRole();

            return AjaxResult.success(tokenService.generateTokens(username, role));

        } catch (Exception e) {
            return AjaxResult.error(401, "Invalid refresh token: " + e.getMessage());
        }
    }
}
