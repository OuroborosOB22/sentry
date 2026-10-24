package org.upgradeguard.sentry.app.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.upgradeguard.sentry.app.config.SentryProperties;
import org.upgradeguard.sentry.contract.Json;
import org.upgradeguard.sentry.contract.SchemaValidator;

class BreakCastClientTest {
    static final String VALID = "{\"schema_version\":\"1.0.0\",\"status\":\"OK\",\"model_version\":\"fixture-1\",\"feature_schema_version\":\"1.0.0\",\"risk_score\":0.8,"
            + "\"calibrated_probability\":null,\"supported_scope\":\"fixture\",\"explanations\":[],\"timing_ms\":{\"inference\":1.5}}";
    static HttpServer server;
    static final AtomicReference<String> received = new AtomicReference<>();
    static volatile String body = VALID;
    static volatile int status = 200;
    static volatile int delayMillis = 0;
    static volatile String redirectTo = null;

    @BeforeAll
    static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/predict", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            if (redirectTo != null) {
                exchange.getResponseHeaders().add("Location", redirectTo);
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
                return;
            }
            byte[] reply = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        server.start();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    @BeforeEach
    void reset() {
        body = VALID;
        status = 200;
        delayMillis = 0;
        redirectTo = null;
        received.set(null);
    }

    private static BreakCastClient client(boolean enabled, int port, Duration timeout) {
        return new BreakCastClient(new SentryProperties(null, List.of(), "mvn", null, null, "python3", 2, null, null, "LOCAL", false, new SentryProperties.Auth(List.of()),
                new SentryProperties.Azure(false, null, 22, null, null, null, "ssh", "scp", "sentry-worker", 2), new SentryProperties.Explanation("DISABLED", null, null, null,
                Duration.ofSeconds(30), 800, false), new SentryProperties.Risk(enabled, "http://127.0.0.1:" + port, timeout)));
    }

    private static BreakCastClient working() {
        return client(true, server.getAddress().getPort(), Duration.ofSeconds(2));
    }

    @Test
    void sendsAContractValidRequestAndReturnsTheAdvisoryAnswer() {
        JsonNode answer = working().predict("demo", "com.google.guava:guava", "31.1-jre", "33.0.0-jre");
        assertEquals("OK", answer.path("status").asText());
        assertEquals(0.8, answer.path("risk_score").asDouble());
        assertTrue(answer.path("advisory").asText().contains("never changes a verdict"));
        JsonNode sent = Json.parse(received.get());
        assertTrue(new SchemaValidator().problems("prediction_request", sent).isEmpty());
        assertEquals("major", sent.path("features").path("change_kind").asText());
        assertEquals(2, sent.path("features").path("major_delta").asInt());
    }

    @Test
    void sendsNoSourceCodeOrPathsOnlyVersionFacts() {
        working().predict("demo", "com.google.guava:guava", "31.1-jre", "33.0.0-jre");
        assertTrue(!received.get().contains("/Users") && !received.get().contains(".java"));
    }

    @Test
    void whenDisabledItSaysSoAndCallsNothing() {
        JsonNode answer = client(false, server.getAddress().getPort(), Duration.ofSeconds(1)).predict("demo", "g:a", "1.0.0", "2.0.0");
        assertEquals("RISK_DISABLED", answer.path("status").asText());
        assertEquals(null, received.get());
    }

    @Test
    void anUnreachableServiceGivesAStatusNotAnException() {
        JsonNode answer = client(true, 1, Duration.ofSeconds(1)).predict("demo", "g:a", "1.0.0", "2.0.0");
        assertEquals("RISK_UNREACHABLE", answer.path("status").asText());
    }

    @Test
    void aSlowServiceTimesOut() {
        delayMillis = 1500;
        JsonNode answer = client(true, server.getAddress().getPort(), Duration.ofMillis(300)).predict("demo", "g:a", "1.0.0", "2.0.0");
        assertEquals("RISK_UNREACHABLE", answer.path("status").asText());
    }

    @Test
    void anAnswerThatBreaksTheContractIsRefused() {
        body = "{\"status\":\"OK\",\"risk_score\":5}";
        assertEquals("RISK_BAD_RESPONSE", working().predict("demo", "g:a", "1.0.0", "2.0.0").path("status").asText());
    }

    @Test
    void notJsonIsRefused() {
        body = "<html>nope</html>";
        assertEquals("RISK_UNREACHABLE", working().predict("demo", "g:a", "1.0.0", "2.0.0").path("status").asText());
    }

    @Test
    void anOversizedAnswerIsRefused() {
        body = "{\"padding\": \"" + "x".repeat(70_000) + "\"}";
        assertEquals("RISK_BAD_RESPONSE", working().predict("demo", "g:a", "1.0.0", "2.0.0").path("status").asText());
    }

    @Test
    void redirectsAreNotFollowed() {
        redirectTo = "http://127.0.0.1:1/internal";
        assertEquals("RISK_BAD_RESPONSE", working().predict("demo", "g:a", "1.0.0", "2.0.0").path("status").asText());
    }

    @Test
    void aServiceThatIsNotReadyIsReportedNotInvented() {
        status = 503;
        body = "{\"schema_version\":\"1.0.0\",\"status\":\"MODEL_UNAVAILABLE\",\"model_version\":null,\"feature_schema_version\":\"1.0.0\",\"risk_score\":null,"
                + "\"calibrated_probability\":null,\"supported_scope\":\"maven only\",\"explanations\":[],\"timing_ms\":{\"inference\":0}}";
        JsonNode answer = working().predict("demo", "g:a", "1.0.0", "2.0.0");
        assertEquals("MODEL_UNAVAILABLE", answer.path("status").asText());
        assertTrue(answer.path("risk_score").isNull());
    }
}
