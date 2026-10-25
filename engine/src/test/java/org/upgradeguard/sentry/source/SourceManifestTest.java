package org.upgradeguard.sentry.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceManifestTest {
    @Test
    void sameFilesGiveTheSameHashThatPythonComputes(@TempDir Path folder) throws Exception {
        Files.createDirectories(folder.resolve("pkg"));
        Files.writeString(folder.resolve("pkg/a.py"), "print('a')\n");
        Files.writeString(folder.resolve("b.txt"), "b\n");
        SourceManifest manifest = SourceManifest.of(folder);
        assertEquals(java.util.List.of("b.txt", "pkg/a.py"), java.util.List.copyOf(manifest.fileHashes().keySet()));
        assertEquals(PythonReference.MANIFEST_SHA256, manifest.manifestSha256());
        assertEquals("snap-" + PythonReference.MANIFEST_SHA256.substring(0, 12), manifest.snapshotId());
    }

    @Test
    void secretsCachesAndBuildOutputAreNeverSelected(@TempDir Path folder) throws Exception {
        Files.writeString(folder.resolve("keep.txt"), "x");
        for (String name : new String[] {".env", ".env.production", "server.pem", "id_rsa", "credentials.json", ".netrc"}) {
            Files.writeString(folder.resolve(name), "secret");
        }
        for (String name : new String[] {".git", "target", "node_modules", "evidence"}) {
            Files.createDirectories(folder.resolve(name));
            Files.writeString(folder.resolve(name).resolve("f.txt"), "x");
        }
        assertEquals(java.util.List.of("keep.txt"), java.util.List.copyOf(SourceManifest.of(folder).fileHashes().keySet()));
    }

    @Test
    void symlinksAreSkippedAndReported(@TempDir Path folder, @TempDir Path outside) throws Exception {
        Files.writeString(outside.resolve("secret.txt"), "do not read");
        Files.createSymbolicLink(folder.resolve("link.txt"), outside.resolve("secret.txt"));
        Files.createSymbolicLink(folder.resolve("linked-folder"), outside);
        Files.writeString(folder.resolve("a.txt"), "a");
        SourceManifest manifest = SourceManifest.of(folder);
        assertEquals(java.util.List.of("a.txt"), java.util.List.copyOf(manifest.fileHashes().keySet()));
        assertTrue(manifest.skipped().contains("link.txt: symlink"));
        assertTrue(manifest.skipped().contains("linked-folder: symlink"));
    }

    @Test
    void contentChangesTheHash(@TempDir Path folder) throws Exception {
        Files.writeString(folder.resolve("a.txt"), "one");
        String first = SourceManifest.of(folder).manifestSha256();
        Files.writeString(folder.resolve("a.txt"), "two");
        assertNotEquals(first, SourceManifest.of(folder).manifestSha256());
    }

    @Test
    void exclusionRulesMatchTheDocumentedPatterns() {
        assertTrue(SourceManifest.isExcluded(".env"));
        assertTrue(SourceManifest.isExcluded(".env.local"));
        assertTrue(SourceManifest.isExcluded("my.pem"));
        assertFalse(SourceManifest.isExcluded("environment.txt"));
        assertFalse(SourceManifest.isExcluded("pom.xml"));
    }
}
