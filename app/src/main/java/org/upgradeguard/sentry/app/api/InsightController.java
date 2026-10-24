package org.upgradeguard.sentry.app.api;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.upgradeguard.sentry.app.config.SentryProperties;
import org.upgradeguard.sentry.app.explain.AppSettings;
import org.upgradeguard.sentry.app.explain.InsightService;
import org.upgradeguard.sentry.app.jobs.JobService;
import org.upgradeguard.sentry.app.provider.ExecutionProvider;
import org.upgradeguard.sentry.app.provider.ProviderRegistry;
import org.upgradeguard.sentry.app.risk.RiskModelProvider;
import org.upgradeguard.sentry.app.store.JobRepository;
import org.upgradeguard.sentry.app.store.ProjectRepository;
import org.upgradeguard.sentry.app.store.Rows;
import org.upgradeguard.sentry.contract.Json;
import org.upgradeguard.sentry.explain.MermaidRenderer;
import org.upgradeguard.sentry.maven.MavenCoordinate;
import org.upgradeguard.sentry.maven.Versions;
import org.upgradeguard.sentry.source.SourceManifest;

@RestController
@RequestMapping("/api")
public class InsightController {
    private final InsightService insight;
    private final JobRepository jobs;
    private final ProjectRepository projects;
    private final AppSettings settings;
    private final ProviderRegistry providers;
    private final SentryProperties properties;
    private final RiskModelProvider risk;

    public InsightController(InsightService insight, JobRepository jobs, ProjectRepository projects, AppSettings settings, ProviderRegistry providers, SentryProperties properties,
                             RiskModelProvider risk) {
        this.insight = insight;
        this.jobs = jobs;
        this.projects = projects;
        this.settings = settings;
        this.providers = providers;
        this.properties = properties;
        this.risk = risk;
    }

    private void allow(HttpServletRequest request, String jobId) {
        Rows.Job job = jobs.find(jobId).orElseThrow(ApiController.Missing::new);
        if (!ProjectAccess.allowed(request, job.projectId())) {
            throw new ApiController.Forbidden();
        }
    }

    private static boolean administrator(HttpServletRequest request) {
        return request.getAttribute(AccessFilter.PROJECTS_ATTRIBUTE) instanceof List<?> list && list.contains("*");
    }

    @GetMapping(value = "/jobs/{id}/diagram", produces = MediaType.APPLICATION_JSON_VALUE)
    public String diagram(HttpServletRequest request, @PathVariable String id, @RequestParam(defaultValue = "DIFF") String mode) throws IOException {
        allow(request, id);
        return Json.text(insight.diagram(id, parseMode(mode)));
    }

    @PostMapping(value = "/jobs/{id}/explain", produces = MediaType.APPLICATION_JSON_VALUE)
    public String explain(HttpServletRequest request, @PathVariable String id) throws IOException {
        allow(request, id);
        return Json.text(insight.explain(id));
    }

    @GetMapping(value = "/jobs/{id}/export.md", produces = "text/markdown;charset=UTF-8")
    public String export(HttpServletRequest request, @PathVariable String id, @RequestParam(defaultValue = "DIFF") String mode,
                         @RequestParam(defaultValue = "false") boolean explanation) throws IOException {
        allow(request, id);
        return insight.markdown(id, parseMode(mode), explanation);
    }

    private static MermaidRenderer.Mode parseMode(String mode) {
        try {
            return MermaidRenderer.Mode.valueOf(mode);
        } catch (IllegalArgumentException invalid) {
            throw new JobService.Rejected("mode must be BEFORE, AFTER or DIFF");
        }
    }

    public record SettingsBody(String defaultExecution, String explanationProvider, String explanationModel, String explanationBaseUrl) {
    }

    @GetMapping(value = "/settings", produces = MediaType.APPLICATION_JSON_VALUE)
    public String settings() {
        var node = Json.object();
        var execution = node.putObject("execution");
        execution.put("default", settings.defaultExecution());
        var list = execution.putArray("providers");
        providers.ids().forEach(list::add);
        execution.put("azureConfigured", properties.azure().enabled());
        var explanation = node.putObject("explanation");
        explanation.put("provider", settings.explanationProvider());
        explanation.put("model", settings.explanationModel());
        explanation.put("baseUrlHost", settings.host(settings.explanationBaseUrl()));
        explanation.put("apiKeyPresent", settings.apiKeyPresent());
        var risk = node.putObject("risk");
        risk.put("enabled", properties.risk().enabled());
        risk.put("baseUrlHost", settings.host(properties.risk().baseUrl()));
        node.put("localExecutionAllowed", properties.auth().tokens().isEmpty() || properties.allowLocalInHosted());
        return Json.text(node);
    }

    @PutMapping(value = "/settings", produces = MediaType.APPLICATION_JSON_VALUE)
    public String update(HttpServletRequest request, @RequestBody SettingsBody body) {
        if (!administrator(request)) {
            throw new ApiController.Forbidden();
        }
        settings.update(body.defaultExecution(), body.explanationProvider(), body.explanationModel(), body.explanationBaseUrl());
        return settings();
    }

    @PostMapping(value = "/settings/test/{provider}", produces = MediaType.APPLICATION_JSON_VALUE)
    public String test(HttpServletRequest request, @PathVariable String provider) {
        if (!administrator(request)) {
            throw new ApiController.Forbidden();
        }
        ExecutionProvider found = providers.find(provider).orElseThrow(ApiController.Missing::new);
        ExecutionProvider.ProviderCheck check = found.validate();
        var node = Json.object();
        node.put("provider", found.id());
        node.put("usable", check.usable());
        node.put("summary", check.summary());
        check.details().forEach(node.putArray("details")::add);
        return Json.text(node);
    }

    public record RiskBody(String packageName, String fromVersion, String toVersion) {
    }

    @PostMapping(value = "/projects/{id}/risk", produces = MediaType.APPLICATION_JSON_VALUE)
    public String risk(HttpServletRequest request, @PathVariable String id, @RequestBody RiskBody body) {
        projects.find(id).orElseThrow(ApiController.Missing::new);
        if (!ProjectAccess.allowed(request, id)) {
            throw new ApiController.Forbidden();
        }
        try {
            MavenCoordinate.parse(body.packageName());
            Versions.require(body.fromVersion());
            Versions.require(body.toVersion());
        } catch (IllegalArgumentException problem) {
            throw new JobService.Rejected(problem.getMessage());
        }
        return Json.text(risk.predict(id, body.packageName(), body.fromVersion(), body.toVersion()));
    }

    @GetMapping(value = "/projects/{id}/transfer-preview", produces = MediaType.APPLICATION_JSON_VALUE)
    public String transferPreview(HttpServletRequest request, @PathVariable String id) throws IOException {
        Rows.Project project = projects.find(id).orElseThrow(ApiController.Missing::new);
        if (!ProjectAccess.allowed(request, id)) {
            throw new ApiController.Forbidden();
        }
        SourceManifest manifest = SourceManifest.of(java.nio.file.Path.of(project.path()));
        var node = Json.object();
        node.put("files", manifest.fileHashes().size());
        long bytes = 0;
        for (String relative : manifest.fileHashes().keySet()) {
            bytes += java.nio.file.Files.size(java.nio.file.Path.of(project.path()).resolve(relative));
        }
        node.put("bytes", bytes);
        var sample = node.putArray("first_files");
        manifest.fileHashes().keySet().stream().limit(50).forEach(sample::add);
        manifest.exclusionLabels().forEach(node.putArray("never_sent")::add);
        manifest.skipped().forEach(node.putArray("skipped")::add);
        node.put("note", "Only these files would be packed and sent when a remote host is chosen. Nothing is sent for local runs.");
        return Json.text(node);
    }
}
