package com.tostao.tostao_api.membership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;

import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.common.error.InsufficientRoleException;
import com.tostao.tostao_api.common.error.WorkspaceNotFoundException;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;
import com.tostao.tostao_api.workspace.WorkspaceScoped;

/**
 * O atributo {@code owner} do aspecto de autorização: só o OWNER passa; EDITOR e VIEWER recebem 403; quem não é membro
 * e o workspace inexistente recebem 404, iguais. Os outros atributos continuam como eram.
 */
@Import({ TestcontainersConfiguration.class, OwnerOnlyAspectTest.ProbeConfig.class })
@SpringBootTest
class OwnerOnlyAspectTest {

	/** Um serviço de teste só com as quatro combinações de anotação. */
	static class Probe {

		@WorkspaceScoped(owner = true)
		@Transactional
		public String ownerOnly(UUID workspaceId) {
			return "ok";
		}

		@WorkspaceScoped(write = true)
		@Transactional
		public String writers(UUID workspaceId) {
			return "ok";
		}

		@WorkspaceScoped
		@Transactional(readOnly = true)
		public String anyMember(UUID workspaceId) {
			return "ok";
		}

		@WorkspaceScoped(owner = true, write = true)
		@Transactional
		public String both(UUID workspaceId) {
			return "ok";
		}

	}

	@TestConfiguration(proxyBeanMethods = false)
	static class ProbeConfig {

		@Bean
		Probe probe() {
			return new Probe();
		}

	}

	@Autowired
	Probe probe;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
	}

	private static void actAs(Fixture who) {
		Jwt jwt = Jwt.withTokenValue("teste").header("alg", "none").subject(who.userId().toString()).build();
		SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
	}

	private record World(Fixture owner, Fixture editor, Fixture viewer, Fixture outsider) {
	}

	private World world() {
		WorkspaceFixtures f = new WorkspaceFixtures(owner, tokens);
		Fixture o = f.workspaceWithOwner();
		return new World(o, f.addMember(o.workspaceId(), "EDITOR"), f.addMember(o.workspaceId(), "VIEWER"), f.workspaceWithOwner());
	}

	@Test
	void onlyTheOwnerPassesAnOwnerOnlyMethod() {
		World w = world();

		actAs(w.owner());
		assertThat(probe.ownerOnly(w.owner().workspaceId())).isEqualTo("ok");
		for (Fixture who : new Fixture[] { w.editor(), w.viewer() }) {
			actAs(who);
			assertThatThrownBy(() -> probe.ownerOnly(w.owner().workspaceId())).isInstanceOf(InsufficientRoleException.class);
		}
	}

	@Test
	void aNonMemberAndAMissingWorkspaceBothGetNotFoundOnAnOwnerOnlyMethod() {
		World w = world();
		actAs(w.outsider());

		assertThatThrownBy(() -> probe.ownerOnly(w.owner().workspaceId())).isInstanceOf(WorkspaceNotFoundException.class);
		assertThatThrownBy(() -> probe.ownerOnly(UUID.randomUUID())).isInstanceOf(WorkspaceNotFoundException.class);
	}

	@Test
	void theOwnerAttributeIsIndependentOfTheWriteAttribute() {
		World w = world();

		// write = true sozinho continua deixando o editor passar
		actAs(w.editor());
		assertThat(probe.writers(w.owner().workspaceId())).isEqualTo("ok");
		// owner = true junto com write = true exige o dono
		assertThatThrownBy(() -> probe.both(w.owner().workspaceId())).isInstanceOf(InsufficientRoleException.class);
		actAs(w.owner());
		assertThat(probe.both(w.owner().workspaceId())).isEqualTo("ok");
	}

	@Test
	void theDefaultsKeepTheExistingBehaviour() {
		World w = world();

		// sem atributos, qualquer membro passa, inclusive o leitor
		for (Fixture who : new Fixture[] { w.owner(), w.editor(), w.viewer() }) {
			actAs(who);
			assertThat(probe.anyMember(w.owner().workspaceId())).isEqualTo("ok");
		}
		// write = true: o leitor continua recusado e o dono e o editor passam
		actAs(w.viewer());
		assertThatThrownBy(() -> probe.writers(w.owner().workspaceId())).isInstanceOf(InsufficientRoleException.class);
		actAs(w.owner());
		assertThat(probe.writers(w.owner().workspaceId())).isEqualTo("ok");
	}

}
