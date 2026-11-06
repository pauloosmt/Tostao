package com.tostao.tostao_api.auth;

import java.time.Duration;

import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import com.tostao.tostao_api.common.config.SecurityProperties;

/** Monta o cookie de sessão: HttpOnly, SameSite=Lax, Secure conforme o ambiente. */
@Component
public class SessionCookieFactory {

	public static final String NAME = "tostao_session";

	private final SecurityProperties props;

	public SessionCookieFactory(SecurityProperties props) {
		this.props = props;
	}

	public ResponseCookie create(String token) {
		return base(token).maxAge(Duration.ofDays(props.sessionDays())).build();
	}

	/** Cookie vazio com {@code Max-Age=0}: o navegador apaga a sessão daquele aparelho. */
	public ResponseCookie clear() {
		return base("").maxAge(Duration.ZERO).build();
	}

	private ResponseCookie.ResponseCookieBuilder base(String value) {
		return ResponseCookie.from(NAME, value)
				.httpOnly(true)
				.secure(props.cookieSecure())
				.sameSite("Lax")
				.path("/api");
	}

}
