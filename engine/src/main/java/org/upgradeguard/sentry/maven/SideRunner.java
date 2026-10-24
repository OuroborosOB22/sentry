package org.upgradeguard.sentry.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public final class SideRunner {
    private static final String DEPENDENCY_PLUGIN = "org.apache.maven.plugins:maven-dependency-plugin:3.9.0";

    private final MavenInvoker invoker;

    public SideRunner(MavenInvoker invoker) {
        this.invoker = invoker;
    }

    public SideObservation observe(String side, Path projectDir, Path outDir, VerifyProfile profile) throws IOException {
        Files.createDirectories(outDir);
        String javaVersion = runtimeText(projectDir, outDir.resolve("version.log"), "Java version:");
        String mavenVersion = runtimeText(projectDir, outDir.resolve("version.log"), "Apache Maven");

        Path installLog = outDir.resolve("install.log");
        Map<String, String> installProperties = new HashMap<>(profile.properties());
        installProperties.put("outputFile", outDir.resolve("dependencies.txt").toString());
        installProperties.put("appendOutput", "false");
        MavenInvoker.Result install = invoker.run(projectDir, List.of(DEPENDENCY_PLUGIN + ":resolve", DEPENDENCY_PLUGIN + ":list"),
                installProperties, installLog, profile.installTimeout());
        StageResult installStage = stage(install, installLog);
        List<ResolvedDependency> dependencies = new ArrayList<>();
        if (installStage.passed() && Files.isRegularFile(outDir.resolve("dependencies.txt"))) {
            dependencies = DependencyListParser.parse(Files.readString(outDir.resolve("dependencies.txt")));
        }
        if (!installStage.passed()) {
            return new SideObservation(side, installStage, StageResult.notRun(), StageResult.notRun(), List.of(), dependencies, false,
                    install.timedOut(), javaVersion, mavenVersion);
        }

        Path verifyLog = outDir.resolve("verify.log");
        MavenInvoker.Result verify = invoker.run(projectDir, profile.goals(), profile.properties(), verifyLog, profile.verifyTimeout());
        ReportParser.Reports reports = ReportParser.readFolders(
                projectDir.resolve("target/surefire-reports"), projectDir.resolve("target/failsafe-reports"));
        keepReports(projectDir, outDir);
        String log = Files.readString(verifyLog);
        boolean anyBroken = reports.results().stream().anyMatch(item -> item.outcome() == TestOutcome.FAIL || item.outcome() == TestOutcome.ERROR);
        boolean compileFailure = verify.exitCode() != 0 && !verify.timedOut() && (log.contains("COMPILATION ERROR") || log.contains("Compilation failure"));

        StageResult build;
        StageResult tests;
        boolean infrastructure = false;
        if (verify.timedOut()) {
            build = StageResult.notRun();
            tests = new StageResult(StageResult.TIMED_OUT, null, verify.seconds(), verifyLog.getFileName().toString());
        } else if (verify.exitCode() == 0) {
            build = new StageResult(StageResult.PASS, 0, verify.seconds(), verifyLog.getFileName().toString());
            tests = reports.results().isEmpty() ? StageResult.notRun()
                    : new StageResult(StageResult.PASS, 0, verify.seconds(), verifyLog.getFileName().toString());
        } else if (anyBroken) {
            build = new StageResult(StageResult.PASS, null, verify.seconds(), verifyLog.getFileName().toString());
            tests = new StageResult(StageResult.FAIL, verify.exitCode(), verify.seconds(), verifyLog.getFileName().toString());
        } else if (compileFailure) {
            build = new StageResult(StageResult.FAIL, verify.exitCode(), verify.seconds(), verifyLog.getFileName().toString());
            tests = StageResult.notRun();
        } else {
            build = new StageResult(StageResult.FAIL, verify.exitCode(), verify.seconds(), verifyLog.getFileName().toString());
            tests = StageResult.notRun();
            infrastructure = true;
        }
        return new SideObservation(side, installStage, build, tests, reports.results(), dependencies, compileFailure,
                infrastructure || verify.timedOut(), javaVersion, mavenVersion);
    }

    private static StageResult stage(MavenInvoker.Result result, Path log) {
        String outcome = result.timedOut() ? StageResult.TIMED_OUT : (result.exitCode() == 0 ? StageResult.PASS : StageResult.FAIL);
        return new StageResult(outcome, result.timedOut() ? null : result.exitCode(), result.seconds(), log.getFileName().toString());
    }

    private String runtimeText(Path projectDir, Path log, String marker) throws IOException {
        if (!Files.exists(log)) {
            invoker.run(projectDir, List.of("-v"), Map.of(), log, java.time.Duration.ofSeconds(60));
        }
        for (String line : Files.readAllLines(log)) {
            if (line.startsWith(marker)) {
                return line.substring(marker.length()).trim().split(",")[0].trim().replace("Apache Maven", "").trim().split(" ")[0];
            }
        }
        return "unknown";
    }

    private static void keepReports(Path projectDir, Path outDir) throws IOException {
        for (String folder : List.of("surefire-reports", "failsafe-reports")) {
            Path source = projectDir.resolve("target").resolve(folder);
            if (!Files.isDirectory(source)) {
                continue;
            }
            try (Stream<Path> files = Files.list(source)) {
                for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".xml")).toList()) {
                    Path target = outDir.resolve("reports").resolve(folder).resolve(file.getFileName());
                    Files.createDirectories(target.getParent());
                    Files.copy(file, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
