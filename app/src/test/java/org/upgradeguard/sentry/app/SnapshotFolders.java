package org.upgradeguard.sentry.app;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.upgradeguard.sentry.contract.Json;
import org.upgradeguard.sentry.source.SourceManifest;

final class SnapshotFolders {
    static final String ID = "snap-0123456789ab";

    private SnapshotFolders() {
    }

    static Path create(Path destination, String slug, String mode) throws IOException {
        String folderName = mode.equals("wrong-name") ? "snap-ffffffffffff" : ID;
        Path folder = destination.resolve("snapshots").resolve(mode.equals("other-project") ? "someone-else" : slug).resolve(folderName);
        Files.createDirectories(folder.resolve("logs"));
        Files.writeString(folder.resolve("logs/install.log"), "install log");
        Files.writeString(folder.resolve("manifest.json"), "{}");
        ObjectNode snapshot = (ObjectNode) Json.read(Path.of("..", "contracts", "v1", "fixtures", "valid", "snapshot_passing.json"));
        snapshot.put("snapshot_id", ID);
        ArrayNode artifacts = Json.array();
        for (String relative : new String[] {"logs/install.log", "manifest.json"}) {
            ObjectNode item = Json.object();
            item.put("id", relative.replace('/', '-'));
            item.put("path", relative);
            item.put("sha256", SourceManifest.sha256(folder.resolve(relative)));
            item.put("media_type", "text/plain");
            artifacts.add(item);
        }
        snapshot.set("artifacts", artifacts);
        Json.write(folder.resolve("snapshot.json"), snapshot);
        if (mode.equals("tampered")) {
            Files.writeString(folder.resolve("logs/install.log"), "forged");
        }
        if (mode.equals("symlink")) {
            Files.createSymbolicLink(folder.resolve("shortcut"), Path.of("/etc/passwd"));
        }
        if (mode.equals("two")) {
            Path second = folder.getParent().resolve("snap-aaaaaaaaaaaa");
            Files.createDirectories(second);
            Files.writeString(second.resolve("snapshot.json"), "{}");
        }
        return destination;
    }
}
