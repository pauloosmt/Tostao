package com.tostao.tostao_api.support;

import java.util.UUID;

import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;

/**
 * Cria usuários, workspaces, papéis, contas e lançamentos pela conexão do dono (que ignora o RLS).
 * Nenhuma rota atribui os papéis EDITOR e VIEWER antes da fase 3, então os testes os semeiam aqui.
 */
public class WorkspaceFixtures {

	/** Um usuário que é membro de um workspace, com o token de sessão dele. */
	public record Fixture(UUID workspaceId, UUID userId, String token) {
	}

	private final OwnerJdbc owner;
	private final SessionTokenService tokens;

	public WorkspaceFixtures(OwnerJdbc owner, SessionTokenService tokens) {
		this.owner = owner;
		this.tokens = tokens;
	}

	public UUID newUser() {
		return owner.client()
				.sql("insert into users (email, password_hash, name) values (:e, 'x', 'Teste') returning id")
				.param("e", "fx-" + UUID.randomUUID() + "@example.com")
				.query(UUID.class).single();
	}

	public String tokenOf(UUID userId) {
		return tokens.issue(userId, 0);
	}

	/** Usuário novo, dono de um workspace novo. */
	public Fixture workspaceWithOwner() {
		UUID user = newUser();
		UUID workspace = owner.client().sql("insert into workspaces (name) values ('Fixture') returning id")
				.query(UUID.class).single();
		addMembership(workspace, user, "OWNER");
		return new Fixture(workspace, user, tokenOf(user));
	}

	/** Usuário novo, membro do workspace com o papel dado (OWNER, EDITOR ou VIEWER). */
	public Fixture addMember(UUID workspaceId, String role) {
		UUID user = newUser();
		addMembership(workspaceId, user, role);
		return new Fixture(workspaceId, user, tokenOf(user));
	}

	private void addMembership(UUID workspaceId, UUID userId, String role) {
		owner.client().sql("insert into workspace_members (workspace_id, user_id, role) values (:w, :u, :r)")
				.param("w", workspaceId).param("u", userId).param("r", role).update();
	}

	public UUID insertAccount(UUID workspaceId, String name, String type, long initialCents, boolean archived) {
		return owner.client().sql("""
				insert into accounts (workspace_id, name, type, initial_balance_cents, archived)
				values (:w, :n, :t, :i, :a) returning id""")
				.param("w", workspaceId).param("n", name).param("t", type).param("i", initialCents)
				.param("a", archived).query(UUID.class).single();
	}

	/** Lançamento de receita (valor positivo) ou despesa (valor negativo), opcionalmente excluído. */
	public UUID insertTransaction(UUID workspaceId, UUID accountId, UUID userId, long amountCents, boolean deleted) {
		return owner.client().sql("""
				insert into transactions (workspace_id, account_id, type, amount_cents, occurred_on, description,
				                          created_by, deleted_at)
				values (:w, :a, :t, :c, current_date, 'lançamento de teste', :u,
				        case when :d then now() else null end)
				returning id""")
				.param("w", workspaceId).param("a", accountId)
				.param("t", amountCents > 0 ? "INCOME" : "EXPENSE").param("c", amountCents)
				.param("u", userId).param("d", deleted).query(UUID.class).single();
	}

	/** Transferência: duas linhas com o mesmo grupo, saída negativa e entrada positiva. */
	public void insertTransfer(UUID workspaceId, UUID fromAccount, UUID toAccount, UUID userId, long cents) {
		UUID group = UUID.randomUUID();
		for (Object[] leg : new Object[][] { { fromAccount, -cents }, { toAccount, cents } }) {
			owner.client().sql("""
					insert into transactions (workspace_id, account_id, type, amount_cents, occurred_on, description,
					                          transfer_group_id, created_by)
					values (:w, :a, 'TRANSFER', :c, current_date, 'transferência de teste', :g, :u)""")
					.param("w", workspaceId).param("a", leg[0]).param("c", leg[1]).param("g", group)
					.param("u", userId).update();
		}
	}

}
