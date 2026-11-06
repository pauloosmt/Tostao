package com.tostao.tostao_api.transaction;

import static com.tostao.tostao_api.support.AuthSupport.session;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

/**
 * Concorrência (SC-002 e SC-010): nunca nasce lançamento em conta ou categoria arquivada, uma transferência
 * nunca fica incoerente nem com uma só ponta excluída, e operações opostas não se travam. Qualquer 500 (ou
 * impasse) aparece como exceção do {@code MockMvc} e derruba o teste.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class TransactionConcurrencyTest {

	private static final LocalDate DAY = LocalDate.of(2026, 11, 5);

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;
	@Autowired
	PostgreSQLContainer postgres;

	private WorkspaceFixtures fixtures() {
		return new WorkspaceFixtures(owner, tokens);
	}

	private String base(Fixture fx) {
		return "/api/v1/workspaces/" + fx.workspaceId();
	}

	private int create(Fixture fx, String json) throws Exception {
		return mvc.perform(post(base(fx) + "/transactions").cookie(session(fx.token()))
				.contentType(MediaType.APPLICATION_JSON).content(json)).andReturn().getResponse().getStatus();
	}

	private int edit(Fixture fx, UUID id, String json) throws Exception {
		return mvc.perform(put(base(fx) + "/transactions/" + id).cookie(session(fx.token()))
				.contentType(MediaType.APPLICATION_JSON).content(json)).andReturn().getResponse().getStatus();
	}

	private int remove(Fixture fx, UUID id) throws Exception {
		return mvc.perform(delete(base(fx) + "/transactions/" + id).cookie(session(fx.token()))).andReturn()
				.getResponse().getStatus();
	}

	private int archiveAccount(Fixture fx, UUID account) throws Exception {
		return mvc.perform(post(base(fx) + "/accounts/" + account + "/archive").cookie(session(fx.token())))
				.andReturn().getResponse().getStatus();
	}

	private int archiveCategory(Fixture fx, UUID category) throws Exception {
		return mvc.perform(post(base(fx) + "/categories/" + category + "/archive").cookie(session(fx.token())))
				.andReturn().getResponse().getStatus();
	}

	private static String expense(UUID account, UUID category, long cents) {
		return "{\"type\":\"EXPENSE\",\"accountId\":\"%s\",\"amountCents\":%d,\"occurredOn\":\"2026-11-05\",\"description\":\"Corrida\",\"categoryId\":\"%s\"}"
				.formatted(account, cents, category);
	}

	private static String transfer(UUID from, UUID to, long cents, String description) {
		return "{\"type\":\"TRANSFER\",\"accountId\":\"%s\",\"destinationAccountId\":\"%s\",\"amountCents\":%d,\"occurredOn\":\"2026-11-05\",\"description\":\"%s\"}"
				.formatted(from, to, cents, description);
	}

	private static String transferEdit(UUID from, UUID to, long cents, String description) {
		return "{\"accountId\":\"%s\",\"destinationAccountId\":\"%s\",\"amountCents\":%d,\"occurredOn\":\"2026-11-06\",\"description\":\"%s\"}"
				.formatted(from, to, cents, description);
	}

	/** Dispara todas as tarefas ao mesmo tempo (barreira) e devolve os resultados na ordem. */
	private <T> List<T> race(List<Callable<T>> jobs) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(jobs.size());
		CyclicBarrier barrier = new CyclicBarrier(jobs.size());
		try {
			List<Future<T>> futures = new ArrayList<>();
			for (Callable<T> job : jobs) {
				futures.add(pool.submit(() -> {
					barrier.await(10, TimeUnit.SECONDS);
					return job.call();
				}));
			}
			List<T> results = new ArrayList<>();
			for (Future<T> f : futures) {
				results.add(f.get(60, TimeUnit.SECONDS));
			}
			return results;
		}
		finally {
			pool.shutdownNow();
		}
	}

	private long count(String where, Object... args) {
		var sql = owner.client().sql("select count(*) from transactions where " + where);
		for (int i = 0; i < args.length; i++) {
			sql = sql.param("p" + i, args[i]);
		}
		return sql.query(Long.class).single();
	}

	private Connection ownerConnection() throws Exception {
		Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
				postgres.getPassword());
		c.setAutoCommit(false);
		return c;
	}

	/**
	 * Segura o bloqueio de um {@code UPDATE ... archived = true} não confirmado, dispara a operação em outra
	 * thread e confere que ela fica esperando; ao confirmar, a operação vê o arquivamento e é recusada.
	 */
	private int holdArchiveThenRun(String archiveSql, UUID target, Callable<Integer> operation) throws Exception {
		try (Connection c = ownerConnection()) {
			try (PreparedStatement ps = c.prepareStatement(archiveSql)) {
				ps.setObject(1, target);
				assertThat(ps.executeUpdate()).isEqualTo(1);
			}
			ExecutorService pool = Executors.newSingleThreadExecutor();
			try {
				Future<Integer> future = pool.submit(operation);
				Thread.sleep(700);
				assertThat(future.isDone()).as("a operação deve esperar o arquivamento terminar").isFalse();
				c.commit();
				return future.get(30, TimeUnit.SECONDS);
			}
			finally {
				pool.shutdownNow();
			}
		}
	}

	// ----------------------------------------------------------------------------------------------
	// Bloqueio determinístico: o arquivamento que chega primeiro sempre vence
	// ----------------------------------------------------------------------------------------------

	@Test
	void anAccountBeingArchivedRefusesTheTransactionThatWasWaiting() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID account = fixtures().insertAccount(fx.workspaceId(), "Corrente", "CHECKING", 0, false);
		UUID category = fixtures().insertCategory(fx.workspaceId(), null, "Comida", "EXPENSE", false);

		int status = holdArchiveThenRun("update accounts set archived = true where id = ?", account,
				() -> create(fx, expense(account, category, 100)));

		assertThat(status).isEqualTo(409);
		assertThat(count("account_id = :p0", account)).isZero();
	}

	@Test
	void aCategoryBeingArchivedRefusesTheTransactionThatWasWaiting() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID account = fixtures().insertAccount(fx.workspaceId(), "Corrente", "CHECKING", 0, false);
		UUID category = fixtures().insertCategory(fx.workspaceId(), null, "Comida", "EXPENSE", false);

		int status = holdArchiveThenRun("update categories set archived = true where id = ?", category,
				() -> create(fx, expense(account, category, 100)));

		assertThat(status).isEqualTo(409);
		assertThat(count("category_id = :p0", category)).isZero();
	}

	@Test
	void aSubcategoryBeingArchivedWithItsRootRefusesTheTransactionThatWasWaiting() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID account = fixtures().insertAccount(fx.workspaceId(), "Corrente", "CHECKING", 0, false);
		UUID root = fixtures().insertCategory(fx.workspaceId(), null, "Comida", "EXPENSE", false);
		UUID sub = fixtures().insertCategory(fx.workspaceId(), root, "Feira", "EXPENSE", false);

		int status = holdArchiveThenRun("update categories set archived = true where parent_id = ?", root,
				() -> create(fx, expense(account, sub, 100)));

		assertThat(status).isEqualTo(409);
		assertThat(count("category_id = :p0", sub)).isZero();
	}

	@Test
	void aTransferDestinationBeingArchivedRefusesTheTransferThatWasWaiting() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID origin = fixtures().insertAccount(fx.workspaceId(), "Origem", "CHECKING", 0, false);
		UUID destination = fixtures().insertAccount(fx.workspaceId(), "Destino", "SAVINGS", 0, false);

		int status = holdArchiveThenRun("update accounts set archived = true where id = ?", destination,
				() -> create(fx, transfer(origin, destination, 100, "Espera")));

		assertThat(status).isEqualTo(409);
		assertThat(count("account_id in (:p0, :p1)", origin, destination)).isZero();
	}

	@Test
	void anEditMovingToAnAccountBeingArchivedIsRefused() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID account = fixtures().insertAccount(fx.workspaceId(), "Corrente", "CHECKING", 0, false);
		UUID target = fixtures().insertAccount(fx.workspaceId(), "Alvo", "CHECKING", 0, false);
		UUID category = fixtures().insertCategory(fx.workspaceId(), null, "Comida", "EXPENSE", false);
		UUID existing = fixtures().insertLedgerTransaction(fx.workspaceId(), account, fx.userId(), category, "EXPENSE",
				100, DAY, "Existente", false);

		int status = holdArchiveThenRun("update accounts set archived = true where id = ?", target,
				() -> edit(fx, existing, "{\"accountId\":\"%s\",\"amountCents\":100,\"occurredOn\":\"2026-11-05\",\"description\":\"Existente\",\"categoryId\":\"%s\"}"
						.formatted(target, category)));

		assertThat(status).isEqualTo(409);
		assertThat(count("account_id = :p0", target)).isZero();
	}

	// ----------------------------------------------------------------------------------------------
	// Corridas repetidas: as invariantes valem em qualquer intercalação
	// ----------------------------------------------------------------------------------------------

	@Test
	void archivingAnAccountWhileCreatingTransactionsNeverLeavesAnInconsistency() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID category = fixtures().insertCategory(fx.workspaceId(), null, "Comida", "EXPENSE", false);

		for (int round = 0; round < 20; round++) {
			UUID account = fixtures().insertAccount(fx.workspaceId(), "Conta " + round, "CHECKING", 0, false);
			List<Callable<Integer>> jobs = new ArrayList<>();
			jobs.add(() -> archiveAccount(fx, account));
			for (int i = 0; i < 3; i++) {
				long cents = 100 + i;
				jobs.add(() -> create(fx, expense(account, category, cents)));
			}

			List<Integer> statuses = race(jobs);

			assertThat(statuses.get(0)).isEqualTo(200);
			long created = statuses.subList(1, 4).stream().filter(s -> s == 201).count();
			assertThat(statuses.subList(1, 4)).allMatch(s -> s == 201 || s == 409);
			assertThat(count("account_id = :p0", account)).isEqualTo(created);
			// depois do arquivamento, nada novo entra
			assertThat(create(fx, expense(account, category, 999))).isEqualTo(409);
			assertThat(count("account_id = :p0", account)).isEqualTo(created);
		}
	}

	@Test
	void archivingACategoryWhileCreatingTransactionsNeverLeavesAnInconsistency() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID account = fixtures().insertAccount(fx.workspaceId(), "Corrente", "CHECKING", 0, false);

		for (int round = 0; round < 20; round++) {
			UUID root = fixtures().insertCategory(fx.workspaceId(), null, "Raiz " + round, "EXPENSE", false);
			UUID sub = fixtures().insertCategory(fx.workspaceId(), root, "Filha " + round, "EXPENSE", false);
			List<Callable<Integer>> jobs = new ArrayList<>();
			jobs.add(() -> archiveCategory(fx, root));
			jobs.add(() -> create(fx, expense(account, sub, 100)));
			jobs.add(() -> create(fx, expense(account, root, 101)));

			List<Integer> statuses = race(jobs);

			assertThat(statuses.get(0)).isEqualTo(200);
			assertThat(statuses.subList(1, 3)).allMatch(s -> s == 201 || s == 409);
			assertThat(create(fx, expense(account, sub, 999))).isEqualTo(409);
			assertThat(create(fx, expense(account, root, 999))).isEqualTo(409);
		}
	}

	@Test
	void editingAndDeletingTheSameTransferNeverLeavesHalfOfIt() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID a = fixtures().insertAccount(fx.workspaceId(), "A", "CHECKING", 0, false);
		UUID b = fixtures().insertAccount(fx.workspaceId(), "B", "SAVINGS", 0, false);

		for (int round = 0; round < 20; round++) {
			UUID[] pair = fixtures().insertTransferPair(fx.workspaceId(), a, b, fx.userId(), 1_000, DAY, "Original");
			List<Callable<Integer>> jobs = List.of(
					() -> remove(fx, pair[0]),
					() -> edit(fx, pair[1], transferEdit(a, b, 2_000, "Editada")),
					() -> edit(fx, pair[0], transferEdit(a, b, 3_000, "Editada 2")));

			List<Integer> statuses = race(jobs);

			assertThat(statuses.get(0)).isEqualTo(204);
			assertThat(statuses.subList(1, 3)).allMatch(s -> s == 200 || s == 404);
			List<Map<String, Object>> legs = owner.client().sql("""
					select amount_cents, description, occurred_on, deleted_at from transactions
					where id in (:ids) order by amount_cents""").param("ids", List.of(pair[0], pair[1]))
					.query().listOfRows();
			assertThat(legs).hasSize(2);
			// a exclusão sempre vence no fim, e as duas pontas ficam excluídas juntas
			assertThat(legs.get(0).get("deleted_at")).isNotNull();
			assertThat(legs.get(1).get("deleted_at")).isNotNull();
			assertThat(Math.abs(((Number) legs.get(0).get("amount_cents")).longValue()))
					.isEqualTo(((Number) legs.get(1).get("amount_cents")).longValue());
			assertThat(legs.get(0).get("description")).isEqualTo(legs.get(1).get("description"));
			assertThat(legs.get(0).get("occurred_on")).isEqualTo(legs.get(1).get("occurred_on"));
		}
	}

	@Test
	void simultaneousEditsOfTheSameTransferLeaveTheTwoLegsEqual() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID a = fixtures().insertAccount(fx.workspaceId(), "A", "CHECKING", 0, false);
		UUID b = fixtures().insertAccount(fx.workspaceId(), "B", "SAVINGS", 0, false);

		for (int round = 0; round < 20; round++) {
			UUID[] pair = fixtures().insertTransferPair(fx.workspaceId(), a, b, fx.userId(), 1_000, DAY, "Original");
			List<Callable<Integer>> jobs = List.of(
					() -> edit(fx, pair[0], transferEdit(a, b, 2_000, "Pela saida")),
					() -> edit(fx, pair[1], transferEdit(a, b, 3_000, "Pela entrada")),
					() -> edit(fx, pair[0], transferEdit(b, a, 4_000, "Invertida")));

			List<Integer> statuses = race(jobs);

			assertThat(statuses).allMatch(s -> s == 200);
			List<Map<String, Object>> legs = owner.client().sql("""
					select account_id, amount_cents, description, occurred_on from transactions
					where id in (:ids) order by amount_cents""").param("ids", List.of(pair[0], pair[1]))
					.query().listOfRows();
			long out = ((Number) legs.get(0).get("amount_cents")).longValue();
			long in = ((Number) legs.get(1).get("amount_cents")).longValue();
			assertThat(out).isNegative();
			assertThat(in).isEqualTo(-out);
			assertThat(legs.get(0).get("description")).isEqualTo(legs.get(1).get("description"));
			assertThat(legs.get(0).get("occurred_on")).isEqualTo(legs.get(1).get("occurred_on"));
			assertThat(legs.get(0).get("account_id")).isNotEqualTo(legs.get(1).get("account_id"));
		}
	}

	@Test
	void oppositeTransfersAtTheSameTimeNeverDeadlock() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID a = fixtures().insertAccount(fx.workspaceId(), "A", "CHECKING", 0, false);
		UUID b = fixtures().insertAccount(fx.workspaceId(), "B", "SAVINGS", 0, false);

		for (int round = 0; round < 20; round++) {
			List<Callable<Integer>> jobs = new ArrayList<>();
			for (int i = 0; i < 3; i++) {
				long cents = 100 + i;
				jobs.add(() -> create(fx, transfer(a, b, cents, "A para B")));
				jobs.add(() -> create(fx, transfer(b, a, cents, "B para A")));
			}

			assertThat(race(jobs)).allMatch(s -> s == 201);
		}
		assertThat(count("workspace_id = :p0", fx.workspaceId())).isEqualTo(20 * 6 * 2);
		assertThat(owner.client().sql("select coalesce(sum(amount_cents), 0) from transactions where workspace_id = :w")
				.param("w", fx.workspaceId()).query(Long.class).single()).isZero();
	}

}
