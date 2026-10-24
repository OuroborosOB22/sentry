package org.upgradeguard.sentry.app;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.upgradeguard.sentry.app.store.ProjectRepository;
import org.upgradeguard.sentry.app.store.Rows;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:riskapi;DB_CLOSE_DELAY=-1", "sentry.data-dir=target/risk-test-data", "sentry.risk.enabled=true"})
@AutoConfigureMockMvc
class RiskApiTest {
    static HttpServer service;

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ProjectRepository projects;

    @BeforeAll
    static void start() throws Exception {
        service = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        service.createContext("/v1/predict", exchange -> {
            byte[] reply = ("{\"schema_version\":\"1.0.0\",\"status\":\"OK\",\"model_version\":\"fixture-1\",\"feature_schema_version\":\"1.0.0\",\"risk_score\":0.42,"
                    + "\"calibrated_probability\":null,\"supported_scope\":\"fixture\",\"explanations\":[],\"timing_ms\":{\"inference\":1}}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        service.start();
    }

    @AfterAll
    static void stop() {
        service.stop(0);
    }

    @DynamicPropertySource
    static void address(DynamicPropertyRegistry registry) throws Exception {
        if (service == null) {
            start();
        }
        registry.add("sentry.risk.base-url", () -> "http://127.0.0.1:" + service.getAddress().getPort());
    }

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM projects");
        Path folder = Files.createDirectories(Path.of("target/risk-roots/demo"));
        Files.writeString(folder.resolve("pom.xml"), "<project/>");
        projects.insert(new Rows.Project("demo", "Demo", folder.toAbsolutePath().toString(), "MAVEN", Instant.now()));
    }

    private static String body(String pack, String from, String to) {
        return "{\"packageName\":\"" + pack + "\",\"fromVersion\":\"" + from + "\",\"toVersion\":\"" + to + "\"}";
    }

    @Test
    void returnsTheScoreAsAdviceOnly() throws Exception {
        mvc.perform(post("/api/projects/demo/risk").contentType(MediaType.APPLICATION_JSON).content(body("org.demo:lib", "1.0.0", "2.0.0")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OK")).andExpect(jsonPath("$.risk_score").value(0.42))
                .andExpect(jsonPath("$.advisory").exists());
    }

    @Test
    void refusesPackagesAndVersionsThatAreNotPlain() throws Exception {
        mvc.perform(post("/api/projects/demo/risk").contentType(MediaType.APPLICATION_JSON).content(body("not a package", "1.0.0", "2.0.0"))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/projects/demo/risk").contentType(MediaType.APPLICATION_JSON).content(body("org.demo:lib", "1.0.0;rm", "2.0.0"))).andExpect(status().isBadRequest());
    }

    @Test
    void unknownProjectIsNotFound() throws Exception {
        mvc.perform(post("/api/projects/missing/risk").contentType(MediaType.APPLICATION_JSON).content(body("org.demo:lib", "1.0.0", "2.0.0"))).andExpect(status().isNotFound());
    }

    @Test
    void withoutTheJsonContentTypeStateChangesAreRefused() throws Exception {
        mvc.perform(post("/api/projects/demo/risk").contentType(MediaType.TEXT_PLAIN).content(body("org.demo:lib", "1.0.0", "2.0.0"))).andExpect(status().is4xxClientError());
    }
}
