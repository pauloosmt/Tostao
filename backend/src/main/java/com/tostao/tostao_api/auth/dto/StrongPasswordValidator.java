package com.tostao.tostao_api.auth.dto;

import com.tostao.tostao_api.auth.PasswordPolicy;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

class StrongPasswordValidator implements ConstraintValidator<StrongPassword, String> {

	@Override
	public boolean isValid(String value, ConstraintValidatorContext context) {
		// ausência é tratada por @NotNull, para a mensagem certa
		return value == null || PasswordPolicy.isValid(value);
	}

}
