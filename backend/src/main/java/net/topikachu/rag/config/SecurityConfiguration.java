package net.topikachu.rag.config;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity(prePostEnabled = true) // <--- 关键修改1：开启注解权限控制
public class SecurityConfiguration {

	@Bean
	public SecurityFilterChain apiV1(HttpSecurity http,
									 Converter<Jwt, ? extends AbstractAuthenticationToken> jwtAuthenticationConverter) throws Exception {
		http
				// 1. 作用域限制：只对 /api/v1/** 生效
				.securityMatcher("/api/v1/**")

				// 2. 基础设置：开启 CORS，禁用 Session（无状态）
				.cors(Customizer.withDefaults())
				.sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

				// 3. 关键修改2：彻底禁用 CSRF（API 服务标准做法）
				// 既然我们主要使用 Authorization Header，就不需要 CSRF Token
				.csrf(AbstractHttpConfigurer::disable)

				// 禁用不需要的 Basic Auth 和 Form Login
				.httpBasic(AbstractHttpConfigurer::disable)
				.formLogin(AbstractHttpConfigurer::disable)

				// 4. 关键修改3：极简的 URL 权限配置
				.authorizeHttpRequests(auth -> auth
						// (A) 仅放行登录与刷新 token
						.requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/refresh").permitAll()

						// (B) 剩下的所有 /api/v1/** 请求，只要带了合法的 Token 就能访问
						// 具体的 USER/ADMIN 权限区别，将移到 Controller 方法上使用 @PreAuthorize 配置
						.anyRequest().authenticated()
				)

				// 5. 异常处理：返回标准的 401/403 而不是跳转页面
				.exceptionHandling(eh -> eh
						.authenticationEntryPoint((req, res, ex) -> res.sendError(HttpServletResponse.SC_UNAUTHORIZED))
						.accessDeniedHandler((req, res, ex) -> res.sendError(HttpServletResponse.SC_FORBIDDEN)))

				// 6. 资源服务器配置（解析 JWT）
				.oauth2ResourceServer(oauth2 -> oauth2
						.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)));

		return http.build();
	}

	/**
	 * 将 JWT 中的 roles claim 转换为 Spring Security 识别的 ROLE_xxx 权限
	 */
	@Bean
	public Converter<Jwt, ? extends AbstractAuthenticationToken> jwtAuthenticationConverter() {
		JwtGrantedAuthoritiesConverter gac = new JwtGrantedAuthoritiesConverter();
		gac.setAuthoritiesClaimName("roles"); // 你的 JWT 载荷中存角色的字段名
		gac.setAuthorityPrefix("ROLE_");      // 转换后自动加前缀，如 ROLE_ADMIN

		JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
		converter.setJwtGrantedAuthoritiesConverter(gac);
		return converter;
	}

	@Bean
	public JwtDecoder jwtDecoder(@Value("${security.jwt.secret}") String secret) {
		SecretKey key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
		return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
	}

	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	public CorsConfigurationSource corsConfigurationSource() {
		CorsConfiguration cfg = new CorsConfiguration();
		// 允许前端地址，开发时可用 "*"
		cfg.setAllowedOrigins(List.of("http://localhost:5173"));
		cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
		cfg.setAllowCredentials(true);
		cfg.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Requested-With"));

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", cfg);
		return source;
	}
}
