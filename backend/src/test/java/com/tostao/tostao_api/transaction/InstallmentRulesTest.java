package com.tostao.tostao_api.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.tostao.tostao_api.common.error.FieldValidationException;

/** As regras puras da compra parcelada: divisão em centavos, calendário, faixa de datas e descrição. */
class InstallmentRulesTest {

	private static final long MAX_TOTAL = 1_000_000_000_000L;

	// ---- divisão ----------------------------------------------------------------------------------------------

	@Test
	void anExactDivisionGivesEqualInstallments() {
		assertThat(InstallmentRules.split(300_000, 3)).containsExactly(100_000, 100_000, 100_000);
	}

	@Test
	void theRemainderGoesOneCentAtATimeToTheFirstInstallments() {
		assertThat(InstallmentRules.split(10_000, 3)).containsExactly(3_334, 3_333, 3_333);
		assertThat(InstallmentRules.split(5, 3)).containsExactly(2, 2, 1);
		assertThat(InstallmentRules.split(5, 2)).containsExactly(3, 2);
		assertThat(InstallmentRules.split(5, 5)).containsExactly(1, 1, 1, 1, 1);
		assertThat(InstallmentRules.split(7, 4)).containsExactly(2, 2, 2, 1);
		// 100 = 48 x 2 + 4: as quatro primeiras levam o centavo
		assertThat(InstallmentRules.split(100, 48)).hasSize(48).startsWith(3, 3, 3, 3, 2).endsWith(2, 2);
	}

	@Test
	void theSumIsAlwaysTheTotalAndNoInstallmentDiffersByMoreThanOneCent() {
		Random random = new Random(20261110L);
		for (int i = 0; i < 5_000; i++) {
			int count = 2 + random.nextInt(47);
			long total = count + (long) (random.nextDouble() * (MAX_TOTAL - count));
			// totais pequenos, redondos e no teto também entram
			total = switch (i % 4) {
				case 0 -> count + random.nextInt(200);
				case 1 -> MAX_TOTAL - random.nextInt(100);
				case 2 -> (long) count * (1 + random.nextInt(1000));
				default -> total;
			};

			long[] parts = InstallmentRules.split(total, count);

			assertThat(parts).as("total %d em %d", total, count).hasSize(count);
			assertThat(Arrays.stream(parts).sum()).as("soma de %d em %d", total, count).isEqualTo(total);
			long min = Arrays.stream(parts).min().orElseThrow();
			long max = Arrays.stream(parts).max().orElseThrow();
			assertThat(max - min).as("diferença de %d em %d", total, count).isLessThanOrEqualTo(1);
			assertThat(min).as("mínimo de %d em %d", total, count).isGreaterThanOrEqualTo(1);
			// o centavo extra fica nas primeiras: a sequência nunca sobe
			for (int k = 1; k < count; k++) {
				assertThat(parts[k]).isLessThanOrEqualTo(parts[k - 1]);
			}
		}
	}

	@Test
	void aTotalSmallerThanTheNumberOfInstallmentsIsRefused() {
		assertThatThrownBy(() -> InstallmentRules.requireTotal(2, 3)).isInstanceOf(FieldValidationException.class)
				.satisfies(e -> assertThat(((FieldValidationException) e).field()).isEqualTo("totalCents"));
		assertThatThrownBy(() -> InstallmentRules.requireTotal(47, 48)).isInstanceOf(FieldValidationException.class);
		assertThatCode(() -> InstallmentRules.requireTotal(3, 3)).doesNotThrowAnyException();
		assertThatCode(() -> InstallmentRules.requireTotal(48, 48)).doesNotThrowAnyException();
	}

	// ---- calendário -------------------------------------------------------------------------------------------

	@Test
	void eachInstallmentFallsOneMonthAfterThePreviousOne() {
		assertThat(InstallmentRules.dates(LocalDate.of(2026, 11, 10), 3))
				.containsExactly(LocalDate.of(2026, 11, 10), LocalDate.of(2026, 12, 10), LocalDate.of(2027, 1, 10));
	}

	@Test
	void theEndOfMonthAdjustmentNeverAccumulates() {
		assertThat(InstallmentRules.dates(LocalDate.of(2027, 1, 31), 4)).containsExactly(LocalDate.of(2027, 1, 31),
				LocalDate.of(2027, 2, 28), LocalDate.of(2027, 3, 31), LocalDate.of(2027, 4, 30));
		assertThat(InstallmentRules.dates(LocalDate.of(2026, 1, 30), 3)).containsExactly(LocalDate.of(2026, 1, 30),
				LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 30));
		assertThat(InstallmentRules.dates(LocalDate.of(2027, 1, 29), 3)).containsExactly(LocalDate.of(2027, 1, 29),
				LocalDate.of(2027, 2, 28), LocalDate.of(2027, 3, 29));
	}

	@Test
	void aLeapYearKeepsTheTwentyNinth() {
		assertThat(InstallmentRules.dates(LocalDate.of(2028, 1, 31), 3)).containsExactly(LocalDate.of(2028, 1, 31),
				LocalDate.of(2028, 2, 29), LocalDate.of(2028, 3, 31));
		assertThat(InstallmentRules.dates(LocalDate.of(2028, 2, 29), 13).get(12)).isEqualTo(LocalDate.of(2029, 2, 28));
	}

	@Test
	void everyDayOfTheMonthOverFortyEightInstallmentsFollowsTheFirstDate() {
		for (int day : new int[] { 1, 15, 28, 29, 30, 31 }) {
			LocalDate first = LocalDate.of(2027, 1, day);
			List<LocalDate> dates = InstallmentRules.dates(first, 48);

			assertThat(dates).hasSize(48);
			for (int i = 0; i < 48; i++) {
				LocalDate month = first.withDayOfMonth(1).plusMonths(i);
				assertThat(dates.get(i)).as("dia %d, parcela %d", day, i + 1)
						.isEqualTo(month.withDayOfMonth(Math.min(day, month.lengthOfMonth())));
			}
		}
	}

	@Test
	void theYearTurnsOverCleanly() {
		assertThat(InstallmentRules.dates(LocalDate.of(2026, 12, 31), 3)).containsExactly(LocalDate.of(2026, 12, 31),
				LocalDate.of(2027, 1, 31), LocalDate.of(2027, 2, 28));
	}

	// ---- faixa de datas ---------------------------------------------------------------------------------------

	@Test
	void theFirstAndTheLastInstallmentStayBetween2000And2100() {
		assertThatCode(() -> InstallmentRules.requireDatesInRange(LocalDate.of(2000, 1, 1), 48)).doesNotThrowAnyException();
		assertThatCode(() -> InstallmentRules.requireDatesInRange(LocalDate.of(2100, 1, 31), 12)).doesNotThrowAnyException();
		assertThatCode(() -> InstallmentRules.requireDatesInRange(LocalDate.of(2100, 12, 31), 1)).doesNotThrowAnyException();
	}

	@Test
	void aDateOutsideTheRangeIsRefusedOnTheFirstDateField() {
		for (var invalid : List.of(
				new Object[] { LocalDate.of(1999, 12, 31), 2 },
				new Object[] { LocalDate.of(2100, 12, 31), 2 },
				new Object[] { LocalDate.of(2100, 2, 1), 12 },
				new Object[] { LocalDate.of(2101, 1, 1), 2 },
				new Object[] { LocalDate.of(2097, 12, 1), 48 })) {
			LocalDate first = (LocalDate) invalid[0];
			int count = (int) invalid[1];

			assertThatThrownBy(() -> InstallmentRules.requireDatesInRange(first, count))
					.as("%s em %d", first, count).isInstanceOf(FieldValidationException.class)
					.satisfies(e -> assertThat(((FieldValidationException) e).field()).isEqualTo("firstOccurredOn"));
		}
	}

	// ---- descrição --------------------------------------------------------------------------------------------

	@Test
	void eachInstallmentGetsItsNumberAndTheTotalInTheDescription() {
		assertThat(InstallmentRules.describe("Notebook", 1, 3)).isEqualTo("Notebook (1/3)");
		assertThat(InstallmentRules.describe("Notebook", 3, 3)).isEqualTo("Notebook (3/3)");
		assertThat(InstallmentRules.describe("Notebook", 12, 48)).isEqualTo("Notebook (12/48)");
	}

	@Test
	void aBaseOf190CharactersStillFitsTheTransactionDescription() {
		String base = "x".repeat(InstallmentRules.MAX_DESCRIPTION);

		assertThat(InstallmentRules.describe(base, 48, 48)).hasSize(198);
		assertThat(InstallmentRules.describe(base, 48, 48).length()).isLessThanOrEqualTo(200);
	}

	@Test
	void theBaseDescriptionComesBackFromTheInstallmentDescription() {
		assertThat(InstallmentRules.baseDescription("Notebook (2/3)", 2, 3)).isEqualTo("Notebook");
		assertThat(InstallmentRules.baseDescription(InstallmentRules.describe("Fone (novo)", 7, 12), 7, 12))
				.isEqualTo("Fone (novo)");
		String long190 = "y".repeat(190);
		assertThat(InstallmentRules.baseDescription(InstallmentRules.describe(long190, 48, 48), 48, 48))
				.isEqualTo(long190);
	}

	@Test
	void anEditedDescriptionWithoutTheExpectedSuffixIsReturnedAsItIs() {
		assertThat(InstallmentRules.baseDescription("Notebook novo", 2, 3)).isEqualTo("Notebook novo");
		assertThat(InstallmentRules.baseDescription("Notebook (3/3)", 2, 3)).isEqualTo("Notebook (3/3)");
		assertThat(InstallmentRules.baseDescription("Notebook (2/4)", 2, 3)).isEqualTo("Notebook (2/4)");
	}

}
