package com.tostao.tostao_api.transaction;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

/** As invariantes da V5 valem no banco, mesmo para quem escreve direto (aqui, o dono do schema). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class TransactionSchemaTest {

	private static final long LIMIT = 1_000_000_000_000L;

	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private WorkspaceFixtures fixtures() {
		return new WorkspaceFixtures(owner, tokens);
	}

	private record World(Fixture fx, UUID account, UUID other, UUID expense, UUID income) {
	}

	private World world() {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID w = fx.workspaceId();
		return new World(fx, fixtures().insertAccount(w, "Corrente", "CHECKING", 0, false),
				fixtures().insertAccount(w, "Poupanca", "SAVINGS", 0, false),
				fixtures().insertCategory(w, null, "Mercado", "EXPENSE", false),
				fixtures().insertCategory(w, null, "Salario", "INCOME", false));
	}

	private void insert(World s, UUID account, UUID category, String type, long cents, LocalDate date,
			String description, String notes, UUID group) {
		owner.client().sql("""
				insert into transactions (workspace_id, account_id, category_id, type, amount_cents, occurred_on,
				                          description, notes, transfer_group_id, created_by)
				values (:w, :a, :c, :t, :m, :d, :s, :n, :g, :u)""")
				.param("w", s.fx().workspaceId()).param("a", account).param("c", category).param("t", type)
				.param("m", cents).param("d", date).param("s", description).param("n", notes).param("g", group)
				.param("u", s.fx().userId()).update();
	}

	private void expense(World s, long cents, LocalDate date, String description, String notes) {
		insert(s, s.account(), s.expense(), "EXPENSE", cents, date, description, notes, null);
	}

	private static final LocalDate DAY = LocalDate.of(2026, 11, 5);

	@Test
	void theCategoryMustHaveTheSameTypeAsTheTransaction() {
		World s = world();

		assertThatThrownBy(() -> insert(s, s.account(), s.expense(), "INCOME", 100, DAY, "x", null, null))
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("category_kind_fkey");
		assertThatThrownBy(() -> insert(s, s.account(), s.income(), "EXPENSE", -100, DAY, "x", null, null))
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("category_kind_fkey");
		insert(s, s.account(), s.expense(), "EXPENSE", -100, DAY, "ok", null, null);
		insert(s, s.account(), s.income(), "INCOME", 100, DAY, "ok", null, null);
	}

	@Test
	void aTransferCannotHaveACategory() {
		World s = world();

		assertThatThrownBy(() -> insert(s, s.account(), s.expense(), "TRANSFER", -100, DAY, "x", null, UUID.randomUUID()))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void signAndMagnitudeOfTheAmountAreEnforced() {
		World s = world();

		assertThatThrownBy(() -> expense(s, 0, DAY, "x", null)).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> expense(s, 100, DAY, "positiva", null))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insert(s, s.account(), s.income(), "INCOME", -100, DAY, "negativa", null, null))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> expense(s, -(LIMIT + 1), DAY, "grande", null))
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("amount_limit");
		assertThatThrownBy(() -> insert(s, s.account(), s.income(), "INCOME", LIMIT + 1, DAY, "grande", null, null))
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("amount_limit");
		expense(s, -LIMIT, DAY, "no limite", null);
	}

	@Test
	void descriptionAndNotesLimitsAreEnforced() {
		World s = world();

		for (String bad : new String[] { " com espaço no começo", "com espaço no fim ", "", "x".repeat(201) }) {
			assertThatThrownBy(() -> expense(s, -100, DAY, bad, null)).as("[" + bad + "]")
					.isInstanceOf(DataIntegrityViolationException.class);
		}
		assertThatThrownBy(() -> expense(s, -100, DAY, "x", "")).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> expense(s, -100, DAY, "x", "n".repeat(1001)))
				.isInstanceOf(DataIntegrityViolationException.class);
		expense(s, -100, DAY, "x".repeat(200), "n".repeat(1000));
	}

	@Test
	void datesMustFallBetween2000And2100() {
		World s = world();

		assertThatThrownBy(() -> expense(s, -100, LocalDate.of(1999, 12, 31), "x", null))
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("date_range");
		assertThatThrownBy(() -> expense(s, -100, LocalDate.of(2101, 1, 1), "x", null))
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("date_range");
		expense(s, -100, LocalDate.of(2000, 1, 1), "primeiro dia", null);
		expense(s, -100, LocalDate.of(2100, 12, 31), "ultimo dia", null);
	}

	@Test
	void aTransferGroupHasAtMostOneOutflowAndOneInflow() {
		World s = world();
		UUID group = UUID.randomUUID();
		insert(s, s.account(), null, "TRANSFER", -100, DAY, "saida", null, group);
		insert(s, s.other(), null, "TRANSFER", 100, DAY, "entrada", null, group);

		assertThatThrownBy(() -> insert(s, s.account(), null, "TRANSFER", -100, DAY, "segunda saida", null, group))
				.isInstanceOf(DuplicateKeyException.class).hasMessageContaining("transfer_leg_key");
		assertThatThrownBy(() -> insert(s, s.other(), null, "TRANSFER", 100, DAY, "segunda entrada", null, group))
				.isInstanceOf(DuplicateKeyException.class).hasMessageContaining("transfer_leg_key");
		assertThatThrownBy(() -> insert(s, s.other(), null, "TRANSFER", 250, DAY, "terceira ponta", null, group))
				.isInstanceOf(DuplicateKeyException.class);
		insert(s, s.account(), null, "TRANSFER", -100, DAY, "outro grupo", null, UUID.randomUUID());
	}

}
