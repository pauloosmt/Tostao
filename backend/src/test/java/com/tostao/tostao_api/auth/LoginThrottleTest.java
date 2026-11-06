package com.tostao.tostao_api.auth;

import static com.tostao.tostao_api.support.AuthSupport.PASSWORD;
import static com.tostao.tostao_api.support.AuthSupport.login;
import static com.tostao.tostao_api.support.AuthSupport.newEmail;
import static com.tostao.tostao_api.support.AuthSupport.registerAndGetToken;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class LoginThrottleTest {

	static final String WRONG = "Errada-123-abc";

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;

	private int fail(String email) throws Exception {
		return login(mvc, email, WRONG).getResponse().getStatus();
	}

	private int failedCount(String email) {
		return owner.client().sql("select failed_count from login_attempts where email = :e")
				.param("e", email.strip().toLowerCase()).query(Integer.class).optional().orElse(0);
	}

	private boolean hasRow(String email) {
		return owner.client().sql("select count(*) from login_attempts where email = :e")
				.param("e", email.strip().toLowerCase()).query(Long.class).single() > 0;
	}

	@Test
	void fiveFailuresLockEvenTheCorrectPasswordWithRetryAfter() throws Exception {
		String email = newEmail();
		registerAndGetToken(mvc, email);

		for (int i = 0; i < 5; i++) {
			assertThat(fail(email)).as("falha %d", i + 1).isEqualTo(401);
		}
		MvcResult locked = login(mvc, email, PASSWORD);

		assertThat(locked.getResponse().getStatus()).isEqualTo(429);
		assertThat(locked.getResponse().getContentType()).contains("application/problem+json");
		long retryAfter = Long.parseLong(locked.getResponse().getHeader(HttpHeaders.RETRY_AFTER));
		assertThat(retryAfter).isBetween(1L, 15 * 60L);
		assertThat(locked.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
	}

	@Test
	void unknownEmailBehavesExactlyLikeAnExistingOne() throws Exception {
		String ghost = newEmail();

		for (int i = 0; i < 5; i++) {
			assertThat(fail(ghost)).isEqualTo(401);
		}

		assertThat(login(mvc, ghost, PASSWORD).getResponse().getStatus()).isEqualTo(429);
	}

	@Test
	void stateLivesInTheDatabaseNotInProcessMemory() throws Exception {
		String email = newEmail();
		registerAndGetToken(mvc, email);
		for (int i = 0; i < 5; i++) {
			fail(email);
		}

		var row = owner.client().sql("""
				select failed_count, locked_until > now() from login_attempts where email = :e""")
				.param("e", email).query((rs, i) -> new Object[] { rs.getInt(1), rs.getBoolean(2) }).single();

		assertThat(row[0]).isEqualTo(5);
		assertThat(row[1]).isEqualTo(true);
	}

	@Test
	void successBelowTheLimitResetsTheCounter() throws Exception {
		String email = newEmail();
		registerAndGetToken(mvc, email);
		for (int i = 0; i < 3; i++) {
			fail(email);
		}
		assertThat(failedCount(email)).isEqualTo(3);

		assertThat(login(mvc, email, PASSWORD).getResponse().getStatus()).isEqualTo(200);

		assertThat(hasRow(email)).isFalse();
		// a contagem recomeçou: mais quatro falhas ainda não bloqueiam
		for (int i = 0; i < 4; i++) {
			assertThat(fail(email)).isEqualTo(401);
		}
		assertThat(login(mvc, email, PASSWORD).getResponse().getStatus()).isEqualTo(200);
	}

	@Test
	void afterTheLockExpiresTheCorrectPasswordWorksAgain() throws Exception {
		String email = newEmail();
		registerAndGetToken(mvc, email);
		for (int i = 0; i < 5; i++) {
			fail(email);
		}
		owner.client().sql("update login_attempts set locked_until = now() - interval '1 minute' where email = :e")
				.param("e", email).update();

		assertThat(login(mvc, email, PASSWORD).getResponse().getStatus()).isEqualTo(200);
		assertThat(hasRow(email)).isFalse();
	}

	@Test
	void aFailureAfterAnExpiredLockRestartsTheCountAtOne() throws Exception {
		String email = newEmail();
		registerAndGetToken(mvc, email);
		for (int i = 0; i < 5; i++) {
			fail(email);
		}
		owner.client().sql("update login_attempts set locked_until = now() - interval '1 minute' where email = :e")
				.param("e", email).update();

		assertThat(fail(email)).isEqualTo(401);

		assertThat(failedCount(email)).isEqualTo(1);
		boolean unlocked = owner.client().sql("select locked_until is null from login_attempts where email = :e")
				.param("e", email).query(Boolean.class).single();
		assertThat(unlocked).isTrue();
	}

	@Test
	void failuresCountTogetherRegardlessOfEmailCaseAndSpaces() throws Exception {
		String email = newEmail();
		registerAndGetToken(mvc, email);

		login(mvc, email.toUpperCase(), WRONG);
		login(mvc, " " + email + " ", WRONG);
		login(mvc, email, WRONG);
		login(mvc, email.toUpperCase(), WRONG);
		login(mvc, email, WRONG);

		assertThat(login(mvc, email, PASSWORD).getResponse().getStatus()).isEqualTo(429);
	}

	@Test
	void lockingOnePersonDoesNotAffectAnother() throws Exception {
		String victim = newEmail();
		String bystander = newEmail();
		registerAndGetToken(mvc, victim);
		registerAndGetToken(mvc, bystander);
		for (int i = 0; i < 5; i++) {
			fail(victim);
		}

		assertThat(login(mvc, victim, PASSWORD).getResponse().getStatus()).isEqualTo(429);
		assertThat(login(mvc, bystander, PASSWORD).getResponse().getStatus()).isEqualTo(200);
	}

	@Test
	void concurrentFailuresAreNeverUndercounted() throws Exception {
		String email = newEmail();
		registerAndGetToken(mvc, email);
		int attempts = 20;
		ExecutorService pool = Executors.newFixedThreadPool(8);
		try {
			List<Callable<Integer>> calls = new ArrayList<>();
			for (int i = 0; i < attempts; i++) {
				calls.add(() -> fail(email));
			}
			for (Future<Integer> f : pool.invokeAll(calls)) {
				assertThat(f.get()).isIn(401, 429);
			}
		}
		finally {
			pool.shutdownNow();
		}

		assertThat(failedCount(email)).isGreaterThanOrEqualTo(5);
		assertThat(login(mvc, email, PASSWORD).getResponse().getStatus()).isEqualTo(429);
	}

}
