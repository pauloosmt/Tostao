package com.tostao.tostao_api.membership;

import static com.tostao.tostao_api.membership.MembershipScenario.accept;
import static com.tostao.tostao_api.membership.MembershipScenario.changeRole;
import static com.tostao.tostao_api.membership.MembershipScenario.invite;
import static com.tostao.tostao_api.membership.MembershipScenario.listMembers;
import static com.tostao.tostao_api.membership.MembershipScenario.removeMember;
import static com.tostao.tostao_api.membership.MembershipScenario.revokeInvitation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;
import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

/**
 * SC-004, SC-005 e SC-011 sob concorrência real: o código vale uma só vez mesmo com aceites simultâneos, o limite de 20
 * membros nunca é ultrapassado, convites simultâneos ao mesmo e-mail deixam um só pendente, e aceitar, revogar, remover e
 * mudar o papel ao mesmo tempo nunca deixam um estado inconsistente nem um erro interno.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class MembershipConcurrencyTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private WorkspaceFixtures fixtures() {
		return new WorkspaceFixtures(owner, tokens);
	}

	private record Answer(int status, String body) {
	}

	private static List<Answer> together(List<Callable<Answer>> actions) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(actions.size());
		CountDownLatch ready = new CountDownLatch(actions.size());
		CountDownLatch go = new CountDownLatch(1);
		List<Future<Answer>> futures = new ArrayList<>();
		for (var action : actions) {
			futures.add(pool.submit(() -> {
				ready.countDown();
				go.await();
				return action.call();
			}));
		}
		ready.await(30, TimeUnit.SECONDS);
		go.countDown();
		List<Answer> answers = new ArrayList<>();
		for (Future<Answer> future : futures) {
			answers.add(future.get(90, TimeUnit.SECONDS));
		}
		pool.shutdown();
		return answers;
	}

	private static Answer answer(ResultActions result) throws Exception {
		var response = result.andReturn().getResponse();
		return new Answer(response.getStatus(), response.getContentAsString());
	}

	private static String body(ResultActions result) throws Exception {
		return result.andReturn().getResponse().getContentAsString();
	}

	private static String sha256(String code) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8)));
	}

	private Fixture person() {
		UUID user = fixtures().newUser();
		return new Fixture(UUID.randomUUID(), user, fixtures().tokenOf(user));
	}

	private String emailOf(Fixture who) {
		return owner.client().sql("select email from users where id = :id").param("id", who.userId()).query(String.class).single();
	}

	private long members(UUID workspace) {
		return owner.client().sql("select count(*) from workspace_members where workspace_id = :w").param("w", workspace).query(Long.class).single();
	}

	/** O dono convida e devolve {código, id}. */
	private String[] invitation(MembershipScenario s, Fixture guest, String role) throws Exception {
		String created = body(invite(mvc, s.owner(), s.workspace(), "{\"email\":\"" + emailOf(guest) + "\",\"role\":\"" + role + "\"}")
				.andExpect(status().isCreated()));
		return new String[] { JsonPath.read(created, "$.code"), JsonPath.read(created, "$.id") };
	}

	// ---- o código vale uma só vez -----------------------------------------------------------------------------

	@Test
	void twentySimultaneousAcceptsOfTheSameCodeLetExactlyOneIn() throws Exception {
		MembershipScenario s = MembershipScenario.create(fixtures());
		Fixture guest = person();
		String code = invitation(s, guest, "EDITOR")[0];
		long before = members(s.workspace());
		List<Callable<Answer>> actions = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			actions.add(() -> answer(accept(mvc, guest, "{\"code\":\"" + code + "\"}")));
		}

		List<Answer> answers = together(actions);

		assertThat(answers.stream().filter(a -> a.status() == 200).count()).isEqualTo(1L);
		assertThat(answers.stream().filter(a -> a.status() == 404).count()).isEqualTo(19L);
		assertThat(answers).extracting(Answer::status).containsOnly(200, 404);
		assertThat(members(s.workspace())).isEqualTo(before + 1);
		assertThat(owner.client().sql("select count(*) from invitations where workspace_id = :w and accepted_at is not null").param("w", s.workspace())
				.query(Long.class).single()).isEqualTo(1L);
	}

	@Test
	void manyPeopleAcceptingAtTheSameTimeNeverPassTwentyMembers() throws Exception {
		MembershipScenario s = MembershipScenario.create(fixtures());
		for (int i = 0; i < 7; i++) {
			fixtures().addMember(s.workspace(), "VIEWER");
		}
		assertThat(members(s.workspace())).isEqualTo(10L);
		List<Callable<Answer>> actions = new ArrayList<>();
		for (int i = 0; i < 25; i++) {
			Fixture guest = person();
			String code = "codigo-" + UUID.randomUUID();
			owner.client().sql("""
					insert into invitations (workspace_id, email, role, code_hash, created_by, expires_at)
					values (:w, :e, 'VIEWER', :h, :u, now() + interval '7 days')""")
					.param("w", s.workspace()).param("e", emailOf(guest)).param("h", sha256(code)).param("u", s.owner().userId()).update();
			actions.add(() -> answer(accept(mvc, guest, "{\"code\":\"" + code + "\"}")));
		}

		List<Answer> answers = together(actions);

		assertThat(answers).extracting(Answer::status).containsOnly(200, 409);
		assertThat(answers.stream().filter(a -> a.status() == 200).count()).isEqualTo(10L);
		assertThat(members(s.workspace())).as("o limite de 20 nunca é ultrapassado").isEqualTo(20L);
	}

	// ---- convidar ---------------------------------------------------------------------------------------------

	@Test
	void tenSimultaneousInvitationsToTheSameEmailLeaveASinglePendingOne() throws Exception {
		MembershipScenario s = MembershipScenario.create(fixtures());
		List<Callable<Answer>> actions = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			actions.add(() -> answer(invite(mvc, s.owner(), s.workspace(), "{\"email\":\"mesmo@exemplo.com\",\"role\":\"EDITOR\"}")));
		}

		List<Answer> answers = together(actions);

		assertThat(answers).extracting(Answer::status).containsOnly(201);
		assertThat(owner.client().sql("select count(*) from invitations where workspace_id = :w and accepted_at is null and revoked_at is null")
				.param("w", s.workspace()).query(Long.class).single()).isEqualTo(1L);
		assertThat(owner.client().sql("select count(*) from invitations where workspace_id = :w and revoked_at is not null").param("w", s.workspace())
				.query(Long.class).single()).isEqualTo(9L);
	}

	@Test
	void simultaneousInvitationsNeverPassTwentyPending() throws Exception {
		MembershipScenario s = MembershipScenario.create(fixtures());
		List<Callable<Answer>> actions = new ArrayList<>();
		for (int i = 0; i < 25; i++) {
			String email = "pessoa" + i + "-" + UUID.randomUUID() + "@exemplo.com";
			actions.add(() -> answer(invite(mvc, s.owner(), s.workspace(), "{\"email\":\"" + email + "\",\"role\":\"VIEWER\"}")));
		}

		List<Answer> answers = together(actions);

		assertThat(answers).extracting(Answer::status).containsOnly(201, 409);
		assertThat(answers.stream().filter(a -> a.status() == 201).count()).isEqualTo(20L);
		assertThat(owner.client().sql("select count(*) from invitations where workspace_id = :w and accepted_at is null and revoked_at is null")
				.param("w", s.workspace()).query(Long.class).single()).isEqualTo(20L);
	}

	// ---- aceitar x revogar, remover x mudar o papel -----------------------------------------------------------

	@Test
	void acceptingAndRevokingTheSameInvitationNeverBothSucceed() throws Exception {
		MembershipScenario s = MembershipScenario.create(fixtures());

		for (int round = 0; round < 30; round++) {
			Fixture guest = person();
			String[] invitation = invitation(s, guest, "EDITOR");

			List<Answer> answers = together(List.of(
					() -> answer(accept(mvc, guest, "{\"code\":\"" + invitation[0] + "\"}")),
					() -> answer(revokeInvitation(mvc, s.owner(), s.workspace(), invitation[1]))));

			int accepted = answers.get(0).status();
			int revoked = answers.get(1).status();
			boolean isMember = MembershipScenario.roleOf(owner, s.workspace(), guest.userId()) != null;
			assertThat(accepted).as("rodada " + round).isIn(200, 404);
			assertThat(revoked).as("rodada " + round).isIn(204, 404);
			// nunca os dois dão certo, e o estado final bate com quem ganhou
			assertThat(accepted == 200 && revoked == 204).as("rodada " + round).isFalse();
			assertThat(isMember).as("rodada " + round).isEqualTo(accepted == 200);
			assertThat(accepted == 404 && revoked == 404).as("um dos dois sempre vence: rodada " + round).isFalse();
			// quem entrou sai, para o workspace nunca passar do limite de 20 membros ao longo das rodadas
			if (isMember) {
				removeMember(mvc, s.owner(), s.workspace(), guest.userId()).andExpect(status().isNoContent());
			}
		}
	}

	@Test
	void removingAndChangingTheRoleOfTheSameMemberAtTheSameTimeEndsConsistent() throws Exception {
		MembershipScenario s = MembershipScenario.create(fixtures());

		for (int round = 0; round < 30; round++) {
			Fixture member = fixtures().addMember(s.workspace(), "EDITOR");

			List<Answer> answers = together(List.of(
					() -> answer(removeMember(mvc, s.owner(), s.workspace(), member.userId())),
					() -> answer(changeRole(mvc, s.owner(), s.workspace(), member.userId(), "VIEWER", true))));

			assertThat(answers.get(0).status()).as("rodada " + round).isEqualTo(204);
			assertThat(answers.get(1).status()).as("rodada " + round).isIn(200, 404);
			assertThat(MembershipScenario.roleOf(owner, s.workspace(), member.userId())).as("rodada " + round).isNull();
		}
	}

	// ---- desempenho -------------------------------------------------------------------------------------------

	private interface Call {
		ResultActions run() throws Exception;
	}

	private long medianMillis(Call call, int expectedStatus) throws Exception {
		call.run().andExpect(status().is(expectedStatus));
		long[] millis = new long[5];
		for (int i = 0; i < millis.length; i++) {
			long start = System.nanoTime();
			call.run().andExpect(status().is(expectedStatus));
			millis[i] = (System.nanoTime() - start) / 1_000_000;
		}
		Arrays.sort(millis);
		return millis[millis.length / 2];
	}

	@Test
	void invitingAcceptingAndListingTheMembersStayUnderASecondWithTwentyMembers() throws Exception {
		MembershipScenario s = MembershipScenario.create(fixtures());
		for (int i = 0; i < 16; i++) {
			fixtures().addMember(s.workspace(), "VIEWER");
		}
		assertThat(members(s.workspace())).isEqualTo(19L);

		long invite = medianMillis(() -> invite(mvc, s.owner(), s.workspace(), "{\"email\":\"mesmo-" + UUID.randomUUID() + "@exemplo.com\",\"role\":\"VIEWER\"}"), 201);
		long list = medianMillis(() -> listMembers(mvc, s.viewer(), s.workspace()), 200);
		// aceitar: cada chamada usa um convite novo para uma pessoa nova, em workspaces diferentes para não passar de 20
		List<Long> times = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			MembershipScenario other = MembershipScenario.create(fixtures());
			Fixture guest = person();
			String code = invitation(other, guest, "EDITOR")[0];
			long start = System.nanoTime();
			accept(mvc, guest, "{\"code\":\"" + code + "\"}").andExpect(status().isOk());
			times.add((System.nanoTime() - start) / 1_000_000);
		}
		times.remove(0);
		long acceptMedian = times.stream().sorted().toList().get(times.size() / 2);
		System.out.printf("[SC-011 membros] medianas em ms: convidar=%d, aceitar=%d, listar membros=%d%n", invite, acceptMedian, list);

		assertThat(invite).as("convidar").isLessThan(1_000);
		assertThat(acceptMedian).as("aceitar").isLessThan(1_000);
		assertThat(list).as("listar membros").isLessThan(1_000);
	}

}
