package com.tostao.tostao_api.imports;

import static com.tostao.tostao_api.imports.ImportScenario.count;
import static com.tostao.tostao_api.imports.ImportScenario.fingerprint;
import static com.tostao.tostao_api.imports.ImportScenario.seed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;
import java.util.function.Function;

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
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.common.error.AccountNotFoundException;
import com.tostao.tostao_api.common.error.CategoryNotFoundException;
import com.tostao.tostao_api.imports.dto.ImportConfirmRequest;
import com.tostao.tostao_api.imports.dto.ImportLineRequest;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;
import com.tostao.tostao_api.workspace.TenantSession;

/**
 * As duas camadas de isolamento da importação, uma de cada vez (FR-021, SC-010). Camada de dados: o SQL roda como
 * {@code tostao_app} <b>sem filtro de workspace</b>, e só o RLS barra. Camada de serviço: o serviço roda com a conexão do
 * dono (RLS neutralizado) e o filtro explícito por {@code workspace_id} isola sozinho.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ImportLayersTest {

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

	private static void actAs(Fixture who) {
		Jwt jwt = Jwt.withTokenValue("teste").header("alg", "none").subject(who.userId().toString()).build();
		SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
	}

	private ImportScenario scenario() {
		return ImportScenario.create(new WorkspaceFixtures(owner, tokens));
	}

	private <T> T asApp(Fixture who, UUID workspace, Function<JdbcClient, T> work) {
		return new TransactionTemplate(transactionManager).execute(status -> {
			tenant.enterWorkspace(who.userId(), workspace);
			return work.apply(appJdbc);
		});
	}

	private static ImportConfirmRequest request(UUID category, String... keys) {
		java.util.List<ImportLineRequest> lines = new java.util.ArrayList<>();
		for (String key : keys) {
			lines.add(new ImportLineRequest(key, LocalDate.of(2026, 11, 5), "Compra " + key, -100L, category));
		}
		return new ImportConfirmRequest(lines);
	}

	private static final String INSERT = """
			insert into transactions (workspace_id, account_id, type, amount_cents, occurred_on, description, status, created_by, import_key)
			values (:w, :a, 'EXPENSE', -100, date '2026-11-05', 'Compra', 'CLEARED', :u, :k)
			on conflict (account_id, import_key) where import_key is not null do nothing""";

	// ---- camada de dados --------------------------------------------------------------------------------------

	@Test
	void theDatabaseLayerAloneRefusesToInsertIntoAnotherWorkspace() throws Exception {
		ImportScenario a = scenario();
		ImportScenario b = scenario();
		String before = fingerprint(owner, b.workspace());

		assertThatThrownBy(() -> asApp(a.owner(), a.workspace(), db -> db.sql(INSERT).param("w", b.workspace())
				.param("a", b.checking()).param("u", a.owner().userId()).param("k", "id:invadida").update()))
				.rootCause().hasMessageContaining("row-level security");

		assertThat(fingerprint(owner, b.workspace())).isEqualTo(before);
	}

	@Test
	void theDatabaseLayerAloneNeverSeesNorConflictsWithAnotherWorkspacesKey() throws Exception {
		ImportScenario a = scenario();
		ImportScenario b = scenario();
		seed(owner, b.workspace(), b.checking(), b.owner().userId(), null, -100, LocalDate.of(2026, 11, 5), "Deles", "id:1", false);
		seed(owner, a.workspace(), a.checking(), a.owner().userId(), null, -100, LocalDate.of(2026, 11, 5), "Meu", "id:2", false);

		// sem filtro de workspace de propósito: só o RLS protege
		long seen = asApp(a.owner(), a.workspace(), db -> db.sql("select count(*) from transactions where import_key is not null")
				.query(Long.class).single());
		long theirs = asApp(a.owner(), a.workspace(), db -> db.sql("select count(*) from transactions where import_key = 'id:1'")
				.query(Long.class).single());
		// a mesma chave na minha conta não colide com a deles: são contas diferentes
		int inserted = asApp(a.owner(), a.workspace(), db -> db.sql(INSERT).param("w", a.workspace()).param("a", a.checking())
				.param("u", a.owner().userId()).param("k", "id:1").update());

		assertThat(seen).as("só a chave de A").isEqualTo(1L);
		assertThat(theirs).isZero();
		assertThat(inserted).isEqualTo(1);
	}

	@Test
	void theDatabaseLayerAloneStopsAViewerAndNoContext() throws Exception {
		ImportScenario a = scenario();
		String before = fingerprint(owner, a.workspace());

		assertThatThrownBy(() -> asApp(a.viewer(), a.workspace(), db -> db.sql(INSERT).param("w", a.workspace())
				.param("a", a.checking()).param("u", a.viewer().userId()).param("k", "id:leitor").update()))
				.rootCause().hasMessageContaining("row-level security");
		assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(status -> appJdbc.sql(INSERT)
				.param("w", a.workspace()).param("a", a.checking()).param("u", a.owner().userId()).param("k", "id:sem-contexto")
				.update())).rootCause().hasMessageContaining("row-level security");

		assertThat(fingerprint(owner, a.workspace())).isEqualTo(before);
	}

	// ---- camada de serviço ------------------------------------------------------------------------------------

	@Test
	void theServiceFilterAloneRefusesAnotherWorkspacesAccountAndCategoryWithRlsBypassed() throws Exception {
		ImportService imports = new ImportService(owner.client());
		ImportScenario a = scenario();
		ImportScenario b = scenario();
		String bBefore = fingerprint(owner, b.workspace());
		String aBefore = fingerprint(owner, a.workspace());
		actAs(a.owner());

		// a conta de B pelo workspace de A
		assertThatThrownBy(() -> imports.confirm(a.workspace(), b.checking(), request(null, "x"))).isInstanceOf(AccountNotFoundException.class);
		assertThatThrownBy(() -> imports.preview(a.workspace(), b.checking(), Statements.bytes("banco.ofx"))).isInstanceOf(AccountNotFoundException.class);
		// a categoria de B numa conta de A
		assertThatThrownBy(() -> imports.confirm(a.workspace(), a.checking(), request(b.food(), "y"))).isInstanceOf(CategoryNotFoundException.class);

		assertThat(fingerprint(owner, b.workspace())).isEqualTo(bBefore);
		assertThat(fingerprint(owner, a.workspace())).isEqualTo(aBefore);
	}

	@Test
	void theServiceFilterAloneWritesOnlyToTheGivenWorkspaceAndKeepsKeysApart() throws Exception {
		ImportService imports = new ImportService(owner.client());
		ImportScenario a = scenario();
		ImportScenario b = scenario();
		seed(owner, b.workspace(), b.checking(), b.owner().userId(), null, -100, LocalDate.of(2026, 11, 5), "Deles", "k1", false);
		String bBefore = fingerprint(owner, b.workspace());
		actAs(a.owner());

		var result = imports.confirm(a.workspace(), a.checking(), request(a.food(), "k1", "k2"));

		assertThat(result.created()).hasSize(2);
		assertThat(result.skipped()).isZero();
		assertThat(count(owner, a.checking())).isEqualTo(2);
		assertThat(fingerprint(owner, b.workspace())).isEqualTo(bBefore);
	}

}
