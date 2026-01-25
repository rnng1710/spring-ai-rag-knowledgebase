package net.topikachu.rag.api;

import net.topikachu.rag.common.AjaxResult;
import net.topikachu.rag.service.TokenService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    @Autowired
    private TokenService tokenService;

    @Autowired
    private JwtDecoder jwtDecoder;

    // Hardcoded users: admin/admin, user/user
    private static final Map<String, String> USERS = Map.of(
            "admin", "admin",
            "user", "user");

    @PostMapping("/login")
    public AjaxResult login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");

        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            return AjaxResult.error(400, "Username and password are required");
        }

        if (!USERS.containsKey(username) || !USERS.get(username).equals(password)) {
            return AjaxResult.error(401, "Invalid username or password");
        }

        String role = "admin".equals(username) ? "ADMIN" : "USER";
        return AjaxResult.success(tokenService.generateTokens(username, role));
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

            if (!USERS.containsKey(username)) {
                return AjaxResult.error(401, "User not found");
            }

            String role = "admin".equals(username) ? "ADMIN" : "USER";

            return AjaxResult.success(tokenService.generateTokens(username, role));

        } catch (Exception e) {
            return AjaxResult.error(401, "Invalid refresh token: " + e.getMessage());
        }
    }
}
