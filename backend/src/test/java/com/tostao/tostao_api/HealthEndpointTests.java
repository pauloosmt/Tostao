package com.tostao.tostao_api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class HealthEndpointTests {

	@Autowired
	MockMvc mvc;

	@Test
	void healthIsPublicAndHidesDetails() throws Exception {
		mvc.perform(get("/api/v1/health"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"))
				.andExpect(jsonPath("$.components").doesNotExist())
				.andExpect(header().string("Referrer-Policy", "no-referrer"))
				.andExpect(header().string("X-Frame-Options", "DENY"));
	}

	@Test
	void everythingElseIsClosed() throws Exception {
		mvc.perform(get("/api/v1/workspaces")).andExpect(status().isForbidden());
	}

}
