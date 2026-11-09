package com.tostao.tostao_api.budget;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.tostao.tostao_api.budget.BudgetTracking.Limit;
import com.tostao.tostao_api.budget.BudgetTracking.Spent;
import com.tostao.tostao_api.budget.dto.CopyBudgetsResponse;
import com.tostao.tostao_api.budget.dto.MonthBudgetResponse;
import com.tostao.tostao_api.budget.dto.MonthBudgetResponse.MonthInfo;
import com.tostao.tostao_api.budget.dto.MonthlyBudgetResponse;
import com.tostao.tostao_api.common.error.BudgetConflictException;
import com.tostao.tostao_api.common.error.BudgetNotFoundException;
import com.tostao.tostao_api.common.error.CategoryNotFoundException;
import com.tostao.tostao_api.common.error.FieldValidationException;
import com.tostao.tostao_api.dashboard.Period;
import com.tostao.tostao_api.workspace.CurrentUser;
import com.tostao.tostao_api.workspace.WorkspaceScoped;

/**
 * Orçamento mensal, irmão do {@link BudgetService} (semanal): mesma regra de gasto, percentual e estado
 * ({@link BudgetTracking}), tabela própria. Duas camadas de isolamento: {@link WorkspaceScoped} (participação
 * e papel) e o RLS cuidam do workspace, e toda consulta daqui também filtra explicitamente por
 * {@code workspace_id}. A categoria de um limite é conferida e travada ({@code FOR SHARE}) antes de gravar,
 * para nunca nascer nem mudar limite em categoria arquivada, nem sob corrida com o arquivamento. Nenhuma
 * exceção é capturada dentro de transação: corridas se resolvem com {@code ON CONFLICT}.
 */
@Service
public class MonthlyBudgetService {

	/** Limite do workspace com o nome da categoria (nulo no limite geral). */
	private static final String SELECT = """
			select b.id, b.month_start, b.category_id, c.name as category_name, b.amount_cents, b.created_by,
			       b.created_at, b.updated_at
			from monthly_budgets b
			left join categories c on c.id = b.category_id and c.workspace_id = b.workspace_id
			where b.workspace_id = :workspaceId
			""";

	private static final RowMapper<MonthlyBudgetResponse> ROW_MAPPER = (rs, i) -> new MonthlyBudgetResponse(
			rs.getObject("id", UUID.class),
			YearMonth.from(rs.getObject("month_start", LocalDate.class)).toString(),
			rs.getObject("category_id", UUID.class),
			rs.getString("category_name"),
			rs.getLong("amount_cents"),
			rs.getObject("created_by", UUID.class),
			rs.getObject("created_at", OffsetDateTime.class).toInstant(),
			rs.getObject("updated_at", OffsetDateTime.class).toInstant());

	/** Limites do mês com a categoria (nome e se está arquivada). */
	static final String MONTH_LIMITS_SQL = """
			select b.id, b.category_id, c.name as category_name, coalesce(c.archived, false) as category_archived,
			       b.amount_cents, b.created_by, b.created_at, b.updated_at
			from monthly_budgets b
			left join categories c on c.id = b.category_id and c.workspace_id = b.workspace_id
			where b.workspace_id = :workspaceId and b.month_start = :start
			""";

	private final JdbcClient jdbc;

	public MonthlyBudgetService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Resultado de {@link #define}: o limite e se ele nasceu agora ou já existia. */
	public record DefineResult(MonthlyBudgetResponse budget, boolean created) {
	}

	/**
	 * Define o limite de um escopo e mês: cria, ou substitui o valor se já existe (mantendo quem criou e a
	 * data de criação). Uma instrução só ({@code INSERT ... ON CONFLICT DO UPDATE}), então duas definições
	 * simultâneas do mesmo escopo deixam um único limite, com o valor da última gravação.
	 */
	@WorkspaceScoped(write = true)
	@Transactional
	public DefineResult define(UUID workspaceId, Period month, UUID categoryId, long amountCents) {
		if (categoryId != null) {
			requireUsableCategory(workspaceId, categoryId);
		}
		record Upsert(UUID id, boolean created) {
		}
		Upsert upsert = jdbc.sql("""
				insert into monthly_budgets (workspace_id, month_start, category_id, amount_cents, created_by)
				values (:workspaceId, :start, :categoryId, :amount, :createdBy)
				on conflict (workspace_id, month_start, category_id)
				do update set amount_cents = excluded.amount_cents, updated_at = now()
				returning id, (xmax = 0) as created
				""")
				.param("workspaceId", workspaceId)
				.param("start", month.start())
				.param("categoryId", categoryId)
				.param("amount", amountCents)
				.param("createdBy", CurrentUser.id())
				.query((rs, i) -> new Upsert(rs.getObject("id", UUID.class), rs.getBoolean("created")))
				.single();
		return new DefineResult(load(workspaceId, upsert.id()), upsert.created());
	}

	/** Altera o valor de um limite existente. Em categoria arquivada é recusado (409). */
	@WorkspaceScoped(write = true)
	@Transactional
	public MonthlyBudgetResponse update(UUID workspaceId, UUID budgetId, long amountCents) {
		record Existing(UUID categoryId) {
		}
		UUID categoryId = jdbc.sql("""
				select category_id from monthly_budgets
				where id = :id and workspace_id = :workspaceId
				for update
				""")
				.param("id", budgetId)
				.param("workspaceId", workspaceId)
				.query((rs, i) -> new Existing(rs.getObject("category_id", UUID.class)))
				.optional()
				.orElseThrow(BudgetNotFoundException::new)
				.categoryId();
		if (categoryId != null) {
			requireUsableCategory(workspaceId, categoryId);
		}
		jdbc.sql("""
				update monthly_budgets set amount_cents = :amount, updated_at = now()
				where id = :id and workspace_id = :workspaceId
				""")
				.param("amount", amountCents)
				.param("id", budgetId)
				.param("workspaceId", workspaceId)
				.update();
		return load(workspaceId, budgetId);
	}

	/** Remove o limite de verdade (é um plano, não um lançamento). Funciona também em categoria arquivada. */
	@WorkspaceScoped(write = true)
	@Transactional
	public void delete(UUID workspaceId, UUID budgetId) {
		int removed = jdbc.sql("delete from monthly_budgets where id = :id and workspace_id = :workspaceId")
				.param("id", budgetId)
				.param("workspaceId", workspaceId)
				.update();
		if (removed == 0) {
			throw new BudgetNotFoundException();
		}
	}

	/**
	 * Acompanhamento de um mês. Os limites e as despesas saem do mesmo instante ({@code REPEATABLE READ}),
	 * então o total, o gasto de cada limite e o gasto fora dos limites nunca se contradizem sob gravações
	 * simultâneas. Os valores dos limites nunca são somados entre si.
	 */
	@WorkspaceScoped
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public MonthBudgetResponse month(UUID workspaceId, Period month) {
		List<Limit> limits = jdbc.sql(MONTH_LIMITS_SQL)
				.param("workspaceId", workspaceId)
				.param("start", month.start())
				.query((rs, i) -> new Limit(rs.getObject("id", UUID.class), rs.getObject("category_id", UUID.class),
						rs.getString("category_name"), rs.getBoolean("category_archived"), rs.getLong("amount_cents"),
						rs.getObject("created_by", UUID.class),
						rs.getObject("created_at", OffsetDateTime.class).toInstant(),
						rs.getObject("updated_at", OffsetDateTime.class).toInstant()))
				.list();
		List<Spent> spent = jdbc.sql(BudgetService.PERIOD_SPENT_SQL)
				.param("workspaceId", workspaceId)
				.param("start", month.start())
				.param("end", month.end())
				.query((rs, i) -> new Spent(rs.getObject("category_id", UUID.class), rs.getObject("parent_id", UUID.class),
						rs.getLong("spent_cents")))
				.list();

		BudgetTracking.Result result = BudgetTracking.track(limits, spent);
		return new MonthBudgetResponse(new MonthInfo(month.id(), month.start(), month.end()),
				result.totalSpentCents(), result.spentOutsideCategoryLimitsCents(), result.items());
	}

	/**
	 * Copia os limites do mês de origem para os próximos {@code months} meses, só onde ainda não há limite do
	 * mesmo escopo. Um único {@code INSERT ... SELECT ... ON CONFLICT DO NOTHING}: atômico, nunca altera um
	 * limite existente, e as contagens saem do que foi de fato gravado. Limites de categorias hoje arquivadas
	 * não são copiados. Como a data guardada é sempre o dia 1, somar meses nunca "escorrega" para o fim do mês.
	 */
	@WorkspaceScoped(write = true)
	@Transactional
	public CopyBudgetsResponse copy(UUID workspaceId, Period from, int months) {
		if (from.start().plusMonths(months).isAfter(Period.LAST_MONTH_START)) {
			throw new FieldValidationException("months", "os meses de destino passam de 2100");
		}
		record Source(UUID id, UUID categoryId) {
		}
		// trava os limites de origem: ninguém os remove nem os altera até o fim da cópia
		List<Source> sources = jdbc.sql("""
				select id, category_id from monthly_budgets
				where workspace_id = :workspaceId and month_start = :start
				order by id
				for share
				""")
				.param("workspaceId", workspaceId)
				.param("start", from.start())
				.query((rs, i) -> new Source(rs.getObject("id", UUID.class), rs.getObject("category_id", UUID.class)))
				.list();

		Set<UUID> archived = new HashSet<>();
		List<UUID> categoryIds = sources.stream().map(Source::categoryId).filter(Objects::nonNull).toList();
		if (!categoryIds.isEmpty()) {
			// travadas em modo compartilhado e em ordem de id: um arquivamento simultâneo espera esta cópia
			jdbc.sql("""
					select id, archived from categories
					where workspace_id = :workspaceId and id in (:ids)
					order by id
					for share
					""")
					.param("workspaceId", workspaceId)
					.param("ids", categoryIds)
					.query((rs, i) -> {
						if (rs.getBoolean("archived")) {
							archived.add(rs.getObject("id", UUID.class));
						}
						return rs.getObject("id", UUID.class);
					})
					.list();
		}
		List<UUID> copyable = sources.stream().filter(s -> s.categoryId() == null || !archived.contains(s.categoryId()))
				.map(Source::id).toList();
		int skippedArchived = (sources.size() - copyable.size()) * months;
		if (copyable.isEmpty()) {
			return new CopyBudgetsResponse(0, 0, skippedArchived);
		}

		int created = jdbc.sql("""
				insert into monthly_budgets (workspace_id, month_start, category_id, amount_cents, created_by)
				select b.workspace_id, (b.month_start + make_interval(months => i.n))::date, b.category_id,
				       b.amount_cents, :createdBy
				from monthly_budgets b
				cross join generate_series(1, :months) as i(n)
				where b.workspace_id = :workspaceId and b.id in (:ids)
				order by i.n, b.id
				on conflict do nothing
				returning id
				""")
				.param("createdBy", CurrentUser.id())
				.param("months", months)
				.param("workspaceId", workspaceId)
				.param("ids", copyable)
				.query(UUID.class)
				.list()
				.size();
		return new CopyBudgetsResponse(created, copyable.size() * months - created, skippedArchived);
	}

	private MonthlyBudgetResponse load(UUID workspaceId, UUID budgetId) {
		return jdbc.sql(SELECT + "  and b.id = :budgetId\n")
				.param("workspaceId", workspaceId)
				.param("budgetId", budgetId)
				.query(ROW_MAPPER)
				.optional()
				.orElseThrow(BudgetNotFoundException::new);
	}

	/**
	 * A categoria existe no workspace, é de despesa e está ativa. A linha fica travada em modo compartilhado:
	 * arquivar a categoria (um {@code UPDATE}) espera esta transação confirmar, e o arquivamento que chegar
	 * antes é visto aqui.
	 */
	private void requireUsableCategory(UUID workspaceId, UUID categoryId) {
		record Category(String kind, boolean archived) {
		}
		Category category = jdbc.sql("""
				select kind, archived from categories
				where id = :categoryId and workspace_id = :workspaceId
				for share
				""")
				.param("categoryId", categoryId)
				.param("workspaceId", workspaceId)
				.query((rs, i) -> new Category(rs.getString("kind"), rs.getBoolean("archived")))
				.optional()
				.orElseThrow(CategoryNotFoundException::new);
		if (!"EXPENSE".equals(category.kind())) {
			throw new FieldValidationException("categoryId", "só categorias de despesa têm limite");
		}
		if (category.archived()) {
			throw new BudgetConflictException("A categoria está arquivada");
		}
	}

}
