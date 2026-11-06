package com.tostao.tostao_api.workspace;

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

/** O aspecto confere participação (404) e papel de escrita (403) antes de rodar o serviço. */
@Import({ TestcontainersConfiguration.class, WorkspaceRoleAspectTest.ProbeConfig.class })
@SpringBootTest
class WorkspaceRoleAspectTest {

	/** Serviço mínimo anotado, só para exercitar o aspecto. */
	public static class ProbeService {

		@WorkspaceScoped(write = true)
		@Transactional
		public String write(UUID workspaceId) {
			return "wrote";
		}

		@WorkspaceScoped
		@Transactional(readOnly = true)
		public String read(UUID workspaceId) {
			return "read";
		}

	}

	@TestConfiguration(proxyBeanMethods = false)
	static class ProbeConfig {

		@Bean
		ProbeService probeService() {
			return new ProbeService();
		}

	}

	@Autowired
	ProbeService probe;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	WorkspaceFixtures fixtures;

	private WorkspaceFixtures fixtures() {
		if (fixtures == null) {
			fixtures = new WorkspaceFixtures(owner, tokens);
		}
		return fixtures;
	}

	private static void signInAs(UUID userId) {
		Jwt jwt = Jwt.withTokenValue("t").header("alg", "HS256").subject(userId.toString()).build();
		SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
	}

	@AfterEach
	void clear() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void ownerAndEditorCanWriteAndRead() {
		Fixture ownerFx = fixtures().workspaceWithOwner();
		Fixture editor = fixtures().addMember(ownerFx.workspaceId(), "EDITOR");

		signInAs(ownerFx.userId());
		assertThat(probe.write(ownerFx.workspaceId())).isEqualTo("wrote");
		signInAs(editor.userId());
		assertThat(probe.write(ownerFx.workspaceId())).isEqualTo("wrote");
		assertThat(probe.read(ownerFx.workspaceId())).isEqualTo("read");
	}

	@Test
	void viewerCanReadButNotWrite() {
		Fixture ownerFx = fixtures().workspaceWithOwner();
		Fixture viewer = fixtures().addMember(ownerFx.workspaceId(), "VIEWER");
		signInAs(viewer.userId());

		assertThat(probe.read(ownerFx.workspaceId())).isEqualTo("read");
		assertThatThrownBy(() -> probe.write(ownerFx.workspaceId())).isInstanceOf(InsufficientRoleException.class);
	}

	@Test
	void nonMemberGetsNotFoundEvenForWrites() {
		Fixture ownerFx = fixtures().workspaceWithOwner();
		Fixture stranger = fixtures().workspaceWithOwner();
		signInAs(stranger.userId());

		assertThatThrownBy(() -> probe.write(ownerFx.workspaceId())).isInstanceOf(WorkspaceNotFoundException.class);
		assertThatThrownBy(() -> probe.read(ownerFx.workspaceId())).isInstanceOf(WorkspaceNotFoundException.class);
		assertThatThrownBy(() -> probe.write(UUID.randomUUID())).isInstanceOf(WorkspaceNotFoundException.class);
	}

}
