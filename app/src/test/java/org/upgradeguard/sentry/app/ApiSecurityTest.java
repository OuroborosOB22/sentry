package org.upgradeguard.sentry.app;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.upgradeguard.sentry.app.jobs.JobWorker;
import org.upgradeguard.sentry.app.provider.ProviderRegistry;
import org.upgradeguard.sentry.app.store.ProjectRepository;
import org.upgradeguard.sentry.app.store.Rows;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:api;DB_CLOSE_DELAY=-1", "sentry.data-dir=target/api-test-data", "sentry.allowed-roots[0]=target/api-test-roots"})
@AutoConfigureMockMvc
class ApiSecurityTest {
    @Autowired
    MockMvc mvc;
    @Autowired
    ProjectRepository projects;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    JobWorker worker;
    @Autowired
    ProviderRegistry registry;

    FakeProvider fake = new FakeProvider();

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM findings");
        jdbc.update("DELETE FROM jobs");
        jdbc.update("DELETE FROM snapshots");
        jdbc.update("DELETE FROM projects");
        Files.createDirectories(Path.of("target/api-test-roots/good"));
        Files.writeString(Path.of("target/api-test-roots/good/pom.xml"), "<project/>");
        registry.register(fake);
        worker.setPollInterval(Duration.ofMillis(20));
        projects.insert(new Rows.Project("demo", "Demo", Path.of("target/api-test-roots/good").toAbsolutePath().toString(), "MAVEN", Instant.now()));
    }

    @Test
    void everyResponseCarriesSecurityHeaders() throws Exception {
        mvc.perform(get("/api/projects")).andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'none'")));
    }

    @Test
    void onlyThePagesMayEvaluateScripts() throws Exception {
        mvc.perform(get("/api/projects")).andExpect(header().string("Content-Security-Policy", not(containsString("unsafe-eval"))));
        mvc.perform(get("/login")).andExpect(header().string("Content-Security-Policy", allOf(containsString("unsafe-eval"), containsString("base-uri 'self'"))));
    }

    @Test
    void withoutTokensTheApiOnlyAnswersOnThisMachine() throws Exception {
        mvc.perform(get("/api/projects").with(request -> {
            request.setRemoteAddr("203.0.113.7");
            return request;
        })).andExpect(status().isForbidden());
        mvc.perform(get("/api/projects").header("Host", "evil.example").with(request -> {
            request.setServerName("evil.example");
            return request;
        })).andExpect(status().isForbidden());
        mvc.perform(post("/mcp").content("{}").contentType(MediaType.APPLICATION_JSON).with(request -> {
            request.setRemoteAddr("203.0.113.7");
            return request;
        })).andExpect(status().isForbidden());
    }

    @Test
    void projectsOutsideTheAllowedRootsCannotBeRegistered() throws Exception {
        Files.createDirectories(Path.of("target/outside-root"));
        Files.writeString(Path.of("target/outside-root/pom.xml"), "<project/>");
        for (String path : new String[] {"/etc", "target/outside-root", "target/api-test-roots/../outside-root", "/does/not/exist", ""}) {
            mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\",\"path\":\"" + path + "\"}"))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"<script>\",\"path\":\"target/api-test-roots/good\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aSymlinkInsideTheRootThatPointsOutsideIsRefused() throws Exception {
        Path link = Path.of("target/api-test-roots/sneaky");
        Files.deleteIfExists(link);
        Files.createSymbolicLink(link, Path.of("target/outside-root").toAbsolutePath());
        Files.createDirectories(Path.of("target/outside-root"));
        Files.writeString(Path.of("target/outside-root/pom.xml"), "<project/>");
        mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"link\",\"path\":\"target/api-test-roots/sneaky\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anAllowedFolderRegistersOnceAndKeepsItsEcosystem() throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Good one\",\"path\":\"target/api-test-roots/good\"}"))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.ecosystem").value("MAVEN"));
        }
        mvc.perform(get("/api/projects")).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value("demo"));
    }

    @Test
    void aJobGoesFromSubmissionToEvidenceThroughTheRestApi() throws Exception {
        String body = "{\"operation\":\"REHEARSE_UPGRADE\",\"packageName\":\"org.demo:lib\",\"fromVersion\":\"1.0.0\",\"toVersion\":\"2.0.0\",\"execution\":\"FAKE\"}";
        String response = mvc.perform(post("/api/projects/demo/jobs").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String jobId = response.replaceAll(".*\"jobId\":\"([^\"]+)\".*", "$1");
        mvc.perform(get("/api/jobs/" + jobId)).andExpect(jsonPath("$.status").value("QUEUED"));
        worker.runOne();
        mvc.perform(get("/api/jobs/" + jobId)).andExpect(jsonPath("$.status").value("SUCCEEDED")).andExpect(jsonPath("$.verdict").value("REJECT"))
                .andExpect(jsonPath("$.findings.length()").value(1));
        mvc.perform(get("/api/jobs/" + jobId + "/evidence")).andExpect(status().isOk()).andExpect(jsonPath("$.verdict.decision").value("REJECT"));
    }

    @Test
    void badSubmissionsAreRejectedWithAMessageNotAStackTrace() throws Exception {
        String body = "{\"operation\":\"REHEARSE_UPGRADE\",\"packageName\":\"x;y\",\"fromVersion\":\"1\",\"toVersion\":\"2\"}";
        mvc.perform(post("/api/projects/demo/jobs").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(content().string(not(containsString("Exception")))).andExpect(content().string(not(containsString("at org."))));
        mvc.perform(post("/api/projects/demo/jobs").contentType(MediaType.APPLICATION_JSON).content("not json")).andExpect(status().is4xxClientError());
        mvc.perform(get("/api/jobs/nope")).andExpect(status().isNotFound());
        mvc.perform(get("/api/projects/nope")).andExpect(status().isNotFound());
    }

    @Test
    void mcpAndRestUseTheSameServices() throws Exception {
        String init = mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.serverInfo.name").value("sentry")).andReturn().getResponse().getContentAsString();
        assertTrue(init.contains("protocolVersion"));
        mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")).andExpect(status().isAccepted());
        mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}"))
                .andExpect(jsonPath("$.result.tools.length()").value(6));
        String submitted = mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"submit_job\","
                        + "\"arguments\":{\"project_id\":\"demo\",\"operation\":\"REHEARSE_UPGRADE\",\"package\":\"org.demo:lib\",\"from\":\"1.0.0\",\"to\":\"2.0.0\",\"execution\":\"FAKE\"}}}"))
                .andExpect(jsonPath("$.result.isError").value(false)).andReturn().getResponse().getContentAsString();
        String jobId = org.upgradeguard.sentry.contract.Json.parse(org.upgradeguard.sentry.contract.Json.parse(submitted).path("result").path("content").get(0).path("text").asText())
                .path("job_id").asText();
        worker.runOne();
        mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"job_report\",\"arguments\":{\"job_id\":\"" + jobId + "\"}}}"))
                .andExpect(jsonPath("$.result.isError").value(false)).andExpect(jsonPath("$.result.content[0].text").value(containsString("REJECT")));
        mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"run_shell\",\"arguments\":{}}}"))
                .andExpect(jsonPath("$.result.isError").value(true));
        mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content("{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"bogus\"}")).andExpect(jsonPath("$.error.code").value(-32601));
        mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content("{broken")).andExpect(jsonPath("$.error.code").value(-32700));
        assertEquals(6, 6);
    }

    @Test
    void aWebsiteInAnotherTabCannotDriveTheLocalHub() throws Exception {
        String body = "{\"name\":\"x\",\"path\":\"target/api-test-roots/good\"}";
        mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content(body).header("Origin", "http://evil.example")).andExpect(status().isForbidden());
        mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content(body).header("Origin", "http://localhost:9999")).andExpect(status().isForbidden());
        mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content(body).header("Sec-Fetch-Site", "cross-site")).andExpect(status().isForbidden());
        mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content(body).header("Sec-Fetch-Site", "same-site")).andExpect(status().isForbidden());
        mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content(body).header("Origin", "http://localhost").header("Sec-Fetch-Site", "same-origin"))
                .andExpect(status().isCreated());
    }

    @Test
    void simpleRequestsThatSkipThePreflightAreRefused() throws Exception {
        String call = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";
        mvc.perform(post("/mcp").contentType(MediaType.TEXT_PLAIN).content(call)).andExpect(status().isUnsupportedMediaType());
        mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_FORM_URLENCODED).content("a=b")).andExpect(status().isUnsupportedMediaType());
        mvc.perform(post("/api/projects/demo/jobs").contentType(MediaType.TEXT_PLAIN).content("{}")).andExpect(status().isUnsupportedMediaType());
        mvc.perform(post("/mcp").content(call)).andExpect(status().isUnsupportedMediaType());
        mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content(call)).andExpect(status().isOk());
    }
}
