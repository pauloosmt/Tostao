package com.tostao.tostao_api.dashboard;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.tostao.tostao_api.dashboard.dto.DashboardResponse.CategoryGroup;
import com.tostao.tostao_api.dashboard.dto.DashboardResponse.CategoryItem;

/**
 * Regras puras do painel (sem Spring, sem banco): percentuais, variação, montagem e ordem dos grupos por
 * categoria e saldo acumulado por dia. Somas em {@code long} usam {@code addExact}: estourar é erro, nunca
 * número errado em silêncio. Percentuais usam {@link RoundingMode#HALF_UP} com duas casas.
 */
public final class DashboardRules {

	/** Nome do grupo de receitas ou despesas sem categoria. */
	public static final String NO_CATEGORY = "Sem categoria";

	private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

	private DashboardRules() {
	}

	/**
	 * Um total do tipo por categoria usada: {@code rootId}/{@code rootName} é a categoria principal e
	 * {@code categoryId}/{@code categoryName} a própria categoria do lançamento (igual à principal quando o
	 * lançamento é direto nela). {@code rootId} nulo significa lançamento sem categoria.
	 */
	public record CategoryRow(UUID rootId, String rootName, UUID categoryId, String categoryName, long totalCents) {
	}

	/** Percentual de {@code part} sobre {@code total}, com duas casas. {@code total} zero não tem percentual. */
	public static BigDecimal percent(long part, long total) {
		if (total == 0) {
			throw new IllegalArgumentException("Sem total não há percentual");
		}
		return BigDecimal.valueOf(part).multiply(HUNDRED).divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
	}

	/**
	 * Variação percentual sobre o valor absoluto do período anterior, para o sinal indicar o sentido da
	 * mudança. Nula quando o período anterior é zero ("não aplicável").
	 */
	public static BigDecimal changePercent(long change, long previous) {
		if (previous == 0) {
			return null;
		}
		return BigDecimal.valueOf(change).multiply(HUNDRED)
				.divide(BigDecimal.valueOf(previous).abs(), 2, RoundingMode.HALF_UP);
	}

	/**
	 * Monta os grupos de um tipo a partir das linhas por categoria. O lançado direto na principal vai em
	 * {@code directCents}; as subcategorias viram filhos; o total do grupo é a soma dos dois. Grupos e
	 * filhos saem do maior para o menor total, depois por nome (sem diferenciar maiúsculas) e por
	 * identificador. {@code typeTotal} é o total do tipo no período, base dos percentuais.
	 */
	public static List<CategoryGroup> groups(List<CategoryRow> rows, long typeTotal) {
		Map<UUID, Accumulator> byRoot = new HashMap<>();
		for (CategoryRow row : rows) {
			Accumulator group = byRoot.computeIfAbsent(row.rootId(),
					root -> new Accumulator(row.rootId(), row.rootId() == null ? NO_CATEGORY : row.rootName()));
			if (row.rootId() == null || row.rootId().equals(row.categoryId())) {
				group.direct = Math.addExact(group.direct, row.totalCents());
			} else {
				group.children.add(new CategoryItem(row.categoryId(), row.categoryName(), row.totalCents(),
						percent(row.totalCents(), typeTotal)));
			}
		}
		List<CategoryGroup> groups = new ArrayList<>();
		for (Accumulator acc : byRoot.values()) {
			long total = acc.direct;
			for (CategoryItem child : acc.children) {
				total = Math.addExact(total, child.totalCents());
			}
			acc.children.sort(ITEM_ORDER);
			groups.add(new CategoryGroup(acc.id, acc.name, acc.direct, total, percent(total, typeTotal),
					List.copyOf(acc.children)));
		}
		groups.sort(GROUP_ORDER);
		return List.copyOf(groups);
	}

	/**
	 * Saldo total ao fim de cada dia: a abertura mais o movimento acumulado até o dia. Dia sem movimento
	 * repete o saldo do dia anterior.
	 */
	public static List<Long> runningBalances(long opening, List<LocalDate> days, Map<LocalDate, Long> movementByDay) {
		List<Long> balances = new ArrayList<>(days.size());
		long balance = opening;
		for (LocalDate day : days) {
			balance = Math.addExact(balance, movementByDay.getOrDefault(day, 0L));
			balances.add(balance);
		}
		return balances;
	}

	public static long sumExact(Collection<Long> values) {
		long sum = 0;
		for (long value : values) {
			sum = Math.addExact(sum, value);
		}
		return sum;
	}

	private static final Comparator<UUID> ID_ORDER = Comparator.nullsLast(Comparator.<UUID>naturalOrder());

	private static final Comparator<CategoryGroup> GROUP_ORDER = Comparator
			.comparingLong(CategoryGroup::totalCents).reversed()
			.thenComparing(g -> g.name().toLowerCase(Locale.ROOT))
			.thenComparing(CategoryGroup::categoryId, ID_ORDER);

	private static final Comparator<CategoryItem> ITEM_ORDER = Comparator
			.comparingLong(CategoryItem::totalCents).reversed()
			.thenComparing(i -> i.name().toLowerCase(Locale.ROOT))
			.thenComparing(CategoryItem::categoryId, ID_ORDER);

	private static final class Accumulator {

		final UUID id;
		final String name;
		long direct;
		final List<CategoryItem> children = new ArrayList<>();

		Accumulator(UUID id, String name) {
			this.id = id;
			this.name = name;
		}

	}

}
