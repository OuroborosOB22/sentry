package org.upgradeguard.sentry.app.explain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.upgradeguard.sentry.app.config.SentryProperties;
import org.upgradeguard.sentry.app.jobs.JobService;
import org.upgradeguard.sentry.app.store.ProjectRepository;
import org.upgradeguard.sentry.app.store.Rows;
import org.upgradeguard.sentry.contract.Json;
import org.upgradeguard.sentry.explain.DiagramBuilder;
import org.upgradeguard.sentry.explain.DiagramData;
import org.upgradeguard.sentry.explain.DisabledExplanationProvider;
import org.upgradeguard.sentry.explain.ExplanationProvider;
import org.upgradeguard.sentry.explain.MermaidRenderer;
import org.upgradeguard.sentry.explain.OpenAiCompatibleProvider;
import org.upgradeguard.sentry.explain.PlainText;
import org.upgradeguard.sentry.explain.Redactor;
import org.upgradeguard.sentry.source.SourceManifest;

@Service
public class InsightService {
    private final JobService jobs;
    private final AppSettings settings;
    private final SentryProperties properties;
    private final JdbcTemplate jdbc;
    private final ProjectRepository projects;

    public static final int MAX_FILES = 3;
    public static final int MAX_FILE_BYTES = 12_000;
    public static final int MAX_CANDIDATES = 50;

    public InsightService(JobService jobs, AppSettings settings, SentryProperties properties, JdbcTemplate jdbc, ProjectRepository projects) {
        this.jobs = jobs;
        this.settings = settings;
        this.properties = properties;
        this.jdbc = jdbc;
        this.projects = projects;
    }

    private Path projectFolder(String jobId) {
        Rows.Job job = jobs.find(jobId).orElseThrow(() -> new JobService.Rejected("unknown job"));
        return Path.of(projects.find(job.projectId()).orElseThrow(() -> new JobService.Rejected("unknown project")).path());
    }

    public List<String> candidateFiles(String jobId) throws IOException {
        String group = evidence(jobId).path("request").path("change").path("package").asText().split(":")[0];
        if (group.isBlank()) {
            return List.of();
        }
        Path root = projectFolder(jobId);
        List<String> found = new ArrayList<>();
        for (String relative : SourceManifest.of(root).fileHashes().keySet()) {
            if (relative.endsWith(".java") && found.size() < MAX_CANDIDATES) {
                String text = Files.readString(root.resolve(relative), StandardCharsets.UTF_8);
                if (Pattern.compile("(?m)^\\s*import\\s+(static\\s+)?" + Pattern.quote(group)).matcher(text).find()) {
                    found.add(relative);
                }
            }
        }
        return found;
    }

    public List<ExplanationProvider.CodeFile> selectedFiles(String jobId, List<String> names) throws IOException {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        if (names.size() > MAX_FILES) {
            throw new JobService.Rejected("at most " + MAX_FILES + " files can be shared");
        }
        List<String> allowed = candidateFiles(jobId);
        Path root = projectFolder(jobId).toRealPath();
        List<ExplanationProvider.CodeFile> files = new ArrayList<>();
        for (String name : names) {
            if (!allowed.contains(name)) {
                throw new JobService.Rejected("that file cannot be shared: " + PlainText.clean(name, 80));
            }
            Path full = root.resolve(name).normalize();
            if (!full.startsWith(root) || Files.isSymbolicLink(full)) {
                throw new JobService.Rejected("that file cannot be shared");
            }
            byte[] bytes = Files.readAllBytes(full);
            String text = new String(bytes, 0, Math.min(bytes.length, MAX_FILE_BYTES), StandardCharsets.UTF_8);
            files.add(new ExplanationProvider.CodeFile(name, Redactor.redact(text)));
        }
        return files;
    }

    public ObjectNode preview(String jobId, List<String> names) throws IOException {
        JsonNode evidence = evidence(jobId);
        List<ExplanationProvider.CodeFile> files = selectedFiles(jobId, names);
        ExplanationProvider provider = provider();
        ObjectNode node = Json.object();
        ObjectNode destination = node.putObject("destination");
        destination.put("provider", provider.id());
        destination.put("model", provider.model());
        destination.put("host", settings.host(settings.explanationBaseUrl()));
        node.put("mode", files.isEmpty() ? "REPORT" : "CODE");
        ObjectNode facts = OpenAiCompatibleProvider.facts(evidence, DiagramBuilder.fromEvidence(evidence), files);
        var items = node.putArray("items");
        OpenAiCompatibleProvider.evidenceIds(evidence, files).forEach(id -> items.addObject().put("id", id).put("kind", id.substring(0, id.indexOf(':'))));
        node.put("total_bytes", Json.text(facts).getBytes(StandardCharsets.UTF_8).length);
        var shown = node.putArray("files");
        files.forEach(file -> shown.add(file.path()));
        node.put("sent", !provider.id().equals("DISABLED"));
        node.put("note", provider.id().equals("DISABLED") ? "No model is set up, so nothing would be sent anywhere."
                : "Repository text is sent as data, never as instructions. Secret-looking text is removed from files first.");
        return node;
    }

    private JsonNode evidence(String jobId) throws IOException {
        return jobs.evidence(jobId).orElseThrow(() -> new JobService.Rejected("this job has no evidence yet"));
    }

    public ObjectNode diagram(String jobId, MermaidRenderer.Mode mode) throws IOException {
        DiagramData data = DiagramBuilder.fromEvidence(evidence(jobId));
        ObjectNode out = Json.object();
        out.set("diagram", new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(data));
        MermaidRenderer.Rendered rendered = MermaidRenderer.renderWithIds(data, mode);
        out.put("mermaid", rendered.text());
        rendered.nodeIds().forEach(out.putObject("node_ids")::put);
        out.put("mode", mode.name());
        return out;
    }

    ExplanationProvider provider() {
        if (!settings.explanationProvider().equals("OPENAI_COMPATIBLE") || settings.explanationBaseUrl().isBlank() || settings.explanationModel().isBlank()) {
            return new DisabledExplanationProvider();
        }
        SentryProperties.Explanation config = properties.explanation();
        return new OpenAiCompatibleProvider(settings.explanationBaseUrl(), settings.explanationModel(), settings.apiKey(), config.timeout(), config.maxOutputTokens());
    }

    public ObjectNode explain(String jobId) throws IOException {
        return explain(jobId, List.of());
    }

    public ObjectNode explain(String jobId, List<String> names) throws IOException {
        JsonNode evidence = evidence(jobId);
        ExplanationProvider provider = provider();
        List<ExplanationProvider.CodeFile> files = selectedFiles(jobId, names);
        String fingerprint = fingerprint(evidence, provider, files);
        Optional<String> cached = jdbc.queryForList("SELECT body FROM explanations WHERE job_id = ? AND fingerprint = ?", String.class, jobId, fingerprint).stream().findFirst();
        if (cached.isPresent()) {
            ObjectNode node = (ObjectNode) Json.parse(cached.get());
            node.put("cached", true);
            return node;
        }
        DiagramData data = DiagramBuilder.fromEvidence(evidence);
        ExplanationProvider.Result result = provider.explain(evidence, data, files);
        ObjectNode node = Json.object();
        node.put("status", result.status());
        node.put("provider", provider.id());
        node.put("model", provider.model());
        node.put("summary", result.summary());
        var annotations = node.putArray("annotations");
        result.annotations().forEach(item -> annotations.addObject().put("node_id", item.nodeId()).put("text", item.text()));
        result.sources().forEach(node.putArray("sources")::add);
        node.put("note", result.note());
        node.put("mode", files.isEmpty() ? "REPORT" : "CODE");
        node.put("labelled", "Written by a language model from the measured facts. It is not evidence and never changes the verdict.");
        node.put("cached", false);
        if (result.status().equals(ExplanationProvider.Result.OK)) {
            jdbc.update("INSERT INTO explanations (job_id, fingerprint, provider, model, body, created_at) VALUES (?, ?, ?, ?, ?, ?)", jobId, fingerprint, provider.id(), provider.model(),
                    Json.text(node), java.sql.Timestamp.from(Instant.now()));
        }
        return node;
    }

    static String fingerprint(JsonNode evidence, ExplanationProvider provider, List<ExplanationProvider.CodeFile> files) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((Json.text(evidence) + "|" + provider.id() + "|" + provider.model() + "|" + OpenAiCompatibleProvider.PROMPT_VERSION).getBytes(StandardCharsets.UTF_8));
            files.forEach(file -> digest.update((file.path() + "|" + file.text()).getBytes(StandardCharsets.UTF_8)));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public String markdown(String jobId, MermaidRenderer.Mode mode, boolean includeExplanation) throws IOException {
        JsonNode evidence = evidence(jobId);
        DiagramData data = DiagramBuilder.fromEvidence(evidence);
        StringBuilder text = new StringBuilder();
        JsonNode change = evidence.path("request").path("change");
        text.append("# ").append(PlainText.clean(change.path("package").asText(), 120)).append(' ').append(PlainText.clean(change.path("from_version").asText(), 40)).append(" to ")
                .append(PlainText.clean(change.path("to_version").asText(), 40)).append("\n\n");
        text.append("Verdict: **").append(evidence.path("verdict").path("decision").asText()).append("**\n\n## Findings\n\n");
        for (JsonNode finding : evidence.path("verdict").path("findings")) {
            text.append("- `").append(PlainText.clean(finding.path("code").asText(), 60)).append("` (").append(PlainText.clean(finding.path("severity").asText(), 20)).append("): ")
                    .append(PlainText.clean(finding.path("message").asText(), 300)).append('\n');
        }
        text.append("\n## Change diagram\n\n```mermaid\n").append(MermaidRenderer.render(data, mode)).append("```\n\n");
        section(text, "Checked", evidence.path("coverage").path("checked"));
        section(text, "Not checked", evidence.path("coverage").path("not_checked"));
        section(text, "Unavailable", evidence.path("coverage").path("unavailable"));
        text.append("## Limits of the diagram\n\n");
        data.limitations().forEach(item -> text.append("- ").append(item).append('\n'));
        if (includeExplanation) {
            ObjectNode explanation = explain(jobId);
            if (explanation.path("status").asText().equals("OK")) {
                text.append("\n## Explanation (").append(explanation.path("provider").asText()).append(", ").append(explanation.path("model").asText()).append(")\n\n")
                        .append(explanation.path("labelled").asText()).append("\n\n").append(PlainText.clean(explanation.path("summary").asText(), 1200)).append('\n');
            }
        }
        return text.toString();
    }

    private static void section(StringBuilder text, String title, JsonNode items) {
        text.append("## ").append(title).append("\n\n");
        if (!items.isArray() || items.isEmpty()) {
            text.append("- nothing\n\n");
            return;
        }
        items.forEach(item -> text.append("- ").append(PlainText.clean(item.asText(), 200)).append('\n'));
        text.append('\n');
    }

    public List<String> modes() {
        return java.util.Arrays.stream(MermaidRenderer.Mode.values()).map(Enum::name).toList();
    }
}
