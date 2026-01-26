package net.topikachu.rag.config;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

	@Bean
	public SecurityFilterChain apiV1(HttpSecurity http,
			Converter<Jwt, ? extends AbstractAuthenticationToken> jwtAuthenticationConverter) throws Exception {
		http
				.securityMatcher("/api/v1/**")
				.cors(Customizer.withDefaults())

				// JWT：无状态
				.sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

				// 禁用 Basic/Form，避免混用
				.httpBasic(AbstractHttpConfigurer::disable)
				.formLogin(AbstractHttpConfigurer::disable)

				/**
				 * CSRF：
				 * - 你 refresh 使用 HttpOnly Cookie => refresh/logout 端点必须考虑 CSRF
				 * - chat/admin/login 使用 Authorization header => 不需要 CSRF，直接忽略
				 *
				 * CookieCsrfTokenRepository 会下发一个可读的 XSRF-TOKEN cookie（非 HttpOnly）
				 * 前端需要把该 cookie 值回传到请求头 X-CSRF-TOKEN
				 */
				.csrf(csrf -> csrf
						.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
						.ignoringRequestMatchers(
								"/api/v1/auth/login",
								"/api/v1/chat/**",
								"/api/v1/admin/**",
								"/api/v1/index",
								"/api/v1/docs/**",
								"/api/v1/upload/**"))

				// RBAC：严格按你的规则
				.authorizeHttpRequests(auth -> auth
						// PUBLIC
						.requestMatchers("/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout")
						.permitAll()

						// USER or ADMIN
						.requestMatchers("/api/v1/chat/**").hasAnyRole("USER", "ADMIN")

						// ADMIN
						.requestMatchers("/api/v1/admin/**", "/api/v1/index").hasRole("ADMIN")
						.requestMatchers("/api/v1/docs/**", "/api/v1/upload/**").hasAnyRole("USER", "ADMIN")

						// 兜底：防遗漏
						.anyRequest().denyAll())

				// 统一 401/403，便于前端做 refresh/提示
				.exceptionHandling(eh -> eh
						.authenticationEntryPoint((req, res, ex) -> res.sendError(HttpServletResponse.SC_UNAUTHORIZED))
						.accessDeniedHandler((req, res, ex) -> res.sendError(HttpServletResponse.SC_FORBIDDEN)))

				// 方案 B 核心：启用 Resource Server 的 JWT 解析
				.oauth2ResourceServer(oauth2 -> oauth2
						.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)));

		return http.build();
	}

	/**
	 * 关键：把 JWT 里的 roles claim 转成 Spring Security 需要的 ROLE_ 前缀权限
	 * 约定你的 access token claims 形如：{"roles":["USER","ADMIN"], ...}
	 */
	@Bean
	public Converter<Jwt, ? extends AbstractAuthenticationToken> jwtAuthenticationConverter() {
		JwtGrantedAuthoritiesConverter gac = new JwtGrantedAuthoritiesConverter();
		gac.setAuthoritiesClaimName("roles");
		gac.setAuthorityPrefix("ROLE_");

		JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
		converter.setJwtGrantedAuthoritiesConverter(gac);
		return converter;
	}

	/**
	 * JwtDecoder：示例使用 HS256
	 * 你签发 access token 也必须使用同一 secret/算法
	 */
	@Bean
	public JwtDecoder jwtDecoder(@Value("${security.jwt.secret}") String secret) {
		SecretKey key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");

		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();

		// 可选加强：issuer 校验（如果你在签发端设置了 iss）
		// OAuth2TokenValidator<Jwt> withIssuer =
		// JwtValidators.createDefaultWithIssuer("your-issuer");
		// decoder.setJwtValidator(withIssuer);

		return decoder;
	}

	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	public CorsConfigurationSource corsConfigurationSource() {
		CorsConfiguration cfg = new CorsConfiguration();
		cfg.setAllowedOrigins(List.of("http://localhost:5173"));
		cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
		cfg.setAllowCredentials(true);

		// JWT + CSRF 必需的 headers
		cfg.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-CSRF-TOKEN", "X-Requested-With"));

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", cfg);
		return source;
	}
}