package com.tostao.tostao_api.recurrence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.tostao.tostao_api.common.error.FieldValidationException;
import com.tostao.tostao_api.transaction.TransactionType;

/** A forma da recorrência por tipo (FR-002): cada recusa aponta o campo. */
class TransferRecurrenceRulesTest {

	private static final UUID ORIGIN = UUID.randomUUID();
	private static final UUID DESTINATION = UUID.randomUUID();
	private static final UUID CATEGORY = UUID.randomUUID();

	private static void refused(TransactionType type, UUID destination, UUID category, String field) {
		assertThatThrownBy(() -> RecurrenceRules.requireShape(type, ORIGIN, destination, category))
				.isInstanceOf(FieldValidationException.class)
				.satisfies(e -> assertThat(((FieldValidationException) e).field()).isEqualTo(field));
	}

	@Test
	void aTransferNeedsADifferentDestinationAndNoCategory() {
		assertThatCode(() -> RecurrenceRules.requireShape(TransactionType.TRANSFER, ORIGIN, DESTINATION, null)).doesNotThrowAnyException();

		refused(TransactionType.TRANSFER, null, null, "destinationAccountId");
		refused(TransactionType.TRANSFER, ORIGIN, null, "destinationAccountId");
		refused(TransactionType.TRANSFER, DESTINATION, CATEGORY, "categoryId");
	}

	@Test
	void anIncomeOrExpenseNeedsACategoryAndNoDestination() {
		for (TransactionType type : new TransactionType[] { TransactionType.INCOME, TransactionType.EXPENSE }) {
			assertThatCode(() -> RecurrenceRules.requireShape(type, ORIGIN, null, CATEGORY)).doesNotThrowAnyException();

			refused(type, null, null, "categoryId");
			refused(type, DESTINATION, CATEGORY, "destinationAccountId");
			refused(type, DESTINATION, null, "destinationAccountId");
		}
	}

	@Test
	void theDestinationIsCheckedBeforeTheCategoryOfATransfer() {
		// destino igual à origem e categoria juntos: o primeiro defeito apontado é o do destino
		refused(TransactionType.TRANSFER, ORIGIN, CATEGORY, "destinationAccountId");
	}

	@Test
	void aMissingTypeIsRefusedAndEveryRealTypeIsAccepted() {
		assertThatThrownBy(() -> RecurrenceRules.requireType(null)).isInstanceOf(FieldValidationException.class)
				.satisfies(e -> assertThat(((FieldValidationException) e).field()).isEqualTo("type"));
		for (TransactionType type : TransactionType.values()) {
			assertThatCode(() -> RecurrenceRules.requireType(type)).doesNotThrowAnyException();
		}
	}

}
