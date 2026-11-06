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
import com.tostao.tostao_api.category.dto.CreateCategoryRequest;
import com.tostao.tostao_api.category.dto.UpdateCategoryRequest;
import com.tostao.tostao_api.common.error.CategoryConflictException;
import com.tostao.tostao_api.common.error.CategoryNameAlreadyExistsException;
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
	 * Cria uma categoria principal (com {@code kind}) ou uma subcategoria (com {@code parentId}, que herda o
	 * tipo). Tudo é checado antes de gravar; o nome repetido entre irmãs ativas é decidido pela própria
	 * inserção ({@code ON CONFLICT DO NOTHING}), sem exceção dentro da transação. A linha do pai fica
	 * travada em modo compartilhado para que ele não seja arquivado enquanto a filha nasce.
	 */
	@WorkspaceScoped(write = true)
	@Transactional
	public CategoryResponse create(UUID workspaceId, CreateCategoryRequest request) {
		CategoryKind kind;
		if (request.parentId() == null) {
			if (request.kind() == null) {
				throw new FieldValidationException("kind", "obrigatório para categoria principal");
			}
			kind = request.kind();
		}
		else {
			Parent parent = jdbc.sql("""
					select kind, parent_id, archived from categories
					where id = :parentId and workspace_id = :workspaceId
					for share
					""")
					.param("parentId", request.parentId())
					.param("workspaceId", workspaceId)
					.query((rs, i) -> new Parent(CategoryKind.valueOf(rs.getString("kind")),
							rs.getObject("parent_id", UUID.class) != null, rs.getBoolean("archived")))
					.optional()
					.orElseThrow(CategoryNotFoundException::new);
			if (parent.isSubcategory()) {
				throw new FieldValidationException("parentId", "uma subcategoria não pode ter filhas");
			}
			if (parent.archived()) {
				throw new CategoryConflictException("A categoria principal está arquivada");
			}
			if (request.kind() != null && request.kind() != parent.kind()) {
				throw new FieldValidationException("kind", "deve ser igual ao da categoria principal");
			}
			kind = parent.kind();
		}

		UUID id = jdbc.sql("""
				insert into categories (workspace_id, parent_id, name, kind, color, icon)
				values (:workspaceId, :parentId, :name, :kind, :color, :icon)
				on conflict do nothing
				returning id
				""")
				.param("workspaceId", workspaceId)
				.param("parentId", request.parentId())
				.param("name", request.name())
				.param("kind", kind.name())
				.param("color", request.color())
				.param("icon", request.icon())
				.query(UUID.class)
				.optional()
				.orElseThrow(CategoryNameAlreadyExistsException::new);

		return load(workspaceId, id);
	}

	/**
	 * Edita nome, cor e ícone de uma categoria ativa. O tipo e a categoria principal nunca mudam. O nome em
	 * uso por uma irmã ativa é checado antes; a corrida residual de duas gravações simultâneas é barrada
	 * pelos índices únicos e traduzida em 409 na borda web, depois do rollback.
	 */
	@WorkspaceScoped(write = true)
	@Transactional
	public CategoryResponse update(UUID workspaceId, UUID categoryId, UpdateCategoryRequest request) {
		CategoryResponse current = load(workspaceId, categoryId);
		if (current.archived()) {
			throw new CategoryConflictException("A categoria está arquivada; restaure-a antes de editar");
		}
		if (request.kind() != null && request.kind() != current.kind()) {
			throw new FieldValidationException("kind", "não pode ser mudado");
		}
		if (request.parentId() != null && !request.parentId().equals(current.parentId())) {
			throw new FieldValidationException("parentId", "não pode ser mudado");
		}
		requireNameFree(workspaceId, request.name(), current);

		jdbc.sql("""
				update categories set name = :name, color = :color, icon = :icon
				where id = :id and workspace_id = :workspaceId
				""")
				.param("name", request.name())
				.param("color", request.color())
				.param("icon", request.icon())
				.param("id", categoryId)
				.param("workspaceId", workspaceId)
				.update();

		return load(workspaceId, categoryId);
	}

	/**
	 * Arquiva a categoria, preservando todos os seus dados e lançamentos. Arquivar uma principal arquiva
	 * também, na mesma transação, as subcategorias ativas dela. Idempotente: só escreve quando o estado
	 * muda. O {@code UPDATE} da principal vem primeiro porque pede o bloqueio da linha do pai, o que
	 * serializa com a criação e a restauração de subcategorias.
	 */
	@WorkspaceScoped(write = true)
	@Transactional
	public CategoryResponse archive(UUID workspaceId, UUID categoryId) {
		CategoryResponse current = load(workspaceId, categoryId);
		if (current.archived()) {
			return current;
		}
		jdbc.sql("""
				update categories set archived = true
				where id = :id and workspace_id = :workspaceId and not archived
				""")
				.param("id", categoryId)
				.param("workspaceId", workspaceId)
				.update();
		if (current.parentId() == null) {
			jdbc.sql("""
					update categories set archived = true
					where parent_id = :id and workspace_id = :workspaceId and not archived
					""")
					.param("id", categoryId)
					.param("workspaceId", workspaceId)
					.update();
		}
		return load(workspaceId, categoryId);
	}

	/**
	 * Restaura uma categoria arquivada; nunca restaura as subcategorias. Idempotente. Uma subcategoria só
	 * volta com a principal ativa (409), e o nome não pode estar em uso por uma irmã ativa (409); a corrida
	 * residual é barrada pelos índices únicos e traduzida depois do rollback.
	 */
	@WorkspaceScoped(write = true)
	@Transactional
	public CategoryResponse restore(UUID workspaceId, UUID categoryId) {
		CategoryResponse current = load(workspaceId, categoryId);
		if (!current.archived()) {
			return current;
		}
		if (current.parentId() != null) {
			boolean parentArchived = jdbc.sql("""
					select archived from categories
					where id = :parentId and workspace_id = :workspaceId
					for share
					""")
					.param("parentId", current.parentId())
					.param("workspaceId", workspaceId)
					.query(Boolean.class)
					.single();
			if (parentArchived) {
				throw new CategoryConflictException("Restaure antes a categoria principal");
			}
		}
		requireNameFree(workspaceId, current.name(), current);
		jdbc.sql("""
				update categories set archived = false
				where id = :id and workspace_id = :workspaceId and archived
				""")
				.param("id", categoryId)
				.param("workspaceId", workspaceId)
				.update();
		return load(workspaceId, categoryId);
	}

	/**
	 * Checagem prévia: outra categoria ativa irmã já usa este nome (sem diferenciar maiúsculas). São irmãs as
	 * subcategorias do mesmo pai e as principais do mesmo tipo.
	 */
	private void requireNameFree(UUID workspaceId, String name, CategoryResponse category) {
		String siblings = category.parentId() == null
				? "parent_id is null and kind = :kind"
				: "parent_id = :parentId";
		var statement = jdbc.sql("""
				select exists (
				    select 1 from categories
				    where workspace_id = :workspaceId and lower(name) = lower(:name)
				      and not archived and id <> :exceptId and\s""" + siblings + ")")
				.param("workspaceId", workspaceId)
				.param("name", name)
				.param("exceptId", category.id());
		statement = category.parentId() == null
				? statement.param("kind", category.kind().name())
				: statement.param("parentId", category.parentId());
		if (statement.query(Boolean.class).single()) {
			throw new CategoryNameAlreadyExistsException();
		}
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

	private record Parent(CategoryKind kind, boolean isSubcategory, boolean archived) {
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
