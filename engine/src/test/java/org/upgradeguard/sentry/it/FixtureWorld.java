package org.upgradeguard.sentry.it;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

public final class FixtureWorld {
    public static final String GROUP = "org.upgradeguard.fixture";
    public static final String ARTIFACT = "demo-lib";

    private final Path root;

    public FixtureWorld(Path root) throws IOException {
        this.root = root;
        buildLibrary("1.0.0", "return \"Hello, \" + name;", "");
        buildLibrary("1.1.0", "return \"Hello, \" + name;", "public String greetLoud(String name) { return greet(name).toUpperCase(); }");
        buildLibrary("1.2.0", "return \"Hi \" + name;", "");
        buildBreakingLibrary("2.0.0");
    }

    public Path repository() {
        return root.resolve("fixture-repo");
    }

    private void buildLibrary(String version, String greetBody, String extra) throws IOException {
        String source = "package demo; public class Greeter { public String greet(String name) { " + greetBody + " } " + extra + " }";
        publish(version, source);
    }

    private void buildBreakingLibrary(String version) throws IOException {
        publish(version, "package demo; public class Greeter { public String salute(String name) { return \"Greetings, \" + name; } }");
    }

    private void publish(String version, String source) throws IOException {
        Path folder = repository().resolve(GROUP.replace('.', '/')).resolve(ARTIFACT).resolve(version);
        Files.createDirectories(folder);
        Path classes = Files.createTempDirectory(root, "classes-");
        compile(source, classes);
        try (OutputStream out = Files.newOutputStream(folder.resolve(ARTIFACT + "-" + version + ".jar")); JarOutputStream jar = new JarOutputStream(out)) {
            jar.putNextEntry(new JarEntry("demo/Greeter.class"));
            jar.write(Files.readAllBytes(classes.resolve("demo/Greeter.class")));
            jar.closeEntry();
        }
        Files.writeString(folder.resolve(ARTIFACT + "-" + version + ".pom"), "<project><modelVersion>4.0.0</modelVersion><groupId>" + GROUP + "</groupId><artifactId>"
                + ARTIFACT + "</artifactId><version>" + version + "</version></project>\n", StandardCharsets.UTF_8);
    }

    private static void compile(String source, Path output) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        JavaFileObject unit = new SimpleJavaFileObject(URI.create("string:///demo/Greeter.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
        try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            boolean ok = compiler.getTask(null, files, null, List.of("--release", "21", "-d", output.toString()), null, List.of(unit)).call();
            if (!ok) {
                throw new IOException("could not compile the fixture library");
            }
        }
    }

    public Map<String, String> properties() {
        return Map.of("fixture.repo", repository().toString());
    }

    public static String pom(String libVersionXml, String extraDependencies) {
        return """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.example</groupId>
                  <artifactId>client</artifactId>
                  <version>1.0.0</version>
                  <properties>
                    <maven.compiler.release>21</maven.compiler.release>
                    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                  </properties>
                  <repositories>
                    <repository>
                      <id>fixture</id>
                      <url>file://${fixture.repo}</url>
                    </repository>
                  </repositories>
                  <dependencies>
                    <dependency>
                      <groupId>org.upgradeguard.fixture</groupId>
                      <artifactId>demo-lib</artifactId>
                      %s
                    </dependency>
                    <dependency>
                      <groupId>org.junit.jupiter</groupId>
                      <artifactId>junit-jupiter</artifactId>
                      <version>5.11.4</version>
                      <scope>test</scope>
                    </dependency>
                    %s
                  </dependencies>
                  <build>
                    <plugins>
                      <plugin><artifactId>maven-resources-plugin</artifactId><version>3.3.1</version></plugin>
                      <plugin><artifactId>maven-compiler-plugin</artifactId><version>3.14.1</version></plugin>
                      <plugin><artifactId>maven-surefire-plugin</artifactId><version>3.5.5</version></plugin>
                      <plugin><artifactId>maven-jar-plugin</artifactId><version>3.4.2</version></plugin>
                    </plugins>
                  </build>
                </project>
                """.formatted(libVersionXml, extraDependencies);
    }

    public Path project(Path folder, String pomXml, String testSource) throws IOException {
        Files.createDirectories(folder.resolve("src/test/java/client"));
        Files.createDirectories(folder.resolve("src/main/java/client"));
        Files.writeString(folder.resolve("pom.xml"), pomXml, StandardCharsets.UTF_8);
        Files.writeString(folder.resolve("src/main/java/client/Hello.java"),
                "package client; public class Hello { public String say(String name) { return new demo.Greeter().greet(name); } }\n", StandardCharsets.UTF_8);
        if (testSource != null) {
            Files.writeString(folder.resolve("src/test/java/client/HelloTest.java"), testSource, StandardCharsets.UTF_8);
        }
        return folder;
    }

    public static final String PASSING_TESTS = """
            package client;
            import static org.junit.jupiter.api.Assertions.assertTrue;
            import org.junit.jupiter.api.Test;
            class HelloTest {
                @Test void saysHello() { assertTrue(new Hello().say("Ada").contains("Ada")); }
                @Test void addsAGreetingWord() { assertTrue(new Hello().say("Ada").startsWith("Hello")); }
            }
            """;

    public static final String ONLY_ADA_TEST = """
            package client;
            import static org.junit.jupiter.api.Assertions.assertTrue;
            import org.junit.jupiter.api.Test;
            class HelloTest {
                @Test void saysHello() { assertTrue(new Hello().say("Ada").contains("Ada")); }
            }
            """;
}
