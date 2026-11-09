package com.tostao.tostao_api.invoice;

import java.time.Clock;
import java.time.YearMonth;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.tostao.tostao_api.common.error.FieldValidationException;
import com.tostao.tostao_api.dashboard.Period;
import com.tostao.tostao_api.invoice.dto.CardSummaryResponse;
import com.tostao.tostao_api.invoice.dto.InvoiceListResponse;
import com.tostao.tostao_api.invoice.dto.InvoiceResponse;

/**
 * Faturas de um cartão de crédito (só leitura). Mês e intervalo são validados aqui, antes de qualquer
 * transação: erro de formato nunca chega ao banco.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{wid}/accounts/{accountId}")
class InvoiceController {

	/** No máximo 36 meses de vencimento por lista. */
	private static final int MAX_MONTHS = 36;

	private final InvoiceService invoices;
	private final Clock clock;

	InvoiceController(InvoiceService invoices, Clock clock) {
		this.invoices = invoices;
		this.clock = clock;
	}

	/** A fatura do mês de vencimento {@code month} (AAAA-MM), com os lançamentos. */
	@GetMapping("/invoices/{month}")
	InvoiceResponse invoice(@PathVariable("wid") UUID workspaceId, @PathVariable("accountId") UUID accountId,
			@PathVariable("month") String month) {
		YearMonth dueMonth = YearMonth.from(Period.ofMonth(month).start());
		return invoices.invoice(workspaceId, accountId, dueMonth, Period.today(clock.instant()));
	}

	/** As faturas de um intervalo de meses de vencimento; sem intervalo, as seis até a aberta. */
	@GetMapping("/invoices")
	InvoiceListResponse list(@PathVariable("wid") UUID workspaceId, @PathVariable("accountId") UUID accountId,
			@RequestParam(name = "from", required = false) String from,
			@RequestParam(name = "to", required = false) String to) {
		YearMonth fromMonth = from == null ? null : YearMonth.from(Period.ofMonth(from, "from").start());
		YearMonth toMonth = to == null ? null : YearMonth.from(Period.ofMonth(to, "to").start());
		if (fromMonth != null && toMonth != null) {
			if (fromMonth.isAfter(toMonth)) {
				throw new FieldValidationException("from", "deve ser anterior ou igual a to");
			}
			if (InvoiceService.monthsBetween(fromMonth, toMonth) > MAX_MONTHS) {
				throw new FieldValidationException("to", "o intervalo pode ter no máximo " + MAX_MONTHS + " meses");
			}
		}
		return invoices.invoices(workspaceId, accountId, fromMonth, toMonth, Period.today(clock.instant()));
	}

	/** Limite, devido, disponível e a fatura aberta do cartão. */
	@GetMapping("/card-summary")
	CardSummaryResponse summary(@PathVariable("wid") UUID workspaceId, @PathVariable("accountId") UUID accountId) {
		return invoices.summary(workspaceId, accountId, Period.today(clock.instant()));
	}

}
