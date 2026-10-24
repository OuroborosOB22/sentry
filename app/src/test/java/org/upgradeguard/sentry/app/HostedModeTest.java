package org.upgradeguard.sentry.app;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.upgradeguard.sentry.app.provider.ProviderRegistry;
import org.upgradeguard.sentry.app.store.ProjectRepository;
import org.upgradeguard.sentry.app.store.Rows;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:hosted;DB_CLOSE_DELAY=-1", "sentry.data-dir=target/hosted-test-data",
        "sentry.auth.tokens[0].token=scoped-token-for-demo", "sentry.auth.tokens[0].projects[0]=demo",
        "sentry.auth.tokens[1].token=admin-token-for-everything"})
@AutoConfigureMockMvc
class HostedModeTest {
    @Autowired
    MockMvc mvc;
    @Autowired
    ProjectRepository projects;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ProviderRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM findings");
        jdbc.update("DELETE FROM jobs");
        jdbc.update("DELETE FROM snapshots");
        jdbc.update("DELETE FROM projects");
        Path folder = Files.createDirectories(Path.of("target/hosted-roots/demo"));
        Files.writeString(folder.resolve("pom.xml"), "<project/>");
        projects.insert(new Rows.Project("demo", "Demo", folder.toAbsolutePath().toString(), "MAVEN", Instant.now()));
        projects.insert(new Rows.Project("secret", "Secret", folder.toAbsolutePath().toString() + "-2", "MAVEN", Instant.now()));
        registry.register(new FakeProvider());
    }

    @Test
    void theApiNeedsAValidTokenOnEveryEndpoint() throws Exception {
        for (String path : new String[] {"/api/projects", "/api/projects/demo", "/api/jobs/job-x", "/api/jobs/job-x/evidence"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).header("Authorization", "Bearer wrong")).andExpect(status().isUnauthorized());
            mvc.perform(get(path).header("Authorization", "Basic admin-token-for-everything")).andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/projects/demo/jobs").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
    }

    @Test
    void aScopedTokenSeesAndUsesOnlyItsOwnProjects() throws Exception {
        String scoped = "Bearer scoped-token-for-demo";
        mvc.perform(get("/api/projects").header("Authorization", scoped)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value("demo"));
        mvc.perform(get("/api/projects/demo").header("Authorization", scoped)).andExpect(status().isOk());
        mvc.perform(get("/api/projects/secret").header("Authorization", scoped)).andExpect(status().isForbidden());
        mvc.perform(get("/api/projects/secret/snapshots").header("Authorization", scoped)).andExpect(status().isForbidden());
        mvc.perform(post("/api/projects/secret/jobs").header("Authorization", scoped).contentType(MediaType.APPLICATION_JSON)
                .content("{\"operation\":\"SNAPSHOT\",\"execution\":\"FAKE\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/projects").header("Authorization", scoped).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\",\"path\":\"/tmp\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/projects").header("Authorization", "Bearer admin-token-for-everything")).andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void mcpHonoursTheSameProjectScopes() throws Exception {
        String call = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"submit_job\",\"arguments\":{\"project_id\":\"secret\",\"operation\":\"SNAPSHOT\",\"execution\":\"FAKE\"}}}";
        mvc.perform(post("/mcp").header("Authorization", "Bearer scoped-token-for-demo").contentType(MediaType.APPLICATION_JSON).content(call))
                .andExpect(jsonPath("$.result.isError").value(true)).andExpect(jsonPath("$.result.content[0].text").value(containsString("cannot use project")));
        mvc.perform(post("/mcp").header("Authorization", "Bearer scoped-token-for-demo").contentType(MediaType.APPLICATION_JSON)
                .content("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"list_projects\",\"arguments\":{}}}"))
                .andExpect(jsonPath("$.result.content[0].text").value(containsString("demo")));
    }

    @Test
    void runningProjectCodeNextToTheHubIsRefusedWhileTokensAreConfigured() throws Exception {
        mvc.perform(post("/api/projects/demo/jobs").header("Authorization", "Bearer admin-token-for-everything").contentType(MediaType.APPLICATION_JSON)
                .content("{\"operation\":\"REHEARSE_UPGRADE\",\"packageName\":\"a:b\",\"fromVersion\":\"1\",\"toVersion\":\"2\",\"execution\":\"LOCAL\"}"))
                .andExpect(status().isBadRequest()).andExpect(content().string(containsString("switched off while access tokens are configured")));
        mvc.perform(post("/api/projects/demo/jobs").header("Authorization", "Bearer admin-token-for-everything").contentType(MediaType.APPLICATION_JSON)
                .content("{\"operation\":\"SNAPSHOT\",\"execution\":\"FAKE\"}")).andExpect(status().isAccepted());
    }

    @Test
    void theBrowserLoginNeedsTheCsrfTokenAndAValidAccessToken() throws Exception {
        mvc.perform(get("/")).andExpect(status().isFound()).andExpect(redirectedUrl("/login"));
        MockHttpSession session = new MockHttpSession();
        String form = mvc.perform(get("/login").session(session)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String csrf = form.replaceAll(".*name=\"csrf\" value=\"([^\"]+)\".*", "$1");
        mvc.perform(post("/login").session(session).param("token", "admin-token-for-everything").param("csrf", "wrong")).andExpect(status().isForbidden());
        mvc.perform(post("/login").session(session).param("token", "nope").param("csrf", csrf)).andExpect(redirectedUrl("/login?failed"));
        mvc.perform(post("/login").session(session).param("token", "admin-token-for-everything").param("csrf", csrf)).andExpect(redirectedUrl("/"));
        mvc.perform(post("/login").param("token", "admin-token-for-everything").param("csrf", csrf)).andExpect(status().isForbidden());
    }
}
