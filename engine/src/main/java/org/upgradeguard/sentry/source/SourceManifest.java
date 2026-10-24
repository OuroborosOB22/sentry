package org.upgradeguard.sentry.source;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

public final class SourceManifest {
    public static final Set<String> EXCLUDED_NAMES = Set.of(
            ".git", ".venv", "venv", "env", "__pycache__", ".pytest_cache", ".tox", ".mypy_cache", "node_modules",
            "evidence", "work", "snapshots", "target", ".idea");
    public static final List<String> SECRET_PATTERNS = List.of(
            ".env", ".env.*", "*.pem", "*.key", "id_rsa*", "id_ed25519*", "*.p12", "*.pfx", ".netrc", "credentials*");
    public static final long MAX_FILE_BYTES = 5L * 1024 * 1024;

    private final Map<String, String> fileHashes;
    private final List<String> skipped;

    private SourceManifest(Map<String, String> fileHashes, List<String> skipped) {
        this.fileHashes = fileHashes;
        this.skipped = skipped;
    }

    public static SourceManifest of(Path root) throws IOException {
        Path base = root.toRealPath();
        Map<String, String> hashes = new TreeMap<>();
        List<String> skipped = new ArrayList<>();
        walk(base, base, hashes, skipped);
        Collections.sort(skipped);
        return new SourceManifest(hashes, skipped);
    }

    private static void walk(Path base, Path folder, Map<String, String> hashes, List<String> skipped) throws IOException {
        List<Path> children;
        try (Stream<Path> stream = Files.list(folder)) {
            children = stream.sorted().toList();
        }
        for (Path child : children) {
            String name = child.getFileName().toString();
            if (isExcluded(name)) {
                continue;
            }
            String relative = base.relativize(child).toString().replace('\\', '/');
            if (Files.isSymbolicLink(child)) {
                skipped.add(relative + ": symlink");
            } else if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                walk(base, child, hashes, skipped);
            } else if (Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.size(child) > MAX_FILE_BYTES) {
                    skipped.add(relative + ": larger than " + MAX_FILE_BYTES + " bytes");
                } else {
                    hashes.put(relative, sha256(child));
                }
            }
        }
    }

    public static boolean isExcluded(String name) {
        if (EXCLUDED_NAMES.contains(name) || name.endsWith(".pyc") || name.endsWith(".pyo")) {
            return true;
        }
        for (String pattern : SECRET_PATTERNS) {
            if (matches(pattern, name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(String pattern, String name) {
        if (!pattern.contains("*")) {
            return pattern.equals(name);
        }
        String quoted = java.util.regex.Pattern.quote(pattern).replace("*", "\\E.*\\Q");
        return name.matches(quoted);
    }

    public static String sha256(Path file) throws IOException {
        try (InputStream stream = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            int read;
            while ((read = stream.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public Map<String, String> fileHashes() {
        return Collections.unmodifiableMap(fileHashes);
    }

    public List<String> skipped() {
        return Collections.unmodifiableList(skipped);
    }

    public String manifestSha256() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Map.Entry<String, String> entry : fileHashes.entrySet()) {
                digest.update((entry.getKey() + "\0" + entry.getValue() + "\n").getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public String snapshotId() {
        return "snap-" + manifestSha256().substring(0, 12);
    }

    public List<String> exclusionLabels() {
        List<String> labels = new ArrayList<>(EXCLUDED_NAMES);
        labels.addAll(SECRET_PATTERNS);
        Collections.sort(labels);
        return labels;
    }
}
