package org.upgradeguard.sentry.app.risk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;
import org.upgradeguard.sentry.app.config.SentryProperties;
import org.upgradeguard.sentry.contract.Json;
import org.upgradeguard.sentry.contract.SchemaValidator;

@Component
public class BreakCastClient implements RiskModelProvider {
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final SchemaValidator VALIDATOR = new SchemaValidator();

    private final SentryProperties.Risk settings;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();

    public BreakCastClient(SentryProperties properties) {
        this.settings = properties.risk();
    }

    @Override
    public boolean enabled() {
        return settings.enabled() && settings.baseUrl() != null && !settings.baseUrl().isBlank();
    }

    @Override
    public JsonNode predict(String projectId, String packageName, String fromVersion, String toVersion) {
        if (!enabled()) {
            return unavailable("RISK_DISABLED", "the risk service is not connected");
        }
        RiskFeatures.Result result = RiskFeatures.extract(fromVersion, toVersion);
        ObjectNode request = Json.object();
        request.put("schema_version", "1.0.0");
        request.put("task", "maven_upgrade_build_failure");
        request.put("ecosystem", "MAVEN");
        request.put("project_id", projectId);
        request.put("package", packageName);
        request.put("from_version", fromVersion);
        request.put("to_version", toVersion);
        request.put("feature_schema_version", RiskFeatures.SCHEMA_VERSION);
        ObjectNode features = request.putObject("features");
        result.features().forEach((name, value) -> {
            if (value == null) {
                features.putNull(name);
            } else if (value instanceof Integer number) {
                features.put(name, number);
            } else {
                features.put(name, value.toString());
            }
        });
        result.missing().forEach(request.putArray("missing_features")::add);
        List<String> invalid = VALIDATOR.problems("prediction_request", request);
        if (!invalid.isEmpty()) {
            return unavailable("RISK_BAD_REQUEST", invalid.get(0));
        }
        try {
            HttpRequest post = HttpRequest.newBuilder(URI.create(settings.baseUrl().replaceAll("/+$", "") + "/v1/predict")).timeout(settings.timeout())
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(Json.text(request))).build();
            HttpResponse<InputStream> reply = client.send(post, HttpResponse.BodyHandlers.ofInputStream());
            byte[] body;
            try (InputStream stream = reply.body()) {
                body = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
            }
            if (body.length > MAX_RESPONSE_BYTES) {
                return unavailable("RISK_BAD_RESPONSE", "the answer was too large");
            }
            JsonNode answer = Json.parse(new String(body, java.nio.charset.StandardCharsets.UTF_8));
            List<String> broken = VALIDATOR.problems("prediction_response", answer);
            if (!broken.isEmpty()) {
                return unavailable("RISK_BAD_RESPONSE", "the answer does not follow contract v1");
            }
            ObjectNode checked = ((ObjectNode) answer).deepCopy();
            checked.put("advisory", "This estimate never changes a verdict. A low score is not a safety claim.");
            return checked;
        } catch (IOException | IllegalArgumentException problem) {
            return unavailable("RISK_UNREACHABLE", "the risk service did not answer");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return unavailable("RISK_UNREACHABLE", "interrupted");
        }
    }

    private static JsonNode unavailable(String status, String reason) {
        ObjectNode node = Json.object();
        node.put("status", status);
        node.put("reason", reason);
        node.put("advisory", "Everything else works without the risk service.");
        return node;
    }
}
