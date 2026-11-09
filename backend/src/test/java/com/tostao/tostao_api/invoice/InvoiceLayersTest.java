package com.tostao.tostao_api.invoice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.account.AccountService;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.common.error.AccountNotFoundException;
import com.tostao.tostao_api.invoice.dto.CardSummaryResponse;
import com.tostao.tostao_api.invoice.dto.InvoiceListResponse;
import com.tostao.tostao_api.invoice.dto.InvoiceResponse;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.workspace.TenantSession;

/**
 * As duas camadas de isolamento da fatura, uma de cada vez (SC-008). Camada de dados: as consultas rodam como
 * {@code tostao_app} (que não ignora o RLS) pedindo, de propósito, o workspace errado no filtro. Camada de
 * serviço: o serviço roda com a conexão do dono, que ignora o RLS, e o filtro explícito por
 * {@code workspace_id} e conta isola sozinho.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class InvoiceLayersTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 11, 9);
	private static final YearMonth NOVEMBER = YearMonth.of(2024, 11);

	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;
	@Autowired
	JdbcClient appJdbc;
	@Autowired
	TenantSession tenant;
	@Autowired
	PlatformTransactionManager transactionManager;

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
	}

	private static void actAs(UUID userId) {
		Jwt jwt = Jwt.withTokenValue("teste").header("alg", "none").subject(userId.toString()).build();
		SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
	}

	private enum Mode {
		WORKSPACE, USER_ONLY, NONE
	}

	/** Executa a consulta como {@code tostao_app} no contexto dado, com os parâmetros da fatura de novembro de 2024. */
	private <T> T asApp(String sql, Mode mode, UUID user, UUID contextWorkspace, UUID askedWorkspace, UUID account,
			java.util.function.Function<JdbcClient.StatementSpec, T> read) {
		return new TransactionTemplate(transactionManager).execute(status -> {
			if (mode == Mode.WORKSPACE) {
				tenant.enterWorkspace(user, contextWorkspace);
			} else if (mode == Mode.USER_ONLY) {
				tenant.enterUser(user);
			}
			return read.apply(appJdbc.sql(sql).param("workspaceId", askedWorkspace).param("accountId", account)
					.param("start", LocalDate.of(2024, 10, 21)).param("end", LocalDate.of(2024, 11, 20))
					.param("closing", LocalDate.of(2024, 11, 20)).param("nextClosing", LocalDate.of(2024, 12, 20))
					.param("min", LocalDate.of(2024, 9, 21)).param("max", LocalDate.of(2024, 12, 20)));
		});
	}

	private long rows(String sql, Mode mode, UUID user, UUID contextWorkspace, UUID askedWorkspace, UUID account) {
		return asApp(sql, mode, user, contextWorkspace, askedWorkspace, account,
				spec -> (long) spec.query((rs, i) -> 1).list().size());
	}

	private long value(String sql, Mode mode, UUID user, UUID contextWorkspace, UUID askedWorkspace, UUID account) {
		return asApp(sql, mode, user, contextWorkspace, askedWorkspace, account,
				spec -> spec.query(Long.class).single());
	}

	@Test
	void withTheRightContextTheQueriesSeeTheWorkspacesOwnRows() {
		InvoiceScenario a = InvoiceScenario.create(new WorkspaceFixtures(owner, tokens));
		UUID w = a.owner().workspaceId();

		assertThat(rows(InvoiceService.LINES_SQL, Mode.WORKSPACE, a.owner().userId(), w, w, a.card())).isEqualTo(5);
		assertThat(rows(InvoiceService.DAILY_SQL, Mode.WORKSPACE, a.owner().userId(), w, w, a.card())).isPositive();
		assertThat(value(InvoiceService.TOTAL_SQL, Mode.WORKSPACE, a.owner().userId(), w, w, a.card()))
				.isEqualTo(62_000);
		assertThat(value(InvoiceService.PAID_SQL, Mode.WORKSPACE, a.owner().userId(), w, w, a.card()))
				.isEqualTo(25_000);
	}

	@Test
	void askingForAnotherWorkspaceInTheFilterReturnsNothingBecauseOfRls() {
		WorkspaceFixtures fixtures = new WorkspaceFixtures(owner, tokens);
		InvoiceScenario a = InvoiceScenario.create(fixtures);
		InvoiceScenario b = InvoiceScenario.create(fixtures);
		UUID wa = a.owner().workspaceId();
		UUID wb = b.owner().workspaceId();

		// no contexto de A, pedindo o workspace e o cartão de B
		assertThat(rows(InvoiceService.LINES_SQL, Mode.WORKSPACE, a.owner().userId(), wa, wb, b.card())).isZero();
		assertThat(rows(InvoiceService.DAILY_SQL, Mode.WORKSPACE, a.owner().userId(), wa, wb, b.card())).isZero();
		assertThat(value(InvoiceService.TOTAL_SQL, Mode.WORKSPACE, a.owner().userId(), wa, wb, b.card())).isZero();
		assertThat(value(InvoiceService.PAID_SQL, Mode.WORKSPACE, a.owner().userId(), wa, wb, b.card())).isZero();
		// e o cartão de B no workspace de A também não aparece
		assertThat(rows(InvoiceService.LINES_SQL, Mode.WORKSPACE, a.owner().userId(), wa, wa, b.card())).isZero();
		// a consulta de contas (RLS de accounts) também não mostra o cartão do outro
		assertThat(rows("select id from accounts where workspace_id = :workspaceId and id = :accountId", Mode.WORKSPACE,
				a.owner().userId(), wa, wb, b.card())).isZero();
	}

	@Test
	void withoutAWorkspaceContextTheQueriesSeeNothing() {
		InvoiceScenario a = InvoiceScenario.create(new WorkspaceFixtures(owner, tokens));
		UUID w = a.owner().workspaceId();

		for (Mode mode : new Mode[] { Mode.USER_ONLY, Mode.NONE }) {
			UUID user = mode == Mode.NONE ? null : a.owner().userId();
			assertThat(rows(InvoiceService.LINES_SQL, mode, user, null, w, a.card())).as(mode.name()).isZero();
			assertThat(rows(InvoiceService.DAILY_SQL, mode, user, null, w, a.card())).as(mode.name()).isZero();
			assertThat(value(InvoiceService.TOTAL_SQL, mode, user, null, w, a.card())).as(mode.name()).isZero();
			assertThat(value(InvoiceService.PAID_SQL, mode, user, null, w, a.card())).as(mode.name()).isZero();
		}
	}

	@Test
	void theServiceFilterAloneIsolatesWorkspacesWithRlsBypassed() {
		WorkspaceFixtures fixtures = new WorkspaceFixtures(owner, tokens);
		InvoiceService service = new InvoiceService(owner.client(), new AccountService(owner.client()));
		InvoiceScenario a = InvoiceScenario.create(fixtures);
		InvoiceScenario b = InvoiceScenario.create(fixtures);
		UUID wa = a.owner().workspaceId();
		UUID wb = b.owner().workspaceId();
		// no workspace B o cartão ganha uma compra enorme na mesma fatura
		fixtures.insertLedgerTransaction(wb, b.card(), b.owner().userId(), b.food(), "EXPENSE", 999_999,
				LocalDate.of(2024, 11, 10), "Só de B", false);
		String bBefore = InvoiceScenario.financeFingerprint(owner, wb);
		actAs(a.owner().userId());

		// o cartão de B pelo workspace de A, e o de A pelo workspace de B: não encontrado
		assertThatThrownBy(() -> service.invoice(wa, b.card(), NOVEMBER, TODAY))
				.isInstanceOf(AccountNotFoundException.class);
		assertThatThrownBy(() -> service.invoice(wb, a.card(), NOVEMBER, TODAY))
				.isInstanceOf(AccountNotFoundException.class);
		assertThatThrownBy(() -> service.invoices(wa, b.card(), NOVEMBER, NOVEMBER, TODAY))
				.isInstanceOf(AccountNotFoundException.class);
		assertThatThrownBy(() -> service.summary(wa, b.card(), TODAY)).isInstanceOf(AccountNotFoundException.class);

		// o cartão de A nunca soma lançamentos de B
		InvoiceResponse forA = service.invoice(wa, a.card(), NOVEMBER, TODAY);
		assertThat(forA.totalCents()).isEqualTo(62_000);
		assertThat(forA.lines()).hasSize(5);
		InvoiceListResponse list = service.invoices(wa, a.card(), NOVEMBER, NOVEMBER, TODAY);
		assertThat(list.invoices().get(0).totalCents()).isEqualTo(62_000);
		CardSummaryResponse summary = service.summary(wa, a.card(), TODAY);
		assertThat(summary.owedCents()).isEqualTo(54_345);
		// e a de B soma o que é de B
		assertThat(service.invoice(wb, b.card(), NOVEMBER, TODAY).totalCents()).isEqualTo(62_000 + 999_999);

		// nada foi escrito em B por nenhuma consulta
		assertThat(InvoiceScenario.financeFingerprint(owner, wb)).isEqualTo(bBefore);
	}

}
