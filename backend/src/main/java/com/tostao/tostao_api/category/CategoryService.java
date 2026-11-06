package com.tostao.tostao_api.category;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tostao.tostao_api.category.dto.CategoryResponse;
import com.tostao.tostao_api.category.dto.CategoryTreeResponse;
import com.tostao.tostao_api.common.error.CategoryNotFoundException;
import com.tostao.tostao_api.common.error.FieldValidationException;
import com.tostao.tostao_api.workspace.WorkspaceScoped;

/**
 * Regras de categorias. Duas camadas de isolamento: {@link WorkspaceScoped} (participação e papel) e o
 * RLS cuidam do workspace, e toda consulta daqui também filtra explicitamente por {@code workspace_id}.
 */
@Service
public class CategoryService {

	private static final String SELECT = """
			select id, parent_id, name, kind, color, icon, archived, created_at
			from categories
			where workspace_id = :workspaceId
			""";

	private static final RowMapper<CategoryResponse> ROW_MAPPER = (rs, i) -> new CategoryResponse(
			rs.getObject("id", UUID.class),
			rs.getString("name"),
			CategoryKind.valueOf(rs.getString("kind")),
			rs.getObject("parent_id", UUID.class),
			rs.getString("color"),
			rs.getString("icon"),
			rs.getBoolean("archived"),
			rs.getObject("created_at", OffsetDateTime.class).toInstant());

	private final JdbcClient jdbc;

	public CategoryService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Categorias principais com as suas subcategorias, por nome (sem diferenciar maiúsculas) em cada
	 * nível. Por padrão só as ativas. {@code kind} nulo traz os dois tipos.
	 */
	@WorkspaceScoped
	@Transactional(readOnly = true)
	public List<CategoryTreeResponse> list(UUID workspaceId, String kind, boolean includeArchived) {
		CategoryKind filter = parseKind(kind);
		String sql = SELECT
				+ (filter == null ? "" : "  and kind = :kind\n")
				+ (includeArchived ? "" : "  and not archived\n")
				+ "order by lower(name), id\n";
		var statement = jdbc.sql(sql).param("workspaceId", workspaceId);
		if (filter != null) {
			statement = statement.param("kind", filter.name());
		}
		List<CategoryResponse> rows = statement.query(ROW_MAPPER).list();

		Map<UUID, List<CategoryResponse>> childrenOf = new LinkedHashMap<>();
		for (CategoryResponse row : rows) {
			if (row.parentId() != null) {
				childrenOf.computeIfAbsent(row.parentId(), k -> new ArrayList<>()).add(row);
			}
		}
		List<CategoryTreeResponse> tree = new ArrayList<>();
		for (CategoryResponse row : rows) {
			if (row.parentId() == null) {
				tree.add(CategoryTreeResponse.of(row, childrenOf.getOrDefault(row.id(), List.of())));
			}
		}
		return tree;
	}

	/** Uma categoria. De outro workspace, ou inexistente, é "não encontrada". */
	@WorkspaceScoped
	@Transactional(readOnly = true)
	public CategoryResponse get(UUID workspaceId, UUID categoryId) {
		return load(workspaceId, categoryId);
	}

	/** Carrega uma categoria do workspace; a de outro workspace é "não encontrada". */
	private CategoryResponse load(UUID workspaceId, UUID categoryId) {
		return jdbc.sql(SELECT + "  and id = :categoryId\n")
				.param("workspaceId", workspaceId)
				.param("categoryId", categoryId)
				.query(ROW_MAPPER)
				.optional()
				.orElseThrow(CategoryNotFoundException::new);
	}

	private static CategoryKind parseKind(String kind) {
		if (kind == null) {
			return null;
		}
		try {
			return CategoryKind.valueOf(kind);
		}
		catch (IllegalArgumentException e) {
			throw new FieldValidationException("kind", "deve ser INCOME ou EXPENSE");
		}
	}

}
