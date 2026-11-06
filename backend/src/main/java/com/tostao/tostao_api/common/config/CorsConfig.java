package com.tostao.tostao_api.common.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/** CORS de uma única origem por ambiente, com credenciais (cookie). Sem curingas. */
@Configuration
class CorsConfig {

	@Bean
	CorsConfigurationSource corsConfigurationSource(SecurityProperties props) {
		CorsConfiguration cors = new CorsConfiguration();
		cors.setAllowedOrigins(List.of(props.corsOrigin()));
		cors.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
		cors.setAllowedHeaders(List.of("Content-Type"));
		cors.setAllowCredentials(true);
		cors.setMaxAge(3600L);
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/api/**", cors);
		return source;
	}

}
