package com.tostao.tostao_api.membership;

import static com.tostao.tostao_api.membership.MembershipScenario.listMembers;
import static com.tostao.tostao_api.membership.MembershipScenario.membersUrl;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

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

/** História 2: ver quem faz parte do workspace. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ListMembersTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private MembershipScenario scenario() {
		return MembershipScenario.create(new WorkspaceFixtures(owner, tokens));
	}

	private static String body(ResultActions result) throws Exception {
		return result.andReturn().getResponse().getContentAsString();
	}

	@Test
	void everyMemberSeesTheOwnerFirstAndThenTheOthersInOrderOfEntry() throws Exception {
		MembershipScenario s = scenario();

		for (Fixture who : new Fixture[] { s.owner(), s.editor(), s.viewer() }) {
			String json = body(listMembers(mvc, who, s.workspace()).andExpect(status().isOk()));

			assertThat(JsonPath.<List<String>>read(json, "$[*].userId")).containsExactly(s.owner().userId().toString(),
					s.editor().userId().toString(), s.viewer().userId().toString());
			assertThat(JsonPath.<List<String>>read(json, "$[*].role")).containsExactly("OWNER", "EDITOR", "VIEWER");
		}
	}

	@Test
	void eachMemberCarriesTheIdNameEmailRoleAndTheDateOfEntry() throws Exception {
		MembershipScenario s = scenario();

		String json = body(listMembers(mvc, s.viewer(), s.workspace()).andExpect(status().isOk()));

		List<Map<String, Object>> members = JsonPath.read(json, "$");
		assertThat(members).hasSize(3);
		for (Map<String, Object> member : members) {
			assertThat(member.keySet()).containsExactlyInAnyOrder("userId", "name", "email", "role", "joinedAt");
			assertThat((String) member.get("email")).contains("@");
			assertThat((String) member.get("name")).isNotBlank();
			assertThat((String) member.get("joinedAt")).isNotBlank();
		}
		String expectedEmail = owner.client().sql("select email from users where id = :id").param("id", s.editor().userId())
				.query(String.class).single();
		assertThat(JsonPath.<List<String>>read(json, "$[?(@.role=='EDITOR')].email")).containsExactly(expectedEmail);
	}

	@Test
	void theListNeverCarriesPasswordHashSessionVersionOrAnotherWorkspace() throws Exception {
		MembershipScenario s = scenario();
		MembershipScenario other = scenario();

		String json = body(listMembers(mvc, s.owner(), s.workspace()).andExpect(status().isOk()));

		assertThat(json).doesNotContainIgnoringCase("password").doesNotContainIgnoringCase("hash")
				.doesNotContainIgnoringCase("session");
		assertThat(JsonPath.<List<String>>read(json, "$[*].userId")).doesNotContain(other.owner().userId().toString(),
				other.editor().userId().toString(), other.viewer().userId().toString());
	}

	@Test
	void aNonMemberGetsTheSameNotFoundAsAMissingWorkspace() throws Exception {
		MembershipScenario s = scenario();

		String real = body(listMembers(mvc, s.outsider(), s.workspace()).andExpect(status().isNotFound()));
		String missing = body(listMembers(mvc, s.outsider(), UUID.randomUUID()).andExpect(status().isNotFound()));

		assertThat((String) JsonPath.read(real, "$.title")).isEqualTo(JsonPath.read(missing, "$.title"));
		assertThat((String) JsonPath.read(real, "$.detail")).isEqualTo(JsonPath.read(missing, "$.detail"));
	}

	@Test
	void withoutASessionTheAnswerIsUnauthorized() throws Exception {
		MembershipScenario s = scenario();

		mvc.perform(get(membersUrl(s.workspace()))).andExpect(status().isUnauthorized());
	}

	@Test
	void theResponseIsNeverCached() throws Exception {
		MembershipScenario s = scenario();

		listMembers(mvc, s.viewer(), s.workspace()).andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", containsString("no-store")));
	}

}
