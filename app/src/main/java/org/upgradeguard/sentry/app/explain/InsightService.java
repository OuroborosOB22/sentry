package org.upgradeguard.sentry.app.explain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
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
import org.upgradeguard.sentry.contract.Json;
import org.upgradeguard.sentry.explain.DiagramBuilder;
import org.upgradeguard.sentry.explain.DiagramData;
import org.upgradeguard.sentry.explain.DisabledExplanationProvider;
import org.upgradeguard.sentry.explain.ExplanationProvider;
import org.upgradeguard.sentry.explain.MermaidRenderer;
import org.upgradeguard.sentry.explain.OpenAiCompatibleProvider;

@Service
public class InsightService {
    private final JobService jobs;
    private final AppSettings settings;
    private final SentryProperties properties;
    private final JdbcTemplate jdbc;

    public InsightService(JobService jobs, AppSettings settings, SentryProperties properties, JdbcTemplate jdbc) {
        this.jobs = jobs;
        this.settings = settings;
        this.properties = properties;
        this.jdbc = jdbc;
    }

    private JsonNode evidence(String jobId) throws IOException {
        return jobs.evidence(jobId).orElseThrow(() -> new JobService.Rejected("this job has no evidence yet"));
    }

    public ObjectNode diagram(String jobId, MermaidRenderer.Mode mode) throws IOException {
        DiagramData data = DiagramBuilder.fromEvidence(evidence(jobId));
        ObjectNode out = Json.object();
        out.set("diagram", new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(data));
        out.put("mermaid", MermaidRenderer.render(data, mode));
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
        JsonNode evidence = evidence(jobId);
        ExplanationProvider provider = provider();
        String fingerprint = fingerprint(evidence, provider);
        Optional<String> cached = jdbc.queryForList("SELECT body FROM explanations WHERE job_id = ? AND fingerprint = ?", String.class, jobId, fingerprint).stream().findFirst();
        if (cached.isPresent()) {
            ObjectNode node = (ObjectNode) Json.parse(cached.get());
            node.put("cached", true);
            return node;
        }
        DiagramData data = DiagramBuilder.fromEvidence(evidence);
        ExplanationProvider.Result result = provider.explain(evidence, data);
        ObjectNode node = Json.object();
        node.put("status", result.status());
        node.put("provider", provider.id());
        node.put("model", provider.model());
        node.put("summary", result.summary());
        var annotations = node.putArray("annotations");
        result.annotations().forEach(item -> annotations.addObject().put("node_id", item.nodeId()).put("text", item.text()));
        result.sources().forEach(node.putArray("sources")::add);
        node.put("note", result.note());
        node.put("labelled", "Written by a language model from the measured facts. It is not evidence and never changes the verdict.");
        node.put("cached", false);
        if (result.status().equals(ExplanationProvider.Result.OK)) {
            jdbc.update("INSERT INTO explanations (job_id, fingerprint, provider, model, body, created_at) VALUES (?, ?, ?, ?, ?, ?)", jobId, fingerprint, provider.id(), provider.model(),
                    Json.text(node), java.sql.Timestamp.from(Instant.now()));
        }
        return node;
    }

    static String fingerprint(JsonNode evidence, ExplanationProvider provider) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((Json.text(evidence) + "|" + provider.id() + "|" + provider.model() + "|" + OpenAiCompatibleProvider.PROMPT_VERSION).getBytes(StandardCharsets.UTF_8));
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
        text.append("# ").append(change.path("package").asText()).append(' ').append(change.path("from_version").asText()).append(" to ").append(change.path("to_version").asText()).append("\n\n");
        text.append("Verdict: **").append(evidence.path("verdict").path("decision").asText()).append("**\n\n## Findings\n\n");
        for (JsonNode finding : evidence.path("verdict").path("findings")) {
            text.append("- `").append(finding.path("code").asText()).append("` (").append(finding.path("severity").asText()).append("): ").append(finding.path("message").asText().replace("\n", " ")).append('\n');
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
                        .append(explanation.path("labelled").asText()).append("\n\n").append(explanation.path("summary").asText()).append('\n');
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
        items.forEach(item -> text.append("- ").append(item.asText()).append('\n'));
        text.append('\n');
    }

    public List<String> modes() {
        return java.util.Arrays.stream(MermaidRenderer.Mode.values()).map(Enum::name).toList();
    }
}
