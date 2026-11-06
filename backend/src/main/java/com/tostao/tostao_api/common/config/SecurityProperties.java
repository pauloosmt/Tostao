package com.tostao.tostao_api.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuração de sessão e CORS, sempre vinda de variável de ambiente.
 *
 * @param jwtSecret    segredo HMAC do JWT (mínimo de 32 bytes)
 * @param cookieSecure atributo {@code Secure} do cookie; {@code false} só no ambiente local
 * @param corsOrigin   única origem aceita pelo CORS
 * @param sessionDays  validade deslizante da sessão, em dias
 */
@ConfigurationProperties("tostao.security")
public record SecurityProperties(String jwtSecret, boolean cookieSecure, String corsOrigin, int sessionDays) {
}
