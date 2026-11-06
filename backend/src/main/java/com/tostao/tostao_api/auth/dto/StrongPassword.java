package com.tostao.tostao_api.auth.dto;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/** Senha de 8 a 72 bytes em UTF-8, com ao menos uma letra e um número. */
@Documented
@Constraint(validatedBy = StrongPasswordValidator.class)
@Target({ ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT })
@Retention(RetentionPolicy.RUNTIME)
public @interface StrongPassword {

	String message() default "deve ter de 8 a 72 bytes, com ao menos uma letra e um número";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};

}
