package com.tostao.tostao_api.category;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.tostao.tostao_api.category.dto.CategoryResponse;
import com.tostao.tostao_api.category.dto.CategoryTreeResponse;
import com.tostao.tostao_api.category.dto.CreateCategoryRequest;
import com.tostao.tostao_api.category.dto.UpdateCategoryRequest;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/workspaces/{wid}/categories")
class CategoryController {

	private final CategoryService categories;

	CategoryController(CategoryService categories) {
		this.categories = categories;
	}

	/** O tipo chega como texto e é validado no serviço: valor desconhecido vira 400 no campo kind. */
	@GetMapping
	List<CategoryTreeResponse> list(@PathVariable("wid") UUID workspaceId,
			@RequestParam(name = "kind", required = false) String kind,
			@RequestParam(name = "includeArchived", defaultValue = "false") boolean includeArchived) {
		return categories.list(workspaceId, kind, includeArchived);
	}

	@GetMapping("/{id}")
	CategoryResponse get(@PathVariable("wid") UUID workspaceId, @PathVariable("id") UUID categoryId) {
		return categories.get(workspaceId, categoryId);
	}

	@PutMapping("/{id}")
	CategoryResponse update(@PathVariable("wid") UUID workspaceId, @PathVariable("id") UUID categoryId,
			@Valid @RequestBody UpdateCategoryRequest request) {
		return categories.update(workspaceId, categoryId, request);
	}

	@PostMapping
	ResponseEntity<CategoryResponse> create(@PathVariable("wid") UUID workspaceId,
			@Valid @RequestBody CreateCategoryRequest request) {
		CategoryResponse created = categories.create(workspaceId, request);
		URI location = URI.create("/api/v1/workspaces/" + workspaceId + "/categories/" + created.id());
		return ResponseEntity.created(location).body(created);
	}

}
