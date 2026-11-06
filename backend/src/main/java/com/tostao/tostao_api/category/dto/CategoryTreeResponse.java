package com.tostao.tostao_api.category.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.tostao.tostao_api.category.CategoryKind;

/** Categoria principal com as suas subcategorias, na ordem de exibição. */
public record CategoryTreeResponse(
		UUID id,
		String name,
		CategoryKind kind,
		UUID parentId,
		String color,
		String icon,
		boolean archived,
		Instant createdAt,
		List<CategoryResponse> subcategories) {

	public static CategoryTreeResponse of(CategoryResponse root, List<CategoryResponse> subcategories) {
		return new CategoryTreeResponse(root.id(), root.name(), root.kind(), root.parentId(), root.color(),
				root.icon(), root.archived(), root.createdAt(), subcategories);
	}

}
