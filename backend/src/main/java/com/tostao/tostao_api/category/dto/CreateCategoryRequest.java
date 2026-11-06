package com.tostao.tostao_api.category.dto;

import java.util.UUID;

import com.tostao.tostao_api.category.CategoryKind;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Cadastro de categoria. Sem {@code parentId} é uma categoria principal e o {@code kind} é obrigatório
 * (conferido no serviço); com {@code parentId} é uma subcategoria e herda o tipo da principal. Campos
 * fora desta lista, como o workspace, o estado de arquivada ou a data de criação, são recusados.
 */
public record CreateCategoryRequest(
		@NotBlank(message = "é obrigatório")
		@Size(max = 60, message = "deve ter no máximo 60 caracteres")
		String name,

		CategoryKind kind,

		UUID parentId,

		@Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "deve ser uma cor no formato #RRGGBB")
		String color,

		@Size(min = 1, max = 40, message = "deve ter de 1 a 40 caracteres")
		String icon) {

	public CreateCategoryRequest {
		name = name == null ? null : name.strip();
	}

}
