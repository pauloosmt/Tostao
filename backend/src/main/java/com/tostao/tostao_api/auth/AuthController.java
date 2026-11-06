package com.tostao.tostao_api.auth;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

}
