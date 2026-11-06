package com.tostao.tostao_api.category.dto;

import java.time.Instant;
import java.util.UUID;

import com.tostao.tostao_api.category.CategoryKind;

/** Categoria como a API a entrega. Não expõe o {@code workspace_id}. */
public record CategoryResponse(
		UUID id,
		String name,
		CategoryKind kind,
		UUID parentId,
		String color,
		String icon,
		boolean archived,
		Instant createdAt) {
}
