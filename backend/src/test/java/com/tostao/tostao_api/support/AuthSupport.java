package com.tostao.tostao_api.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import jakarta.servlet.http.Cookie;

/** Helpers de teste para criar contas e obter o cookie de sessão. */
public final class AuthSupport {

	public static final String PASSWORD = "Senha-Forte-123";
	public static final String COOKIE = "tostao_session";

	private AuthSupport() {
	}

	public static String newEmail() {
		return "user-" + UUID.randomUUID() + "@example.com";
	}

	public static String credentials(String email, String password) {
		return "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password);
	}

	public static MvcResult register(MockMvc mvc, String email) throws Exception {
		String body = "{\"name\":\"Teste\",\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD);
		return mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
				.andReturn();
	}

	public static MvcResult login(MockMvc mvc, String email, String password) throws Exception {
		return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content(credentials(email, password))).andReturn();
	}

	/** Valor do cookie de sessão presente no Set-Cookie da resposta. */
	public static String tokenOf(MvcResult result) {
		String header = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
		if (header == null || !header.startsWith(COOKIE + "=")) {
			throw new IllegalStateException("Resposta sem cookie de sessão: " + result.getResponse().getStatus());
		}
		return header.substring(COOKIE.length() + 1, header.indexOf(';'));
	}

	/** Cria a conta e devolve o token da sessão aberta no cadastro. */
	public static String registerAndGetToken(MockMvc mvc, String email) throws Exception {
		return tokenOf(register(mvc, email));
	}

	public static Cookie session(String token) {
		return new Cookie(COOKIE, token);
	}

}
