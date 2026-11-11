package com.tostao.tostao_api.recurrence;

import java.time.LocalDate;
import java.util.UUID;

import com.tostao.tostao_api.common.error.FieldValidationException;
import com.tostao.tostao_api.transaction.TransactionType;

/**
 * Regras da recorrência que não dependem de banco nem de Spring: tipo, início e fim. Ficam aqui para serem provadas
 * por teste unitário puro. O calendário das ocorrências <b>não</b> está aqui: vive só na função de banco
 * {@code generate_recurrence_occurrences} (uma única definição), e os testes o conferem contra um cálculo
 * independente.
 */
public final class RecurrenceRules {

	/** O início pode ficar até este número de dias no passado: limita quantas ocorrências nascem de uma vez. */
	public static final int MAX_DAYS_BACK = 366;
	static final LocalDate MAX_DATE = LocalDate.of(2100, 12, 31);

	private RecurrenceRules() {
	}

	/** Receita, despesa e transferência se repetem. */
	public static void requireType(TransactionType type) {
		if (type == null) {
			throw new FieldValidationException("type", "deve ser INCOME, EXPENSE ou TRANSFER");
		}
	}

	/**
	 * A forma da recorrência por tipo, apontando o campo. Transferência: destino obrigatório e diferente da origem, e sem
	 * categoria. Receita e despesa: categoria obrigatória e sem destino.
	 */
	public static void requireShape(TransactionType type, UUID accountId, UUID destinationAccountId, UUID categoryId) {
		if (type == TransactionType.TRANSFER) {
			if (destinationAccountId == null) {
				throw new FieldValidationException("destinationAccountId", "obrigatório para transferência");
			}
			if (destinationAccountId.equals(accountId)) {
				throw new FieldValidationException("destinationAccountId", "deve ser diferente da conta de origem");
			}
			if (categoryId != null) {
				throw new FieldValidationException("categoryId", "transferência não tem categoria");
			}
			return;
		}
		if (destinationAccountId != null) {
			throw new FieldValidationException("destinationAccountId", "só vale para transferência");
		}
		if (categoryId == null) {
			throw new FieldValidationException("categoryId", "obrigatório para receita e despesa");
		}
	}

	/** O início fica entre 366 dias antes de hoje e 2100-12-31. */
	public static void requireStart(LocalDate start, LocalDate today) {
		if (start.isBefore(today.minusDays(MAX_DAYS_BACK)) || start.isAfter(MAX_DATE)) {
			throw new FieldValidationException("startOn",
					"deve ser uma data de até 366 dias atrás até 2100-12-31");
		}
	}

	/** O fim, quando informado, fica entre o início e 2100-12-31. */
	public static void requireEnd(LocalDate start, LocalDate end) {
		if (end != null && (end.isBefore(start) || end.isAfter(MAX_DATE))) {
			throw new FieldValidationException("endOn", "deve ser uma data entre o início e 2100-12-31");
		}
	}

}
