package com.tostao.tostao_api.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

import com.tostao.tostao_api.auth.CookieBearerTokenResolver;
import com.tostao.tostao_api.auth.SessionCookieFactory;
import com.tostao.tostao_api.auth.SessionRenewalFilter;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.common.error.ProblemSecurityHandler;

/**
 * Sessão stateless por JWT em cookie HttpOnly, headers restritivos e tudo fechado, exceto saúde,
 * cadastro, entrada e saída. A credencial vem só do cookie, nunca do header {@code Authorization}.
 */
@Configuration
class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, SessionTokenService tokens,
			SessionCookieFactory cookies, @Value("${springdoc.swagger-ui.enabled:false}") boolean docsEnabled)
			throws Exception {
		ProblemSecurityHandler problems = new ProblemSecurityHandler();
		http
				// CSRF desligado: o cookie de sessão é SameSite=Lax (ver constituição do projeto)
				.csrf(AbstractHttpConfigurer::disable)
				.cors(Customizer.withDefaults())
				.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.headers(h -> h
						.contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
						.frameOptions(f -> f.deny())
						.referrerPolicy(r -> r.policy(ReferrerPolicy.NO_REFERRER)))
				.oauth2ResourceServer(o -> o
						.bearerTokenResolver(new CookieBearerTokenResolver())
						.authenticationEntryPoint(problems)
						.accessDeniedHandler(problems)
						.jwt(Customizer.withDefaults()))
				.exceptionHandling(e -> e
						.authenticationEntryPoint(problems)
						.accessDeniedHandler(problems))
				.addFilterAfter(new SessionRenewalFilter(tokens, cookies), BearerTokenAuthenticationFilter.class)
				.authorizeHttpRequests(a -> {
					a.requestMatchers("/api/v1/health", "/api/v1/health/**").permitAll();
					a.requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login",
							"/api/v1/auth/logout").permitAll();
					if (docsEnabled) {
						a.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll();
					}
					a.anyRequest().authenticated();
				});
		return http.build();
	}

}
