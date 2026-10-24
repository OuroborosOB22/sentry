package org.upgradeguard.sentry.source;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class SourceArchive {
    public static final long MAX_EXTRACTED_BYTES = 500L * 1024 * 1024;

    private SourceArchive() {
    }

    public static void write(Path root, SourceManifest manifest, Path archive) throws IOException {
        Files.createDirectories(archive.toAbsolutePath().getParent());
        try (OutputStream out = Files.newOutputStream(archive); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (String relative : manifest.fileHashes().keySet()) {
                zip.putNextEntry(new ZipEntry(relative));
                Files.copy(root.resolve(relative), zip);
                zip.closeEntry();
            }
        }
    }

    public static void extract(Path archive, Path destination) throws IOException {
        Path base = destination.toAbsolutePath().normalize();
        Files.createDirectories(base);
        long total = 0;
        try (InputStream in = Files.newInputStream(archive); ZipInputStream zip = new ZipInputStream(in)) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                Path target = base.resolve(entry.getName()).normalize();
                if (!target.startsWith(base) || entry.getName().startsWith("/") || entry.getName().contains("\\")) {
                    throw new IOException("archive entry " + entry.getName() + " leaves the destination");
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                try (OutputStream out = Files.newOutputStream(target)) {
                    byte[] buffer = new byte[65536];
                    int read;
                    while ((read = zip.read(buffer)) > 0) {
                        total += read;
                        if (total > MAX_EXTRACTED_BYTES) {
                            throw new IOException("the archive expands to more than " + MAX_EXTRACTED_BYTES + " bytes");
                        }
                        out.write(buffer, 0, read);
                    }
                }
            }
        }
    }
}
