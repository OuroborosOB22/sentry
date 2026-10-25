package org.upgradeguard.sentry.app.jobs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.upgradeguard.sentry.app.config.SentryProperties;
import org.upgradeguard.sentry.app.store.ProjectRepository;
import org.upgradeguard.sentry.app.store.Rows;
import org.upgradeguard.sentry.maven.EvidenceBuilder;

@Service
public class ProjectService {
    private final ProjectRepository projects;
    private final SentryProperties properties;

    public ProjectService(ProjectRepository projects, SentryProperties properties) {
        this.projects = projects;
        this.properties = properties;
    }

    public Rows.Project register(String name, String path) {
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9 ._-]{0,99}")) {
            throw new JobService.Rejected("the name may only use letters, digits, spaces, dots, dashes and underscores");
        }
        Path real = resolveAllowed(path);
        String ecosystem;
        if (Files.isRegularFile(real.resolve("pom.xml"))) {
            ecosystem = "MAVEN";
        } else if (Files.isRegularFile(real.resolve("requirements.txt"))) {
            ecosystem = "PYTHON";
        } else {
            throw new JobService.Rejected("the folder has neither a pom.xml nor a requirements.txt");
        }
        Optional<Rows.Project> existing = projects.findByPath(real.toString());
        if (existing.isPresent()) {
            return existing.get();
        }
        String base = EvidenceBuilder.slug(name);
        String id = base;
        for (int suffix = 2; projects.find(id).isPresent(); suffix++) {
            id = base + "-" + suffix;
        }
        Rows.Project project = new Rows.Project(id, name, real.toString(), ecosystem, Instant.now());
        projects.insert(project);
        return project;
    }

    private Path resolveAllowed(String path) {
        if (path == null || path.isBlank()) {
            throw new JobService.Rejected("choose a project folder");
        }
        Path real;
        try {
            real = Path.of(path).toRealPath();
        } catch (IOException | RuntimeException missing) {
            throw new JobService.Rejected("that folder does not exist");
        }
        if (!Files.isDirectory(real)) {
            throw new JobService.Rejected("that is not a folder");
        }
        for (Path root : properties.allowedRoots()) {
            try {
                if (real.startsWith(root.toRealPath())) {
                    return real;
                }
            } catch (IOException ignored) {
                continue;
            }
        }
        throw new JobService.Rejected("that folder is outside the roots this installation may check");
    }

    public List<Rows.Project> all() {
        return projects.all();
    }

    public Optional<Rows.Project> find(String id) {
        return projects.find(id);
    }
}
