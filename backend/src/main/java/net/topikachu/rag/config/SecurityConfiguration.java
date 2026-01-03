package net.topikachu.rag.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {
	@Bean
	public SecurityFilterChain securedFilterChainIndex(HttpSecurity http) throws Exception {
		http
				.securityMatcher("/api/v1/index")
				.httpBasic(basic -> basic.realmName("Restricted Area"))
				.authorizeHttpRequests(authorize -> authorize.anyRequest().hasRole("ADMIN"))
				.cors(Customizer.withDefaults())
				.csrf(AbstractHttpConfigurer::disable);
		return http.build();
	}

	@Bean
	public SecurityFilterChain securedFilterChainChat(HttpSecurity http) throws Exception {
		http
				.securityMatcher("/api/v1/chat")
				.httpBasic(basic -> basic.realmName("Restricted Area"))
				.authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
				.cors(Customizer.withDefaults())
				.csrf(AbstractHttpConfigurer::disable);
		return http.build();
	}

	@Bean
	public SecurityFilterChain securedFilterChainDocs(HttpSecurity http) throws Exception {
		http
				.securityMatcher("/api/v1/docs/**")
				.httpBasic(basic -> basic.realmName("Restricted Area"))
				.authorizeHttpRequests(authorize -> authorize.anyRequest().hasRole("ADMIN"))
				.cors(Customizer.withDefaults())
				.csrf(AbstractHttpConfigurer::disable);
		return http.build();
	}

	@Bean
	public CorsConfigurationSource corsConfigurationSource() {
		CorsConfiguration configuration = new CorsConfiguration();
		configuration.setAllowedOrigins(List.of("http://localhost:5173"));
		configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
		configuration.setAllowedHeaders(List.of("*"));
		configuration.setAllowCredentials(true);

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", configuration);
		return source;
	}

	@Bean
	public UserDetailsService users() {
		UserDetails user = User.builder()
				.username("user")
				.password("{bcrypt}$2a$10$kzpUDZ8kHIjloT6SKcfG/uU8wvCBDKeQ1T9iKJ.ycnGBmtnNTAHpG")
				.roles("USER")
				.build();
		UserDetails admin = User.builder()
				.username("admin")
				.password("{bcrypt}$2a$10$yuIsgd1yleSSW.stYcCOl.KZbGBxG0KFS7VB4XeV4GYF1.oPz7L42")
				.roles("USER", "ADMIN")
				.build();
		return new InMemoryUserDetailsManager(user, admin);
	}

}
