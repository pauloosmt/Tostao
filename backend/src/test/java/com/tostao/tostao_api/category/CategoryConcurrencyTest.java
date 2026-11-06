package com.tostao.tostao_api.category;

import static com.tostao.tostao_api.support.AuthSupport.session;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

/** SC-009: o mesmo nome na mesma posição, ao mesmo tempo, aceita um e recusa os outros, nunca erro interno. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class CategoryConcurrencyTest {

	private static final int THREADS = 8;

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private WorkspaceFixtures fixtures() {
		return new WorkspaceFixtures(owner, tokens);
	}

	private List<Integer> postAllAtOnce(Fixture fx, String json) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(THREADS);
		try {
			List<Callable<Integer>> jobs = new ArrayList<>();
			for (int i = 0; i < THREADS; i++) {
				jobs.add(() -> mvc.perform(post("/api/v1/workspaces/" + fx.workspaceId() + "/categories")
						.cookie(session(fx.token())).contentType(MediaType.APPLICATION_JSON).content(json))
						.andReturn().getResponse().getStatus());
			}
			List<Integer> statuses = new ArrayList<>();
			for (Future<Integer> f : pool.invokeAll(jobs)) {
				statuses.add(f.get());
			}
			return statuses;
		}
		finally {
			pool.shutdownNow();
		}
	}

	@Test
	void eightSimultaneousRootsWithTheSameNameCreateExactlyOne() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();

		List<Integer> statuses = postAllAtOnce(fx, "{\"name\":\"Disputada\",\"kind\":\"EXPENSE\"}");

		assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1);
		assertThat(statuses.stream().filter(s -> s == 409).count()).isEqualTo(THREADS - 1);
		assertThat(owner.client().sql("select count(*) from categories where workspace_id = :w")
				.param("w", fx.workspaceId()).query(Long.class).single()).isEqualTo(1);
	}

	@Test
	void eightSimultaneousSubcategoriesWithTheSameNameCreateExactlyOne() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID parent = fixtures().insertCategory(fx.workspaceId(), null, "Pai", "EXPENSE", false);

		List<Integer> statuses = postAllAtOnce(fx, "{\"name\":\"Disputada\",\"parentId\":\"%s\"}".formatted(parent));

		assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1);
		assertThat(statuses.stream().filter(s -> s == 409).count()).isEqualTo(THREADS - 1);
	}

}
