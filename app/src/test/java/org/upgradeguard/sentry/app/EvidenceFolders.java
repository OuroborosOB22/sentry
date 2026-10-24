package org.upgradeguard.sentry.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.upgradeguard.sentry.contract.Json;
import org.upgradeguard.sentry.source.SourceManifest;

final class EvidenceFolders {
    private EvidenceFolders() {
    }

    static Path create(Path folder, String runId, String decision) throws IOException {
        Files.createDirectories(folder.resolve("after"));
        Files.writeString(folder.resolve("after/pytest.log"), "log for " + runId);
        Files.writeString(folder.resolve("verdict.json"), "{\"decision\": \"" + decision + "\"}");
        ObjectNode evidence = (ObjectNode) Json.read(Path.of("..", "contracts", "v1", "fixtures", "valid", "evidence_reject.json"));
        evidence.put("run_id", runId);
        ((ObjectNode) evidence.get("request")).put("run_id", runId);
        ((ObjectNode) evidence.get("verdict")).put("decision", decision);
        ArrayNode manifest = Json.array();
        for (String relative : new String[] {"after/pytest.log", "verdict.json"}) {
            ObjectNode item = Json.object();
            item.put("id", relative.replace('/', '-'));
            item.put("path", relative);
            item.put("sha256", SourceManifest.sha256(folder.resolve(relative)));
            item.put("media_type", "text/plain");
            manifest.add(item);
        }
        evidence.set("artifact_manifest", manifest);
        for (JsonNode finding : evidence.path("verdict").path("findings")) {
            ((ArrayNode) ((ObjectNode) finding).putArray("evidence_ids")).add("after-pytest.log");
        }
        Json.write(folder.resolve("evidence.json"), evidence);
        return folder;
    }
}
