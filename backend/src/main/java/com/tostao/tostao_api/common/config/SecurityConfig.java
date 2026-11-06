package com.tostao.tostao_api.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

/**
 * Base de segurança da fase 0: sessão stateless, headers restritivos e tudo fechado,
 * exceto a rota de saúde. A autenticação por cookie JWT entra na spec 001.
 */
@Configuration
class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http
				// CSRF desligado: o cookie de sessão será SameSite=Lax (ver constituição do projeto)
				.csrf(AbstractHttpConfigurer::disable)
				.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.headers(h -> h
						.contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
						.frameOptions(f -> f.deny())
						.referrerPolicy(r -> r.policy(ReferrerPolicy.NO_REFERRER)))
				.authorizeHttpRequests(a -> a
						.requestMatchers("/api/v1/health", "/api/v1/health/**").permitAll()
						.anyRequest().denyAll());
		return http.build();
	}

}
