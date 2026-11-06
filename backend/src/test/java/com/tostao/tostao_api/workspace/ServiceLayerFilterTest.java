package com.tostao.tostao_api.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.common.error.WorkspaceNotFoundException;

/**
 * Camada de serviço, sozinha (SC-003): o serviço roda com a conexão do dono do schema, que ignora o
 * RLS por completo. Mesmo assim, o filtro explícito por workspace e por usuário isola os dados.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ServiceLayerFilterTest {

	@Autowired
	OwnerJdbc owner;

	private UUID newUser() {
		return owner.client()
				.sql("insert into users (email, password_hash, name) values (:e, 'x', 'T') returning id")
				.param("e", "svc-" + UUID.randomUUID() + "@example.com").query(UUID.class).single();
	}

	private UUID newWorkspace(UUID owningUser) {
		UUID ws = owner.client().sql("insert into workspaces (name) values ('W') returning id")
				.query(UUID.class).single();
		owner.client().sql("insert into workspace_members (workspace_id, user_id, role) values (:w, :u, 'OWNER')")
				.param("w", ws).param("u", owningUser).update();
		return ws;
	}

	@Test
	void serviceFilterAloneIsolatesWorkspacesWithRlsBypassed() {
		WorkspaceService service = new WorkspaceService(owner.client());
		UUID u1 = newUser();
		UUID u2 = newUser();
		UUID w1 = newWorkspace(u1);
		UUID w2 = newWorkspace(u2);

		assertThat(service.get(w1, u1).role()).isEqualTo("OWNER");
		assertThat(service.get(w2, u2).role()).isEqualTo("OWNER");

		assertThatThrownBy(() -> service.get(w2, u1)).isInstanceOf(WorkspaceNotFoundException.class);
		assertThatThrownBy(() -> service.get(w1, u2)).isInstanceOf(WorkspaceNotFoundException.class);
		assertThatThrownBy(() -> service.get(UUID.randomUUID(), u1)).isInstanceOf(WorkspaceNotFoundException.class);
	}

}
