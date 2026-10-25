package org.upgradeguard.sentry.maven;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.upgradeguard.sentry.contract.Json;

public final class OsvScanner {
    public interface Transport {
        String post(String url, String body) throws IOException;
    }

    public static final String ENDPOINT = "https://api.osv.dev/v1/querybatch";

    private final Transport transport;

    public OsvScanner(Transport transport) {
        this.transport = transport;
    }

    public static Transport httpTransport() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        return (url, body) -> {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new IOException("the advisory service answered " + response.statusCode());
                }
                return response.body();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while asking the advisory service", interrupted);
            }
        };
    }

    public List<String> scan(List<ResolvedDependency> dependencies) throws IOException {
        List<ResolvedDependency> asked = dependencies;
        ObjectNode body = Json.object();
        ArrayNode queries = body.putArray("queries");
        for (ResolvedDependency item : asked) {
            ObjectNode query = queries.addObject();
            ObjectNode pack = query.putObject("package");
            pack.put("name", item.key());
            pack.put("ecosystem", "Maven");
            query.put("version", item.version());
        }
        if (asked.isEmpty()) {
            return List.of();
        }
        JsonNode answer = Json.parse(transport.post(ENDPOINT, Json.text(body)));
        JsonNode results = answer.path("results");
        if (!results.isArray() || results.size() != asked.size()) {
            throw new IOException("the advisory service answered in an unexpected shape");
        }
        TreeSet<String> found = new TreeSet<>();
        for (int index = 0; index < asked.size(); index++) {
            for (JsonNode vulnerability : results.get(index).path("vulns")) {
                found.add(asked.get(index).key() + " " + vulnerability.path("id").asText());
            }
        }
        return new ArrayList<>(found);
    }
}
