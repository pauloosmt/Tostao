package com.tostao.tostao_api.category.dto;

import java.util.UUID;

import com.tostao.tostao_api.category.CategoryKind;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Edição de categoria: nome, cor e ícone (a edição é completa, então cor e ícone ausentes limpam o
 * valor). {@code kind} e {@code parentId} são opcionais só para o cliente poder devolver o objeto
 * inteiro; se vierem diferentes do atual, a edição é recusada. Campos fora desta lista são recusados.
 */
public record UpdateCategoryRequest(
		@NotBlank(message = "é obrigatório")
		@Size(max = 60, message = "deve ter no máximo 60 caracteres")
		String name,

		CategoryKind kind,

		UUID parentId,

		@Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "deve ser uma cor no formato #RRGGBB")
		String color,

		@Size(min = 1, max = 40, message = "deve ter de 1 a 40 caracteres")
		String icon) {

	public UpdateCategoryRequest {
		name = name == null ? null : name.strip();
	}

}
