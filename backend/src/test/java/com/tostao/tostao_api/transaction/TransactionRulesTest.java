package com.tostao.tostao_api.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.tostao.tostao_api.category.CategoryKind;
import com.tostao.tostao_api.common.error.FieldValidationException;

/** Teste unitário puro (sem Spring nem banco) das regras de saldo e de transferência. */
class TransactionRulesTest {

	private static final long LIMIT = 1_000_000_000_000L;

	@Test
	void incomeIsPositiveAndExpenseIsNegative() {
		assertThat(TransactionRules.signedAmount(TransactionType.INCOME, 10_000)).isEqualTo(10_000);
		assertThat(TransactionRules.signedAmount(TransactionType.EXPENSE, 10_000)).isEqualTo(-10_000);
		assertThat(TransactionRules.signedAmount(TransactionType.EXPENSE, LIMIT)).isEqualTo(-LIMIT);
	}

	@Test
	void aTransferHasNoSignOfItsOwn() {
		assertThatThrownBy(() -> TransactionRules.signedAmount(TransactionType.TRANSFER, 100))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void aTransferHasAnOutflowAndAnInflowThatCancelOut() {
		long[] legs = TransactionRules.transferLegs(30_000);

		assertThat(legs).containsExactly(-30_000, 30_000);
		assertThat(legs[0] + legs[1]).isZero();
	}

	@Test
	void centsAddUpExactly() {
		// 0,10 + 0,20 = 0,30 sem erro de arredondamento
		long sum = TransactionRules.signedAmount(TransactionType.INCOME, 10)
				+ TransactionRules.signedAmount(TransactionType.INCOME, 20);

		assertThat(sum).isEqualTo(30);
	}

	@Test
	void manySignedAmountsAndTransfersSumWithoutOverflowUpToTheLimit() {
		long total = 0;
		for (int i = 0; i < 1_000; i++) {
			total += TransactionRules.signedAmount(TransactionType.INCOME, LIMIT);
			total += TransactionRules.signedAmount(TransactionType.EXPENSE, LIMIT);
			long[] legs = TransactionRules.transferLegs(LIMIT);
			total += legs[0] + legs[1];
		}
		long onlyIncome = 0;
		for (int i = 0; i < 1_000; i++) {
			onlyIncome += TransactionRules.signedAmount(TransactionType.INCOME, LIMIT);
		}

		assertThat(total).isZero();
		assertThat(onlyIncome).isEqualTo(1_000L * LIMIT);
	}

	@Test
	void likePatternEscapesWildcardsAndBackslash() {
		assertThat(TransactionRules.likePattern("mercado")).isEqualTo("%mercado%");
		assertThat(TransactionRules.likePattern("50%")).isEqualTo("%50\\%%");
		assertThat(TransactionRules.likePattern("a_b")).isEqualTo("%a\\_b%");
		assertThat(TransactionRules.likePattern("c:\\x")).isEqualTo("%c:\\\\x%");
		assertThat(TransactionRules.likePattern("50%_a\\b")).isEqualTo("%50\\%\\_a\\\\b%");
		assertThat(TransactionRules.likePattern("")).isEqualTo("%%");
	}

	@Test
	void datesAreAcceptedOnlyFrom2000To2100() {
		TransactionRules.requireDateInRange(LocalDate.of(2000, 1, 1));
		TransactionRules.requireDateInRange(LocalDate.of(2100, 12, 31));
		TransactionRules.requireDateInRange(LocalDate.of(2999 - 900, 6, 15));

		for (LocalDate bad : new LocalDate[] { LocalDate.of(1999, 12, 31), LocalDate.of(2101, 1, 1) }) {
			assertThatThrownBy(() -> TransactionRules.requireDateInRange(bad))
					.isInstanceOfSatisfying(FieldValidationException.class,
							e -> assertThat(e.field()).isEqualTo("occurredOn"));
		}
	}

	@Test
	void theCategoryKindMustMatchTheTransactionType() {
		TransactionRules.requireCategoryKind(TransactionType.INCOME, CategoryKind.INCOME);
		TransactionRules.requireCategoryKind(TransactionType.EXPENSE, CategoryKind.EXPENSE);

		assertThatThrownBy(() -> TransactionRules.requireCategoryKind(TransactionType.INCOME, CategoryKind.EXPENSE))
				.isInstanceOfSatisfying(FieldValidationException.class, e -> {
					assertThat(e.field()).isEqualTo("categoryId");
					assertThat(e.getMessage()).contains("receita");
				});
		assertThatThrownBy(() -> TransactionRules.requireCategoryKind(TransactionType.EXPENSE, CategoryKind.INCOME))
				.isInstanceOfSatisfying(FieldValidationException.class, e -> {
					assertThat(e.field()).isEqualTo("categoryId");
					assertThat(e.getMessage()).contains("despesa");
				});
	}

	@Test
	void totalPagesIsTheCeilingOfItemsOverSize() {
		assertThat(TransactionRules.totalPages(0, 20)).isZero();
		assertThat(TransactionRules.totalPages(1, 20)).isEqualTo(1);
		assertThat(TransactionRules.totalPages(20, 20)).isEqualTo(1);
		assertThat(TransactionRules.totalPages(21, 20)).isEqualTo(2);
		assertThat(TransactionRules.totalPages(20_000, 100)).isEqualTo(200);
	}

}
