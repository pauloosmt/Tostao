package com.tostao.tostao_api.category;

import static com.tostao.tostao_api.support.AuthSupport.session;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

/** SC-007: listar um workspace com 200 categorias responde em menos de 1 segundo. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class CategoryPerformanceTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	@Test
	void listsTwoHundredCategoriesInUnderASecond() throws Exception {
		Fixture fx = new WorkspaceFixtures(owner, tokens).workspaceWithOwner();
		owner.client().sql("""
				insert into categories (workspace_id, name, kind)
				select :w, 'Principal ' || g, 'EXPENSE' from generate_series(1, 20) g""")
				.param("w", fx.workspaceId()).update();
		owner.client().sql("""
				insert into categories (workspace_id, parent_id, name, kind)
				select :w, p.id, 'Sub ' || g, 'EXPENSE'
				from categories p, generate_series(1, 9) g
				where p.workspace_id = :w and p.parent_id is null""")
				.param("w", fx.workspaceId()).update();
		String url = "/api/v1/workspaces/" + fx.workspaceId() + "/categories";

		mvc.perform(get(url).cookie(session(fx.token()))).andExpect(status().isOk());
		long[] millis = new long[5];
		for (int i = 0; i < millis.length; i++) {
			long start = System.nanoTime();
			mvc.perform(get(url).cookie(session(fx.token()))).andExpect(status().isOk())
					.andExpect(jsonPath("$.length()").value(20))
					.andExpect(jsonPath("$[0].subcategories.length()").value(9));
			millis[i] = (System.nanoTime() - start) / 1_000_000;
		}
		Arrays.sort(millis);

		assertThat(millis[millis.length / 2]).as("mediana em ms").isLessThan(1000);
	}

}
