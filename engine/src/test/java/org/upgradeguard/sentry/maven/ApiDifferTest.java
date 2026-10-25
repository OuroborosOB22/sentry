package org.upgradeguard.sentry.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApiDifferTest {
    private static Path jarWithoutItsParent(Path folder, String name, String thingBody) throws IOException {
        Path source = folder.resolve(name + "-src");
        Files.createDirectories(source.resolve("t"));
        Files.writeString(source.resolve("t/Missing.java"), "package t; public class Missing { }");
        Files.writeString(source.resolve("t/Thing.java"), "package t; public class Thing extends Missing { " + thingBody + " }");
        Path classes = Files.createDirectories(folder.resolve(name + "-classes"));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertEquals(0, compiler.run(null, null, null, "-d", classes.toString(), source.resolve("t/Missing.java").toString(), source.resolve("t/Thing.java").toString()));
        Path jar = folder.resolve(name + ".jar");
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream jarOut = new JarOutputStream(out)) {
            jarOut.putNextEntry(new JarEntry("t/Thing.class"));
            jarOut.write(Files.readAllBytes(classes.resolve("t/Thing.class")));
            jarOut.closeEntry();
        }
        return jar;
    }

    @Test
    void aLibraryWhoseParentClassIsInAnotherJarCanStillBeCompared(@TempDir Path folder) throws IOException {
        Path oldJar = jarWithoutItsParent(folder, "old", "public void greet() { }");
        Path newJar = jarWithoutItsParent(folder, "new", "");
        ObjectNode result = ApiDiffer.compare(oldJar, newJar, "1.0", "2.0");
        assertTrue(result.path("available").asBoolean());
        assertTrue(result.path("missing_classes_ignored").asBoolean());
        assertEquals(1, result.path("method_changes").asInt());
        assertTrue(result.toString().contains("METHOD_REMOVED"));
    }

    @Test
    void jarsThatCannotBeReadAreReportedAsUnavailableAndNeverFailTheRun(@TempDir Path folder) throws IOException {
        Path bad = Files.writeString(folder.resolve("bad.jar"), "not a jar at all");
        ObjectNode result = ApiDiffer.compare(bad, bad, "1.0", "2.0");
        assertFalse(result.path("available").asBoolean());
        assertTrue(result.path("reason").asText().contains("could not be compared"));
    }

    @Test
    void missingJarsAreReportedAsUnavailable(@TempDir Path folder) {
        ObjectNode result = ApiDiffer.compare(folder.resolve("a.jar"), folder.resolve("b.jar"), "1", "2");
        assertFalse(result.path("available").asBoolean());
    }
}
