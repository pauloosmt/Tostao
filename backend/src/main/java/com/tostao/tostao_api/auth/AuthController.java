package com.tostao.tostao_api.auth;

import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.tostao.tostao_api.auth.dto.LoginRequest;
import com.tostao.tostao_api.auth.dto.RegisterRequest;
import com.tostao.tostao_api.user.MeResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

	private final AuthService auth;
	private final SessionCookieFactory cookies;

	AuthController(AuthService auth, SessionCookieFactory cookies) {
		this.auth = auth;
		this.cookies = cookies;
	}

	@PostMapping("/register")
	ResponseEntity<MeResponse> register(@Valid @RequestBody RegisterRequest request) {
		AuthResult result = auth.register(request);
		return ResponseEntity.status(HttpStatus.CREATED)
				.header(HttpHeaders.SET_COOKIE, cookies.create(result.token()).toString())
				.body(result.me());
	}

	@PostMapping("/login")
	ResponseEntity<MeResponse> login(@Valid @RequestBody LoginRequest request) {
		AuthResult result = auth.login(request);
		return ResponseEntity.ok()
				.header(HttpHeaders.SET_COOKIE, cookies.create(result.token()).toString())
				.body(result.me());
	}

	/** Sai só deste aparelho: apaga o cookie. Também responde 204 sem sessão. */
	@PostMapping("/logout")
	ResponseEntity<Void> logout() {
		return ResponseEntity.noContent()
				.header(HttpHeaders.SET_COOKIE, cookies.clear().toString())
				.build();
	}

	/** Sai de todos os aparelhos: invalida todas as sessões da pessoa e apaga o cookie deste aparelho. */
	@PostMapping("/logout-all")
	ResponseEntity<Void> logoutAll(@AuthenticationPrincipal Jwt jwt) {
		auth.logoutAll(UUID.fromString(jwt.getSubject()));
		return ResponseEntity.noContent()
				.header(HttpHeaders.SET_COOKIE, cookies.clear().toString())
				.build();
	}

}
