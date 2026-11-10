package com.tostao.tostao_api.recurrence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.tostao.tostao_api.common.error.FieldValidationException;
import com.tostao.tostao_api.transaction.TransactionType;

/** As regras puras da recorrência: tipo, início e fim. */
class RecurrenceRulesTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);

	private static String fieldOf(Throwable e) {
		return ((FieldValidationException) e).field();
	}

	@Test
	void onlyIncomeAndExpenseRecur() {
		assertThatCode(() -> RecurrenceRules.requireType(TransactionType.INCOME)).doesNotThrowAnyException();
		assertThatCode(() -> RecurrenceRules.requireType(TransactionType.EXPENSE)).doesNotThrowAnyException();
		assertThatThrownBy(() -> RecurrenceRules.requireType(TransactionType.TRANSFER))
				.isInstanceOf(FieldValidationException.class).satisfies(e -> assertThat(fieldOf(e)).isEqualTo("type"));
		assertThatThrownBy(() -> RecurrenceRules.requireType(null)).isInstanceOf(FieldValidationException.class)
				.satisfies(e -> assertThat(fieldOf(e)).isEqualTo("type"));
	}

	@Test
	void theStartStaysBetween366DaysAgoAnd2100() {
		assertThatCode(() -> RecurrenceRules.requireStart(TODAY, TODAY)).doesNotThrowAnyException();
		assertThatCode(() -> RecurrenceRules.requireStart(TODAY.minusDays(366), TODAY)).doesNotThrowAnyException();
		assertThatCode(() -> RecurrenceRules.requireStart(LocalDate.of(2100, 12, 31), TODAY)).doesNotThrowAnyException();
		assertThatThrownBy(() -> RecurrenceRules.requireStart(TODAY.minusDays(367), TODAY))
				.isInstanceOf(FieldValidationException.class).satisfies(e -> assertThat(fieldOf(e)).isEqualTo("startOn"));
		assertThatThrownBy(() -> RecurrenceRules.requireStart(LocalDate.of(2101, 1, 1), TODAY))
				.isInstanceOf(FieldValidationException.class).satisfies(e -> assertThat(fieldOf(e)).isEqualTo("startOn"));
	}

	@Test
	void theLimitFollowsTheGivenTodayAndNotAFixedDate() {
		LocalDate later = LocalDate.of(2030, 3, 1);

		// a mesma data de início que valia em 2026 deixa de valer quando "hoje" anda
		assertThatCode(() -> RecurrenceRules.requireStart(LocalDate.of(2026, 11, 10), TODAY)).doesNotThrowAnyException();
		assertThatThrownBy(() -> RecurrenceRules.requireStart(LocalDate.of(2026, 11, 10), later))
				.isInstanceOf(FieldValidationException.class);
		assertThatCode(() -> RecurrenceRules.requireStart(later.minusDays(366), later)).doesNotThrowAnyException();
	}

	@Test
	void theEndIsOptionalAndStaysBetweenTheStartAnd2100() {
		LocalDate start = LocalDate.of(2026, 11, 10);

		assertThatCode(() -> RecurrenceRules.requireEnd(start, null)).doesNotThrowAnyException();
		assertThatCode(() -> RecurrenceRules.requireEnd(start, start)).doesNotThrowAnyException();
		assertThatCode(() -> RecurrenceRules.requireEnd(start, LocalDate.of(2100, 12, 31))).doesNotThrowAnyException();
		assertThatThrownBy(() -> RecurrenceRules.requireEnd(start, start.minusDays(1)))
				.isInstanceOf(FieldValidationException.class).satisfies(e -> assertThat(fieldOf(e)).isEqualTo("endOn"));
		assertThatThrownBy(() -> RecurrenceRules.requireEnd(start, LocalDate.of(2101, 1, 1)))
				.isInstanceOf(FieldValidationException.class).satisfies(e -> assertThat(fieldOf(e)).isEqualTo("endOn"));
	}

	@Test
	void theFrequenciesAreTheThreeOfTheContract() {
		assertThat(RecurrenceFrequency.values()).extracting(Enum::name).containsExactly("WEEKLY", "MONTHLY", "YEARLY");
	}

}
