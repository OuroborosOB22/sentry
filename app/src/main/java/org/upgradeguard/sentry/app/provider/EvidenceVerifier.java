package org.upgradeguard.sentry.app.provider;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.upgradeguard.sentry.contract.Json;
import org.upgradeguard.sentry.contract.SchemaValidator;
import org.upgradeguard.sentry.source.SourceManifest;

public final class EvidenceVerifier {
    public static final long MAX_BYTES = 500L * 1024 * 1024;
    private static final SchemaValidator VALIDATOR = new SchemaValidator();

    private EvidenceVerifier() {
    }

    public static class UntrustedOutput extends IOException {
        public UntrustedOutput(String message) {
            super(message);
        }
    }

    public static JsonNode verify(Path folder) throws IOException {
        Path evidenceFile = folder.resolve("evidence.json");
        if (!Files.isRegularFile(evidenceFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new UntrustedOutput("the output has no evidence.json");
        }
        long total = 0;
        Map<String, Path> present = new HashMap<>();
        try (Stream<Path> walk = Files.walk(folder)) {
            for (Path path : walk.toList()) {
                if (path.equals(folder)) {
                    continue;
                }
                if (Files.isSymbolicLink(path)) {
                    throw new UntrustedOutput("the output contains a link at " + folder.relativize(path));
                }
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    total += Files.size(path);
                    present.put(folder.relativize(path).toString().replace('\\', '/'), path);
                } else if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw new UntrustedOutput("the output contains something that is not a plain file");
                }
            }
        }
        if (total > MAX_BYTES) {
            throw new UntrustedOutput("the output is larger than " + MAX_BYTES + " bytes");
        }
        JsonNode evidence = Json.read(evidenceFile);
        VALIDATOR.require("evidence", evidence);
        Map<String, String> listed = new HashMap<>();
        evidence.path("artifact_manifest").forEach(item -> listed.put(item.path("path").asText(), item.path("sha256").asText()));
        present.remove("evidence.json");
        if (!present.keySet().equals(listed.keySet())) {
            throw new UntrustedOutput("the files in the output do not match the evidence manifest");
        }
        for (Map.Entry<String, String> entry : listed.entrySet()) {
            if (!SourceManifest.sha256(present.get(entry.getKey())).equals(entry.getValue())) {
                throw new UntrustedOutput(entry.getKey() + " does not match its recorded hash");
            }
        }
        return evidence;
    }
}
