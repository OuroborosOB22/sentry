package org.upgradeguard.sentry.app;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.upgradeguard.sentry.app.jobs.JobService;
import org.upgradeguard.sentry.app.jobs.JobWorker;
import org.upgradeguard.sentry.app.provider.ProviderRegistry;
import org.upgradeguard.sentry.app.store.ProjectRepository;
import org.upgradeguard.sentry.app.store.Rows;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:insight;DB_CLOSE_DELAY=-1", "sentry.data-dir=target/insight-test-data",
        "sentry.explanation.api-key-environment-variable=HOME"})
@AutoConfigureMockMvc
class InsightApiTest {
    static HttpServer llm;
    static final AtomicInteger llmCalls = new AtomicInteger();
    static volatile int llmStatus = 200;

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ProjectRepository projects;
    @Autowired
    JobService service;
    @Autowired
    JobWorker worker;
    @Autowired
    ProviderRegistry registry;

    @BeforeAll
    static void startFakeLanguageModel() throws Exception {
        llm = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        llm.createContext("/v1/chat/completions", exchange -> {
            llmCalls.incrementAndGet();
            String answer = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"summary\\\": \\\"jinja2 changed and tests failed.\\\", \\\"annotations\\\": [{\\\"node_id\\\": \\\"dep:jinja2\\\", \\\"text\\\": \\\"changed\\\"}]}\"}}]}";
            byte[] reply = answer.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(llmStatus, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        llm.start();
    }

    @AfterAll
    static void stopFakeLanguageModel() {
        llm.stop(0);
    }

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM explanations");
        jdbc.update("DELETE FROM settings");
        jdbc.update("DELETE FROM findings");
        jdbc.update("DELETE FROM jobs");
        jdbc.update("DELETE FROM snapshots");
        jdbc.update("DELETE FROM projects");
        llmCalls.set(0);
        llmStatus = 200;
        Path folder = Files.createDirectories(Path.of("target/insight-roots/demo"));
        Files.writeString(folder.resolve("pom.xml"), "<project/>");
        Files.writeString(folder.resolve(".env"), "SECRET=hunter2");
        Files.writeString(folder.resolve("App.java"), "class App {}");
        projects.insert(new Rows.Project("demo", "Demo", folder.toAbsolutePath().toString(), "MAVEN", Instant.now()));
        registry.register(new FakeProvider());
        worker.setPollInterval(Duration.ofMillis(20));
    }

    private String finishedJob() {
        String id = service.submit("demo", new JobService.Submission("REHEARSE_UPGRADE", "org.demo:lib", "1.0.0", "2.0.0", null, "FAKE"));
        worker.runOne();
        return id;
    }

    private void useFakeModel() throws Exception {
        mvc.perform(put("/api/settings").contentType(MediaType.APPLICATION_JSON).content("{\"explanationProvider\":\"OPENAI_COMPATIBLE\",\"explanationModel\":\"test-model\","
                + "\"explanationBaseUrl\":\"http://127.0.0.1:" + llm.getAddress().getPort() + "/v1\"}")).andExpect(status().isOk());
    }

    @Test
    void theDiagramEndpointReturnsDataAndSafeMermaidText() throws Exception {
        String id = finishedJob();
        mvc.perform(get("/api/jobs/" + id + "/diagram")).andExpect(status().isOk()).andExpect(jsonPath("$.mermaid").value(containsString("flowchart TD")))
                .andExpect(jsonPath("$.diagram.nodes[0].id").value("project")).andExpect(jsonPath("$.mode").value("DIFF"));
        mvc.perform(get("/api/jobs/" + id + "/diagram?mode=BEFORE")).andExpect(jsonPath("$.mode").value("BEFORE"));
        mvc.perform(get("/api/jobs/" + id + "/diagram?mode=SIDEWAYS")).andExpect(status().isBadRequest());
    }

    @Test
    void withoutAConfiguredModelTheExplanationIsDisabledAndTheDiagramStillWorks() throws Exception {
        String id = finishedJob();
        mvc.perform(post("/api/jobs/" + id + "/explain")).andExpect(jsonPath("$.status").value("DISABLED")).andExpect(jsonPath("$.summary").value(""));
        mvc.perform(get("/api/jobs/" + id + "/diagram")).andExpect(status().isOk());
        org.junit.jupiter.api.Assertions.assertEquals(0, llmCalls.get());
    }

    @Test
    void aConfiguredModelExplainsOnceAndTheAnswerIsCachedAndLabelled() throws Exception {
        String id = finishedJob();
        useFakeModel();
        mvc.perform(post("/api/jobs/" + id + "/explain")).andExpect(jsonPath("$.status").value("OK")).andExpect(jsonPath("$.cached").value(false))
                .andExpect(jsonPath("$.labelled").value(containsString("never changes the verdict"))).andExpect(jsonPath("$.annotations[0].node_id").value("dep:jinja2"));
        mvc.perform(post("/api/jobs/" + id + "/explain")).andExpect(jsonPath("$.cached").value(true));
        org.junit.jupiter.api.Assertions.assertEquals(1, llmCalls.get());
    }

    @Test
    void aFailingModelIsDisclosedAndNeverCached() throws Exception {
        String id = finishedJob();
        useFakeModel();
        llmStatus = 500;
        mvc.perform(post("/api/jobs/" + id + "/explain")).andExpect(jsonPath("$.status").value("UNAVAILABLE"));
        llmStatus = 200;
        mvc.perform(post("/api/jobs/" + id + "/explain")).andExpect(jsonPath("$.status").value("OK"));
    }

    @Test
    void theMarkdownExportHasTheVerdictTheDiagramAndTheThreeCoverageLists() throws Exception {
        String id = finishedJob();
        String markdown = mvc.perform(get("/api/jobs/" + id + "/export.md")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        for (String part : new String[] {"Verdict: **REJECT**", "```mermaid", "flowchart TD", "## Checked", "## Not checked", "## Unavailable", "## Limits of the diagram"}) {
            org.junit.jupiter.api.Assertions.assertTrue(markdown.contains(part), part);
        }
        org.junit.jupiter.api.Assertions.assertFalse(markdown.contains("## Explanation"));
        useFakeModel();
        mvc.perform(get("/api/jobs/" + id + "/export.md?explanation=true")).andExpect(content().string(containsString("## Explanation (OPENAI_COMPATIBLE, test-model)")));
    }

    @Test
    void settingsShowWhetherAKeyExistsButNeverItsValue() throws Exception {
        String home = System.getenv("HOME");
        String text = mvc.perform(get("/api/settings")).andExpect(status().isOk()).andExpect(jsonPath("$.explanation.apiKeyPresent").value(true))
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertFalse(text.contains(home));
        mvc.perform(get("/api/settings")).andExpect(jsonPath("$.execution.providers").isArray());
    }

    @Test
    void settingsRefuseUnsafeValues() throws Exception {
        for (String body : new String[] {"{\"explanationBaseUrl\":\"file:///etc/passwd\"}", "{\"explanationBaseUrl\":\"http://user:pass@host/v1\"}", "{\"explanationBaseUrl\":\"http://h/v1?key=1\"}",
                "{\"explanationBaseUrl\":\"ftp://h/\"}", "{\"explanationProvider\":\"MAGIC\"}", "{\"defaultExecution\":\"MARS\"}", "{\"explanationModel\":\"bad model;rm\"}"}) {
            mvc.perform(put("/api/settings").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
    }

    @Test
    void aProviderConnectionTestSaysWhatItDoesAndDoesNotProve() throws Exception {
        mvc.perform(post("/api/settings/test/AZURE_VM")).andExpect(status().isOk()).andExpect(jsonPath("$.usable").value(false));
        mvc.perform(post("/api/settings/test/NOWHERE")).andExpect(status().isNotFound());
    }

    @Test
    void theTransferPreviewListsExactlyWhatWouldBeSentAndNeverTheSecrets() throws Exception {
        String text = mvc.perform(get("/api/projects/demo/transfer-preview")).andExpect(status().isOk()).andExpect(jsonPath("$.files").value(2))
                .andExpect(jsonPath("$.never_sent").isArray()).andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertTrue(text.contains("App.java") && text.contains("pom.xml"));
        org.junit.jupiter.api.Assertions.assertFalse(text.contains("hunter2"));
        mvc.perform(get("/api/projects/demo/transfer-preview")).andExpect(content().string(not(containsString("\".env\"]"))));
    }
}
